# ADR 0008：订单域分库分表——一个基因让「按用户查」和「按订单号查」落在同一片

- 状态：**已决定**（2026-09-20）
- 关联：[V2 计划 M6](../plan/v2-high-concurrency-plan.md)、[ADR 0004](0004-m3-admission-funnel.md)（订单号的基因）、
  [ADR 0003](0003-m2-order-lifecycle.md)（限购唯一键）、[ADR 0007](0007-m6-batch-fill.md)

## 背景

D7：订单表没有分片键设计，无法水平扩展。M3 做准入漏斗时已经埋了一半的解法——
`SnowflakeOrderIdGenerator` 把 `user_id % 1024` 放进每个订单号的低 10 位，当时写的理由就是
「为 M6 分片做准备」。M6 要兑现它。

难点不在「把表拆成 8 张」，在于**同一行有两种被叫到的方式**：用户查自己的订单带 `user_id`，
而支付、退款、核销、关单只拿得到订单号。两者必须落在同一片，否则每条语句都是广播。

## 决策

### 1. 槽位：`slot = key % 8`，`ds = slot % 2`，`table = slot / 2`

2 库 × 4 表 = 8 个槽位。**8 整除 1024**，所以 `order_no % 8 == user_id % 8`：
基因永远能算出槽位，两种查法必然同片。连号的用户交替落在两个库，而不是先把一个库填满。

派生单号同样继承基因，因此也单片命中：`pay_no` = `<order_no>-<n>`、
`refund_no` = `RU<order_no>` / `RA<pay_no>`、购买券的 `coupon_no` = `P<order_no>`。
**唯一没有基因的是营销发放的 `G<grant_id>`**，它按 `user_id` 写入，按 `coupon_no` 查时广播。

算法实现成一个 `ComplexKeysShardingAlgorithm`，规则里声明每张表「有哪些列能叫到这一行」。
两条语义是被测试钉住的：

- **一条语句的多个列描述的是同一行，所以取交集**。某一列算不出槽位（如 `G<grant_id>`）就跳过，
  由旁边的 `user_id` 定位——不是退化成广播。
- **两列指向不同槽位时不选边**，返回全部节点，于是 INSERT 被 ShardingSphere 当场拒绝。
  这正是我们要的：一行自己的两个键互相矛盾是 bug，不该被写进其中一片。

### 2. 分片表与单表：`tb_seckill_voucher` 不做广播表

分片：`trade_order`、`order_state_log`、`payment_record`、`refund_record`、`user_coupon`。
其余（券、店铺、用户、营销、RBAC）作为 ShardingSphere **单表**留在 ds_0。

`tb_seckill_voucher` 特别要说明：它看起来像「低频只读的配置表」，适合做广播表，但 `stock` 是**写热点**。
广播表的写会复制到每个库，两个库里的同一个库存数要保持是同一个数字就需要分布式事务——
把最后一道防线（`stock >= n` 条件更新）建立在一个需要 2PC 才能自洽的数字上，是把问题变复杂。
所以它是单表，只在 ds_0。

### 3. 唯一键：哪些还是全局的，哪些收窄成片内的

- `uk(user_id, voucher_id, active_flag)`（限购）**语义不变**。一个用户的行永远在同一张表里，
  所以片内唯一就是全局唯一。这也是分片键选 `user_id` 而不是 `voucher_id` 的原因。
- `uk(pay_no)`、`uk(refund_no)`、`uk(coupon_no)` 同理：都由订单号派生，可能冲突的行必然同表。
- `uk(channel_txn_no)`、`uk(channel_refund_no)`、`uk(verify_code)` **收窄为片内唯一**。
  前两者只会被同时带着 `pay_no` / `refund_no` 的语句碰到，回调幂等不受影响；
  `verify_code` 是 80 bit 随机串，跨片撞号不是需要设计的事情。三处都写进了列注释。
- 附属表的 `id` 去掉 `AUTO_INCREMENT`——每张物理表会各自从头数。没有任何查询按 `id` 路由，
  交给 ShardingSphere 的 `SNOWFLAKE` 生成。

### 4. 两个库各自迁移，ds_1 的 URL 从 ds_0 推导

`spring.flyway.enabled: false`，改由 `ShardingDataSourceConfiguration` 在构建路由数据源**之前**
分别迁移两个物理库：ds_0 跑完整历史（`db/migration`），ds_1 只跑分片表（`db/shard`）。
理由很实际：DDL 走路由层就得告诉它每条语句归谁，而迁移本来就是按物理库做的事。

ds_1 默认是「ds_0 的 URL 把 schema 加个 `_1`」，所以隔离栈、压测脚本和全部 IT 用的还是原来那套
环境变量，只是多了一个 schema（URL 里本来就带 `createDatabaseIfNotExist`）。
代价是 V16 把 pre-shard 的旧行搬进 ds_1 时要跨 schema 读，**因此两个库必须是同一实例的两个 schema**；
指到别的服务器上这条迁移会直接失败，而不是悄悄丢掉奇数槽位。
旧表是改名 `*_pre_shard` 而不是删除。

### 5. 事务：LOCAL，不上 XA

一个消费批次是一个事务，可能同时写 ds_0、ds_1（订单与审计）和 ds_0（扣库存）。
ShardingSphere 的 LOCAL 事务逐个物理连接提交，**不保证跨库原子**。仍然选它，理由是代价与收益不成比例：

- **XA 的代价没有实测**，所以这里不写数字；但 2PC 的额外往返与锁持有时间正好落在刚刚量到
  2000 单/s 的那条热路径上（[consume-sweep](../../benchmark/v2/m6/consume-sweep.md)），
  而它换来的原子性并不是这条链路正确性的唯一来源。在有证据说明 LOCAL 不够用之前不引入它。
- 崩在两次提交之间的后果是**有界的少卖，不是超卖**：没提交的那一半订单仍持有 Redis 预占，
  对账器发现 DB 无单会重投，`INSERT IGNORE` 让重放幂等；但这批的库存已经扣过一次，重放会再扣一次，
  于是 DB 库存**比真实值更低**。方向是安全的（宁可少卖），量级被批大小限住，
  且要求进程恰好死在两次提交之间的那不到一毫秒里。

`OrderShardRoutingIT.oneBatchOfOrdersIsOneTransactionOverBothDatabases` 把「一个批次确实横跨两个库」
钉成了事实，窗口的存在是这条测试的直接推论。**注意：故障注入本身没有做**——
这一条目前是推理 + 机制证据，不是实测。

### 6. 五类 SQL 形态被分片拒绝，都得改

分片不是「加个中间件就透明」。这次被当场挡住的有五类，每一类的理由都成立：

| 形态 | 报错 | 改法 |
| --- | --- | --- |
| `INSERT INTO 分片表 ... SELECT ... JOIN 单表` | the table inserted and the table selected must be the same | 应用侧读券快照，改普通多值 `VALUES` |
| `DELETE a FROM 分片表 a JOIN b` | Can not support DML operation with multiple tables | 先查出键，再按键逐条删 |
| `UPDATE 分片表 ... LIMIT n` | UPDATE ... LIMIT can not support route to multiple data nodes | 先 `SELECT ... LIMIT`（跨片归并是对的），再逐条按键更新 |
| `WHERE id = ?`（无分片键）+ `FOR UPDATE` | 不报错，但在 8 张表上各锁一行 | 谓词里带上 `user_id` |
| `SELECT ... FROM 单表 WHERE EXISTS (SELECT 1 FROM 分片表 ...)` | 被路由到一个没有该单表的库 | 拆成两问，在应用里合 |

第三类值得单独说：`UPDATE ... LIMIT 200` 如果放行，limit 会**按节点**生效，
「最多过期 200 张」会变成「最多 1600 张」。ShardingSphere 拒绝它是对的。

第四类没有报错，是最危险的一类——它只会变慢和多锁，不会失败。

第五类最隐蔽：它不是 join，是子查询，SQL 表面看不出跨了两种表。
`MarketingTagMemberMapper.countBusinessRelationship`（「这个用户和这个商户有没有业务关系」）
就栽在这里，拆成了「有没有发放」「有没有订单」「用户存不存在」三问一合。

### 7. 兜底扫描与对账器：不需要额外做什么，说清楚为什么

计划里写了「关单兜底扫描按分片并行」和「对账器读主库（Hint 强制）」。实际情况是：

- `selectOverduePending` / `selectReleasePending` 不带分片键，ShardingSphere 本来就把它们**并行发往
  全部节点再归并**，`ORDER BY ... LIMIT` 的归并语义也是对的。不需要应用层再分片一次。
- 「读主库」目前是空操作：本项目没有配读写分离，每个分片只有一个节点，不存在从库可读。
  等真的加了读写分离再谈 Hint，现在写进去只是装饰。

## 代价与边界

- **广播读仍然存在**：核销按 `verify_code` 查（店员扫码，低频）、营销券按 `coupon_no` 查、
  商户后台按 `merchant_id` 分页查订单。前两个的量级可以接受。
  第三个**是这次明确不修的**（2026-09-20 决定不做 ES 订单读模型）：它会向 8 张表各发一次查询再归并，
  结果正确但不随分片数扩展，分页深度越大越贵。要修它需要一条独立的读路径（Canal → MQ → ES 订单索引），
  那是另一个子系统，不属于这个里程碑。被问到时的答案就是这一段，而不是「分片之后商户查询也很快」。
- **多表 DML 不再可用**，包括测试清理。
- **`user_coupon.id` 用 SNOWFLAKE**，不再是递增的小整数；按 `id` 排序仍然是时间序。
- 两个库必须同实例（见决策 4）。
- LOCAL 事务的非原子窗口（见决策 5），且尚无故障注入证据。

## 实测

全部在 2 库 × 4 表的真实分片栈上（`STACK_ID=m6dev`，MySQL 一个实例两个 schema）：

- `mvn test` **473/473** 绿。
- 隔离栈全量 IT：执行 **94 个全绿**（另 49 个被环境门控跳过）。
- 营销业务 IT（打开 `M6A/M6B/M6C_ISOLATED` 门控）：**17/17** 绿，与 M2 当时同一组。
- 落位：`OrderShardRoutingIT` 对 8 个槽位逐一用**物理连接**核对——订单行与它的审计行都只出现在
  `slot = user_id % 8` 指定的那一张表里，按 `order_no` 查和按 `user_id` 查拿到同一行；
  限购在片内挡住同一用户，另外 7 个槽位照常下单。
- 一个批次 8 个不同槽位的用户：ds_0 落 4 行、ds_1 落 4 行，库存只扣一次。

两个**不属于分片、但被分片翻出来**的问题：

1. 加上 ShardingSphere 之后接口开始返回 XML（决策 6 之外的一件事）。它的元数据仓库带进
   `jackson-dataformat-xml`，Spring 就把 XML 转换器排在 JSON 之前，不带 `Accept` 头的请求
   拿到 `<Result>…`。**排除这个依赖会让启动直接失败**（运行时真的要 `XmlMapper`），
   最终在 `WebConfig` 里按「声明了 XML 媒体类型」删转换器。只有 4 个控制器测试抓到它。
2. `OrderCloseTimerIT` 的订单号是 `BASE + currentTimeMillis() % 1000`，买家固定 `BASE + 1`——
   两者的低三位**八次里只对上一次**。它在第一轮全量 IT 里碰巧过了，第二轮挂了。
   分片把一个本来就存在的随机性变成了确定性失败。
3. 关掉 `spring.flyway.enabled` 之后，`ShopIndexInitializer` 上的 `@DependsOn("flywayInitializer")`
   指向了一个不再存在的 bean，**应用在正常配置下启动失败**。473 个单测和 94 个 IT 都没看见它：
   那个 bean 标着 `@Profile("!test")`，唯一会创建它的是真实 jar，也就是压测脚本启动的那个。
   这条是**冒烟抓到的，不是测试抓到的**——也是「合并前必须冒烟」这条规矩当天就兑现了一次。
   `@DependsOn` 同时被删掉：迁移现在发生在构造数据源的过程中，任何持有数据源的 bean 本来就排在它之后。
4. **`TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3))` 的单位参数被当成列名**，点赞 outbox 的清理
   每次都抛 `Unknown column 'SECOND'`。注意这张表**从未分片**——ShardingSphere 解析每一条语句，
   所以「只有分片表要小心」是错的判断。改成 `DATE_SUB(..., INTERVAL ? SECOND)`。
   同样是冒烟抓到的：这条定时语句原本只有一个握着 mock `JdbcTemplate` 的单测，
   而 mock 能检查「发出去的 SQL 长什么样」，永远不能回答「有没有人接受它」。
   现在 `ScheduledStatementsIT` 与 `BlogLikeOutboxCleanupStatementIT` 把四条定时语句
   真的对分片数据源各执行一次。
5. **压测脚本自己也读订单表**：`fixture.py` 用裸 mysql 客户端数 `trade_order`，分片后那张表不在了。
   它改成先问 `information_schema` 这个 schema 里是一张逻辑表还是四张物理表，
   于是同一条命令能同时量 pre-M6 基线与分片构建——A/B 的两侧。

顺带记一处**我自己的错误推断**：看到 `Unknown column 'SECOND'` 时我先去改了三个 mapper 里的
`NOW(3) - INTERVAL ? SECOND`，以为是减法形式的问题。它不是——`OrderCloseIT` 一直在执行那条语句且是绿的。
改动已撤回，只留真正的修复。

### 那个「落库退化」是压测脚本的 bug，不是分片

冒烟里量到基线 388.6 单/s、当前 111.4 单/s，看着像分片让落库掉了三倍多。**它不是。**
长测（`benchmark/v2/m6/20260921-002753-m6`，`status=DONE`）把原因摆出来了：
当前构建那三轮 drain 的 `accepted` 是 1999 / 2001 / 2037，而基线是 20000，
并且 outcomes 里写着 `duplicate=20000`——两万个请求全被判成重复购买，
所以那个「落库速率」根本是在量一个小十倍的工作量。

成因是**我为 M6 加的 `S_BASELINE_SCHEMA` 自己引出来的**：基线从此跑在自己的 schema 上，
两个 schema 的 `tb_voucher` 自增各数各的，于是两个构建**铸出相同的券号**；
而它们共享同一个 Redis，判重键按券号 + 用户，基线先让 10 万用户买过券号 14，
当前构建再用券号 14，当然每个人都是「重复」。修法是给基线 schema 的券号一个远离的起点
（`BASELINE_VOUCHER_ID_BASE`，默认 100 万），`scripts/bench.sh` 在基线启动后设置一次。

**这一轮 A/B 的 drain 部分因此作废，需要重跑。** 阶梯部分不受影响（两侧 `accepted` 都等于库存 1000）：

| 指标 | 基线 `v2.0-m5` | 分片构建 |
| --- | ---: | ---: |
| 准入 10k 档 | 9933.1 req/s | 9892.5 |
| 准入 20k 档 | 19694.5 req/s | 19726.0 |
| 应用 CPU（10k / 20k） | 0.68 / 0.99 核 | 0.69 / 0.95 |
| 1000 单的落库 | 710.4 / 738.7 单/s | 702.0 / 745.7 |
| `accepted` | = 库存，无超卖 | = 库存，无超卖 |

也就是说**在阶梯这一半上，把订单拆进八张表没有可测代价**。
真正还没有答案的是「持续两万单的落库有没有变慢」，那要等重跑。

**没有做的**：LOCAL 事务窗口的故障注入（决策 5）、商户侧 ES 订单读模型与其端到端延迟实测
（计划 M6 的最后一项，见「代价与边界」第一条）。
