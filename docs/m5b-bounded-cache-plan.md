# M5B 有界缓存实施计划

> 文档状态：待实施；本文件只定义契约、任务、证据门禁和停止线
>
> 记录日期：2026-08-20
>
> 仓库：`/home/sd101t/IdeaProjects/hm-dianping`
>
> 预期分支：`codex/platform-hardening`
>
> 规划起点：`5617826 docs: mark M5A complete and prioritize M5B`
>
> M5A 测量证据：`4bbc2ba test(observability): capture isolated M5A baseline evidence`

本文是 `docs/modernization-roadmap.md` 中 M5B 的任务级执行手册。下一实施会话只完成商铺详情和商铺类型字典的有界缓存，不实施 M5C 流控/HTTP 503 统一语义，不开始 M5D、M6，也不重跑 M5A 中已停止的 Broker 故障补测。

M5A 唯一未闭合项“Broker 不可用时的新秒杀准入”继续保持 `BLOCKED`。M5B 不依赖该项，也不得借商铺读路径成功推断秒杀 producer 安全。

## 1. 阶段目标

M5B 只回答以下四个问题：

1. Redis 命令卡住、连接断开或缓存值损坏时，MySQL 健康的商铺详情/类型读取能否在有限时间内返回 MySQL 真相；
2. 同一实例内，同一个冷 key 的并发 miss 能否合并为一次 DB 加载，而不是每个请求各自回库；
3. 不存在的商铺和空字典能否使用短 TTL 吸收重复穿透，同时不被解释为永久不存在；
4. 后台商铺创建、更新或归属变更提交后，缓存是否只在事务提交后失效；失效失败时是否不回滚已提交事实，并由物理 TTL 限制陈旧窗口。

本阶段的成功叙述应为：

> Redis 只是商铺读模型。缓存 hit 直接返回；miss、坏值或 Redis 不可用时回到 MySQL；并发冷 miss 在单实例内合并；缓存写入和提交后失效均为 best-effort，MySQL 仍是事实源，失败不会把坏缓存当空数据。

这不是“多级缓存平台”“高可用已经完成”或“性能提升百分比”的声明。

## 2. M5A 证据与当前源码事实

### 2.1 必须复用的基线

- B1 商铺详情三轮、20 线程、每轮 1000 请求：cold 吞吐中位数 `1082.251 req/s`、P99 中位数 `2 ms`、每轮 `998 hit / 2 miss / 2 DB fallback`；warm 为 `1000 hit / 0 miss / 0 fallback`、P99 中位数 `1 ms`。
- 不存在 ID 的 pilot 只有首次 DB fallback，之后由当前空值缓存吸收；没有证据支持布隆过滤器。
- shop type pilot 为 `99 hit / 1 miss / 1 fallback`。
- F1 Redis 停止后，20 次商铺请求全部超过客户端 2 秒上限且没有 HTTP 响应；当前 `CacheClient` 在 Redis 读/写异常时直接抛出。
- F2 MySQL 停止后，冷 miss 同样超过 2 秒；这是 M5C 的 DB 并发/失败语义问题，M5B 不伪装成已经解决。
- 热榜已经具备坏状态/Redis 异常时的 MySQL 安全回退和自己的 singleflight/generation 协议；M5B 只验证全局 Spring Data Redis 命令超时没有破坏该路径，不改写 M4 协议。

### 2.2 当前代码落点

实施前重新核对以下事实；任何一项变化都先更新本文：

- `CacheClient.queryWithPassThrough(...)` 使用原始 JSON 对象和 `_NULL_PLACEHOLDER_`，Redis get/set 任一异常都会传播给 HTTP 请求；
- `ShopServiceImpl.queryShopById(...)` 当前调用 pass-through，正值和空值实际都使用 `30 seconds`；
- `ShopTypeServiceImpl.queryTypeList()` 使用原始 JSON 数组，正值 TTL 为 `100 minutes`，Redis 异常直接传播，DB 空列表不缓存；
- `AdminCatalogService` 已使用 `TransactionSynchronization.afterCommit()` 删除 `cache:shop:{id}`，但缓存删除和 GEO 同处一个 `try`，前者失败会跳过后续 GEO 同步；
- `application.yaml` 尚未设置 `spring.redis.timeout` 和 lettuce pool `max-wait`；
- 本 checkout 的 Spring Boot `2.3.12.RELEASE` `RedisProperties` 支持 `spring.redis.timeout`，pool 支持 `max-wait`，但没有 `spring.redis.connect-timeout` 属性；禁止把新版本属性直接写进配置并假设生效；
- Redisson 使用独立客户端配置，不会继承 Spring Data Redis 的 command timeout；M5B 不调整 Redisson 的消费/对账锁策略。

### 2.3 当前兼容格式

M5B 不迁移 Redis key 或 payload：

| 资源 | key | 正值格式 | 空值格式 |
| --- | --- | --- | --- |
| 商铺详情 | `cache:shop:{shopId}` | 原始 `Shop` JSON | `_NULL_PLACEHOLDER_` |
| 商铺类型 | `cache:shop:type:list:` | 原始 `ShopType[]` JSON | JSON 空数组 `[]` |

保持格式不变使新旧实例可滚动共存，也避免一次缓存全量刷新。教学用 `RedisData` 逻辑过期 envelope 不能写入上述线上 key。

## 3. 范围与非目标

### 3.1 本阶段包含

- 为 Spring Data Redis 同步命令和 lettuce 连接池等待设置有界时间；
- 商铺详情和商铺类型在 Redis read error 时 fail-open 到 MySQL；
- 缓存 write error 不覆盖已经取得的 MySQL 结果；
- 坏 JSON、错误对象 ID、非法列表等坏缓存不作为 hit 或 empty，回源后 best-effort 修复；
- 进程内、按资源和 key 的 singleflight，合并并发 DB fallback；
- 明确正值 TTL、空值 TTL 及单位，保持物理过期；
- 完整验证后台商铺写入的 after-commit 失效、rollback 不失效和失效失败行为；
- 扩充有限、低基数缓存指标；
- 建立 M5B 专用的并发、坏值、Redis pause/stop、恢复和缓存命中证据；
- 形成结果文档、小型 CSV 和后续 M5C 遗留清单。

### 3.2 明确不做

- 不实现布隆过滤器；
- 不启用 `queryWithLogicalExpire`、`queryWithMutex` 或教学版无限自旋；
- 不增加 Caffeine/JVM 长期缓存、Redis 分布式锁、Redisson 双重锁或通用缓存框架；
- 不承诺跨实例只回源一次；本阶段 singleflight 上界是“每实例、每 key 一次并发加载”；
- 不修改博客热榜 generation、top-K、DB fallback 或 M4 点赞事实边界；
- 不改搜索降级，不增加无界 MySQL `%LIKE%`；
- 不实现秒杀、验证码、后台登录或读接口限流；
- 不在本阶段统一 429/503/业务码；Redis 与 MySQL 同时不可用时的稳定 HTTP 语义留给 M5C；
- 不调整 Hikari connection timeout、DB bulkhead、Redisson retry/timeout、MQ、ES 或 Canal；
- 不新增商铺类型后台 CRUD；当前没有真实写入口，不能为“缓存失效”虚构业务功能；
- 不重跑 Broker unavailable 新秒杀准入，不清理共享 Broker，不改变该项 `BLOCKED` 状态；
- 不升级 Java、Spring Boot、Spring Data Redis、Lettuce、Redisson 或中间件；
- 不 push。

## 4. 目标语义矩阵

| 场景 | 必须返回/执行 | 禁止行为 | 指标事实 |
| --- | --- | --- | --- |
| 合法正值 hit | 返回缓存对象/列表，不访问 DB | 额外查 DB 验真 | `access=hit` |
| 合法空值 hit | 商铺返回现有“不存在”业务结果；类型返回空列表 | 永久缓存；把异常当空值 | `access=empty_hit` |
| Redis miss | 进入 per-key singleflight；leader 查 DB，followers 共享同一结果 | 每个请求独立回库 | 每请求一个 `miss`；一个 `leader`，其余 `shared`；DB outcome 只记实际 DB 调用 |
| 坏 JSON/错误 shape/商铺 ID 不匹配 | 记录坏值，进入 singleflight 回 DB；DB 成功后覆盖该 exact key | 返回部分反序列化对象；解释为不存在 | `access=bad_value` + DB outcome |
| Redis timeout/断连 | 只等待一个有界 Redis read；随后 singleflight 回 DB；本次跳过 Redis write | 再执行一次必然超时的 set；返回空值 | `access=redis_error`；maintenance `write=skipped` |
| DB 返回对象/列表 | leader best-effort 写正值 TTL，写失败仍返回 DB 结果 | 因缓存 set 失败把成功读改成失败 | `db_success`；maintenance write success/failure |
| DB 返回不存在/空列表 | best-effort 写短空值 TTL，返回业务空结果 | 写永久空值 | `db_empty`；maintenance write success/failure |
| DB 抛异常 | 原样传播当前 DB 异常；followers 收到同一失败；不写空值 | 把 DB 故障伪装成不存在 | `db_error`，singleflight entry 必须移除 |
| 后台事务提交 | after-commit 精确删除 `cache:shop:{id}`；GEO 同步独立执行 | 提交前删缓存；缓存失败阻止 GEO 尝试 | maintenance evict success/failure |
| 后台事务回滚 | 不删缓存、不改 GEO | 暴露未提交值 | 无 maintenance success |

补充边界：

- Redis 读失败后跳过本次缓存写，是为了让一次请求只承担一次 Redis timeout；后续请求仍可探测 Redis 恢复。
- 缓存 parse 成功不等于语义合法。商铺至少校验 `id` 与请求 ID 一致；类型列表校验列表/元素非 null、ID 为正且无重复 ID。
- 缓存失败日志可包含用于排障的 shopId，但指标标签禁止任何 ID、key、异常文本。
- `Result.fail("店铺不存在")` 的现有 HTTP 200 业务包装本阶段不调整；稳定 HTTP 状态码属于 M5C。

## 5. 配置契约

### 5.1 Redis 同步等待

第一版只使用当前 Spring Boot 明确支持的属性：

```yaml
spring:
  redis:
    timeout: "${LOCAL_DEALS_REDIS_COMMAND_TIMEOUT:500ms}"
    lettuce:
      pool:
        max-wait: "${LOCAL_DEALS_REDIS_POOL_MAX_WAIT:500ms}"
```

要求：

- 默认 command timeout 和 pool max-wait 均为 `500ms`；运行证据必须记录实际覆盖值；
- 不能使用 `spring.redis.connect-timeout`，因为 Boot 2.3 不绑定它；
- 不能用 `CompletableFuture`/额外线程包住同步 Redis 调用制造表面 timeout，底层命令仍运行会耗尽线程/连接；
- 这两个属性会影响所有 `StringRedisTemplate` 调用，因此必须跑认证、秒杀、Outbox、热榜和采样器的风险回归；这些关键写路径在 Redis 不可用时仍保持原 fail-closed/重试语义；
- M5B 只改变等待上界，不把 Redis 资格状态机改成 DB fallback。

若真实 `pause` 与 `stop/reconnect` 两类测试中任一请求仍超过 `1500ms`，执行者先用 full-context 测试读取 `LettuceConnectionFactory` 的实际 command timeout 和 client options。只有证明瓶颈是 socket connect 后，才允许增加 `LettuceClientConfigurationBuilderCustomizer`，显式保留 `TimeoutOptions.enabled()` 并设置 `SocketOptions.connectTimeout(500ms)`。该分支必须单独提交、增加配置契约测试并重跑全部 Redis 风险套件；不得通过不存在的 YAML 属性或请求线程池绕过问题。

### 5.2 缓存 TTL

新增小型、强类型 `local-deals.cache` 配置，默认值保持 M5A 实际行为，避免顺手改变容量：

| 属性 | 默认值 | 说明 |
| --- | ---: | --- |
| `shop-detail-ttl` | `30s` | 保持当前商铺正值实际 TTL；也限制 after-commit 失效失败/并发回填竞态的最坏陈旧窗口 |
| `shop-detail-empty-ttl` | `30s` | 保持当前空值实际 TTL；新建商铺的 after-commit 删除会提前解除负缓存 |
| `shop-type-ttl` | `100m` | 保持当前只读小字典 TTL；当前无后台写入口 |
| `shop-type-empty-ttl` | `30s` | DB 暂时为空时避免每请求回库，又不会长期冻结空字典 |

所有 Duration 必须大于 0；详情/字典 empty TTL 不得大于对应正值 TTL。不要继续依赖“数值常量 + 调用点 TimeUnit”的隐式单位组合。

### 5.3 一致性边界

- MySQL 是唯一事实源；Redis payload 没有版本号，本阶段不声称强 read-after-write。
- after-commit 删除与并发旧 DB 读回填存在经典 cache-aside 竞态；当前以详情 `30s` 物理 TTL 给出明确陈旧上界，不引入 delayed double delete 或版本平台。
- 类型字典当前只读。若以后新增类型写接口，必须在同一功能提交中增加 after-commit exact-key 失效；不能依赖前端刷新或手工清缓存。
- 直接修改数据库绕过应用不属于受支持的缓存一致性路径；运行手册可以提供精确 key 失效命令，但不能使用 `FLUSHALL`。

## 6. singleflight 设计

新增一个 Spring 单例、进程内的 per-key loader，建议使用 `ConcurrentHashMap<String, FutureTask<?>>` 或等价 Java 8 原语：

1. key 由有限 resource 前缀和完整 cache key 组成，只用于内存协作，不进入 metric tag；
2. 第一个调用者原子放入任务并在当前请求线程执行 DB fallback；不新建通用线程池；
3. 并发调用者等待同一个任务并共享值、空值或异常；
4. leader 完成缓存 best-effort 写入后再完成 Future，使 follower 返回时 key 已尽量可读；
5. `finally` 使用 compare/remove 精确移除本次 entry；成功、空值、DB 异常和中断都不能泄漏 map；
6. follower 中断时恢复 interrupt flag，不把中断解释为“数据不存在”；
7. DB fallback Timer/DB outcome 只由真正执行 DB 的 leader 记录；每个参与请求各记录一次 `leader|shared`；
8. M5B 不给 DB 查询套线程级超时。leader 若被 MySQL/Hikari 阻塞，followers 会共享该阻塞而不会继续放大 DB；MySQL 等待上界和 503 由 M5C 处理。

验收上界是“同一 JVM、同一 key、并发重叠窗口一次 DB 调用”。多实例最多各一次，不宣称集群级 singleflight。

## 7. 指标契约增量

先更新 `docs/m5a-metric-catalog.md` 的 M5B 演进小节，再编码。保留现有名称并只增加有限枚举：

| 指标 | 类型 | 标签和值域 | 语义 |
| --- | --- | --- | --- |
| `local_deals.cache.access` | Counter | 既有 `resource`; `result` 新增 `bad_value` | Redis 读取阶段；坏 payload 不计 hit/miss |
| `local_deals.cache.singleflight` | Counter | `resource=shop_detail|shop_type`; `result=leader|shared` | 每个进入 DB load 合并边界的请求一次；不能从该指标推导跨实例全局调用数 |
| `local_deals.cache.maintenance` | Counter | `resource`; `operation=write|evict`; `result=success|failure|skipped` | DB fallback 后缓存写和 after-commit 失效；Redis read 已失败时本次 write 为 skipped |
| `local_deals.cache.db_fallback` | Timer | 既有 `resource` | 扩展说明为 miss、bad_value、redis_error 后的实际 DB 调用；followers 不重复记录 |

要求：

- 全部组合启动时预注册；
- 不把 shopId、key、线程名、异常类或 message 放入标签；
- `redis_error` 只描述 read boundary，write/evict 失败使用 maintenance，避免一次错误重复计为多个 read error；
- 指标异常不能改变业务返回；
- 结果文档使用运行窗口 delta，不使用进程累计值。

## 8. 任务分解

预期文件落点如下；执行者可按现有包命名微调，但不能扩大业务范围：

| 类别 | 预期文件 |
| --- | --- |
| 配置 | `application.yaml`、`.env.example`、新增 `BoundedCacheProperties`，必要时新增经过测试的 lettuce customizer |
| 缓存核心 | `CacheClient`、新增 `SingleFlightLoader`（或等价小类）、`RedisConstants` 中不再使用的隐式 TTL 常量 |
| 业务接入 | `ShopServiceImpl`、`ShopTypeServiceImpl`、`AdminCatalogService` |
| 指标 | `LocalDealsMetrics`、`docs/m5a-metric-catalog.md` 的 M5B 增量说明 |
| 测试 | 对应 unit/context/真实 Redis/MySQL IT；不把共享依赖默认地址当测试夹具 |
| 证据 | `scripts/m5b-isolated-stack.sh`、`scripts/run-m5b-cache-check.sh`、`.gitignore`、M5B results/summary |
| 收口 | `README.md`、`docs/modernization-roadmap.md` |

### 任务 0：冻结起点与隔离门禁

- [ ] 核对 branch、HEAD、status 和 `git diff --check`；规划起点链应包含 `5617826`，其后只能有经审查的 M5B 计划提交；
- [ ] 阅读路线图第 3、4、8、11、12、15 节和本文件全部内容；
- [ ] 阅读 M5A results 第 3.2、4、5、6 节，不重跑 M5A；
- [ ] 阅读 `docs/blog-like-hot-rank.md`，确认不改 generation/like 真相边界；
- [ ] 核对 Java 8、Maven 和依赖版本；
- [ ] 列出本阶段预期修改文件，保护任何用户已有改动；
- [ ] 确认真实依赖全部为带 run-id、专用 schema/key 的隔离环境；
- [ ] 应用启动必须使用真实属性名 `ROCKETMQ_NAME_SERVER` 指向专用 NameServer，即使本阶段不注入 MQ 故障；禁止再次使用 `LOCAL_DEALS_ROCKETMQ_NAME_SERVER`；
- [ ] 不读取、打印或提交 `.env` 密码。

退出条件：现场可解释，工作区只有本阶段计划/实现文件，依赖身份可由 sentinel/fixture 证明。

### 任务 1：先定配置和指标契约

- [ ] 增加 `BoundedCacheProperties`（名称可按包规范调整），使用 `Duration` 并做启动校验；
- [ ] 在 YAML 和 `.env.example` 中增加 command timeout、pool max-wait、四个 TTL 的安全默认值；
- [ ] 增加配置绑定测试，确认 Boot 2.3 实际读取 `500ms`，不是只断言 YAML 字符串；
- [ ] 更新 metric catalog 和 `LocalDealsMetrics` 有限枚举；
- [ ] 增加 meter 预注册/低基数测试；
- [ ] 不在此任务改变查询行为。

建议 Git 节点：

```text
docs(cache): define M5B bounded-cache contracts
```

### 任务 2：实现 per-key singleflight

- [ ] 新增可独立单测的 loader；
- [ ] 32 个并发调用通过 latch 同时到达，DB callback 必须恰好执行 1 次；
- [ ] 空结果由所有 follower 一致共享；
- [ ] leader 异常由所有 follower 观察到且 map 清空；
- [ ] follower 中断恢复线程标记，map 无泄漏；
- [ ] 不创建 static executor，不 sleep 自旋，不使用 Redis 锁；
- [ ] 只对 `SHOP_DETAIL` 和 `SHOP_TYPE` 启用。

### 任务 3：加固 CacheClient 与两个读服务

- [ ] 保留现有 key/payload；拆开正值/空值 TTL；
- [ ] 把 Redis read 结果显式分类为 hit、empty、miss、bad、unavailable；
- [ ] 商铺对象做 ID 一致性校验；字典列表做有限结构校验；
- [ ] miss/bad/unavailable 都进入相同 singleflight DB load；
- [ ] Redis unavailable 后跳过本次 set；bad string 在 DB 成功后覆盖 exact key；
- [ ] DB error 不写空值、不改成 `Result.fail("店铺不存在")`；
- [ ] Redis set 失败只记录 metric/限频日志，返回 DB 真相；
- [ ] 类型 DB 空列表写 `[]` + 30s，而不是每请求回库；
- [ ] `ShopServiceImpl` 和 `ShopTypeServiceImpl` 使用构造器注入本阶段依赖，便于窄单测；
- [ ] 不调用/扩展教学版逻辑过期、自旋锁和 `RedisData` 写入路径。

建议 Git 节点：

```text
feat(cache): add bounded fallback and per-key singleflight
```

### 任务 4：收口后台写后失效

- [ ] 为 create/update/assign 分别验证只在 commit 后删除详情 key；
- [ ] rollback 时不删除；
- [ ] create 能清除相同新 ID 的历史负缓存；
- [ ] 缓存 delete 失败不改变已提交 DB 结果；
- [ ] 将详情 eviction 与 GEO remove/add 分开 try/catch，任一失败不阻止其他 after-commit 动作；
- [ ] 为 eviction success/failure 记录有限 maintenance 指标；
- [ ] 不增加“删除缓存后再更新 DB”或 delayed double delete；
- [ ] 文档明确最坏陈旧窗口由 30s TTL 限制。

### 任务 5：单元、契约与真实依赖测试

最小测试集合：

- `BoundedCachePropertiesTest`：默认/覆盖/非法 Duration；
- `SingleFlightLoaderTest`：并发一次加载、空值、异常、中断、清理；
- `CacheClientTest`：hit、empty hit、miss、坏 JSON、ID 不匹配、Redis get error、DB error、Redis set error、TTL 和指标计数；
- `ShopTypeServiceTest`：数组 hit、空数组、坏元素、DB 空列表短缓存；
- `AdminCatalogServiceTest`：commit/rollback、eviction failure、GEO 独立执行；
- `RedisTimeoutConfigurationTest`：full context 读取实际 command timeout/pool max-wait；
- 真实 Redis IT：正值/空值 TTL、坏值修复、wrong type 安全回退、并发 singleflight、exact-key cleanup；
- 真实 MySQL + Redis IT：详情/类型 DB 真相、后台提交后失效、回滚不失效；
- M4 热榜 Redis IT：坏状态仍回 DB，不把 timeout 解释为空榜；
- 风险回归：认证 fail-closed、秒杀 Lua/producer/consumer/reconciliation、Outbox Redis 锁 fallback、Redis backlog collector unavailable/NaN。

真实 IT 必须显式选择并记录专用 schema/Redis，不允许 `localhost:6379` 的默认值在未验证 sentinel 时运行。

### 任务 6：M5B 专用基线与故障证据

新增 `scripts/m5b-isolated-stack.sh` 和 `scripts/run-m5b-cache-check.sh`。前者可机械复用 M5A
已经验证的容器所有权/清理结构，但必须使用独立的 `M5B_*` 环境变量、容器名、
`com.localdeals.m5b.run-id` label、schema/sentinel 和 `benchmark/m5b/` 路径；不要重构或改变
M5A 原脚本的历史复现行为。M5B 仍启动一套完整的专用 MySQL、Redis、ES、NameServer 和
Broker，Broker/ES 只作为应用正常启动依赖，不注入故障。

HTTP/JMeter 解析逻辑可以复用，但结果只写入已忽略的 `benchmark/m5b/<run-id>/`。不要修改
M5A 已保存结果，不把新数据追加到 M5A CSV。

脚本必须：

- [ ] 要求 `M5B_ISOLATED=true`、合法 run-id、loopback 应用和全部专用端口；
- [ ] 正式比较只启动一个应用实例，使 per-instance singleflight 的门禁含义确定；
- [ ] 在 stop/pause/unpause/start/delete 前检查容器 run-id label；
- [ ] 使用 trap 只恢复本轮 Redis，不能触碰共享容器；
- [ ] 用专用 MySQL fixture + Redis sentinel 双向证明应用确实连到目标依赖；
- [ ] 检查响应 body 的 `success`、ID/名称/列表内容，不能只统计 HTTP 200；
- [ ] 保存前后 Prometheus delta、MySQL query delta、Redis commands、P50/P95/P99/max、错误数和恢复时间；
- [ ] 原始 JTL/Prometheus/log 不提交，小型汇总 CSV 可提交；
- [ ] 将 `/benchmark/m5b/` 加入 `.gitignore`。

正式场景：

| 编号 | 场景 | 流量与动作 | 硬门禁 |
| --- | --- | --- | --- |
| C1 | 详情 cold/warm 对比 | 沿用 B1：20 线程、1000 请求、三轮；每轮前删 exact key | cold 每轮实际 DB fallback `<=1`；warm 为 0；所有 body 正确 |
| C2 | 不存在 ID | 20 并发 cold，随后 100 warm；检查 placeholder TTL | cold DB fallback `<=1`；warm DB fallback 0；无 false positive；TTL `>0` 且不超过配置 |
| C3 | 类型字典 | 20 并发 cold，随后 100 warm；另测 DB 空列表 fixture | 每轮 cold fallback `<=1`、warm 0；排序/内容正确；空列表使用短 TTL |
| C4 | 坏缓存 | 写入 malformed JSON、错误 shop ID、非法列表，逐项请求两次 | 首次回 DB 且 `bad_value` 增加；第二次 hit 修复值；从不返回错误对象/假空 |
| F1a | Redis pause | 预热后 `docker pause` 专用 Redis，20 个并发详情请求 | 20/20 在 `<2s` 内返回正确 DB 数据，0 transport error，`redis_error>0`，不重复等待 set timeout |
| F1b | Redis stop/reconnect | 停专用 Redis，小流量 20 请求；启动并等待 ready | 故障期同上；ready 后 5 秒窗口内缓存可重新写入并转为 hit |
| F2a | MySQL stop + warm cache | 确认剩余 TTL 覆盖测试窗口后停止专用 MySQL，10 秒内读详情/类型 | 全部返回预热值且 DB fallback=0；不外推超过 TTL 后可用性 |
| R1 | 热榜回归 pilot | 开启 M4 read/refresh，Redis pause 时请求一页 | 请求 `<2s` 回 MySQL 正确榜单；不返回空榜；不改 generation 协议 |

F1 的 `<2s` 是从 M5A 客户端停止线导出的端到端硬门禁；目标实现应以 `500ms` Redis 上界留出 DB 和序列化余量。另记录 P99/max，但不得只用 P99 掩盖超时请求。

C1 的性能仅设灾难性回归保护：同一机器/流量下 warm 吞吐中位数不得低于 M5A 的 90%，最差 P99 不得超过 `10ms`。若主机负载与 M5A 不可比，性能项标记不可比并保留正确性/DB 放大证据，不得改阈值后声称提升。

### 任务 7：全量回归、结果和收口

- [ ] 使用 Java 8 从 clean target 执行默认测试，报告实际 test/failure/error/skipped 数，不预填旧的 242；
- [ ] 执行缓存定向套件和所有受全局 Redis timeout 影响的风险定向套件；
- [ ] 执行专用真实 Redis/MySQL IT，逐项列数量；
- [ ] `bash -n` 检查新增/修改脚本；
- [ ] 检查小型 CSV 列数、状态字段和缺失值，不把失败填 0；
- [ ] 执行 `git diff --check`；
- [ ] 形成 `docs/m5b-bounded-cache-results.md` 与 `docs/m5b-bounded-cache-summary.csv`；
- [ ] README 只写已验证事实和边界；
- [ ] 路线图把 M5B 标为完成、下一会话只进入 M5C；M5A Broker 项仍为 BLOCKED；
- [ ] 审查 staged diff，无 `.env`、凭据、target、JTL、日志、全量 Prometheus 或容器数据；
- [ ] 只删除带本轮 label 的容器/网络，核对无本轮残留；
- [ ] 创建本地提交，不 push，最后要求工作区干净。

## 9. 验收门禁

M5B 只有同时满足以下条件才可标记完成：

- [ ] key/payload 与旧版本兼容，无数据迁移；
- [ ] Boot 2.3 实际 command timeout 和 pool max-wait 有 full-context 证据；
- [ ] Redis pause、stop 两类故障下 20/20 商铺请求均在 2 秒内返回正确 DB 真相；
- [ ] Redis read error 后不执行第二次必然超时的 cache set；
- [ ] cache set/evict 失败不改变 MySQL 成功读写结果；
- [ ] 坏缓存永不解释为不存在/空列表，并可在 DB 健康时修复；
- [ ] 同 JVM 同 key 并发 DB fallback 恰好一次；真实 C1 三轮每轮 fallback `<=1`；
- [ ] 空值 TTL、正值 TTL、类型空列表 TTL 均与配置一致且大于 0；
- [ ] create/update/assign 只在 commit 后失效，rollback 不失效；
- [ ] eviction failure 不阻止 GEO after-commit 尝试；
- [ ] 全局 Redis timeout 没有破坏认证/秒杀 fail-closed、已接受订单消费/对账、Outbox 和热榜安全回退；
- [ ] MySQL cold miss 超时没有被伪报为已解决；
- [ ] 没有引入布隆过滤器、逻辑过期、多级缓存、动态限流或 M6 功能；
- [ ] 默认测试、风险定向、真实依赖 IT、脚本语法和 diff 检查全部通过；
- [ ] 结果文档保存负面/不可比项，不写未经同条件三轮支持的性能提升百分比；
- [ ] F3 Broker 新准入仍明确为 BLOCKED；
- [ ] 本地提交后工作区干净且未 push。

建议实现提交链：

```text
docs(cache): define M5B bounded-cache contracts
feat(cache): add bounded fallback and per-key singleflight
test(cache): verify bounded cache and Redis recovery
docs(cache): record M5B evidence and prioritize M5C
```

每个提交必须可独立解释；不要把结果文档提交在产生证据的代码之前，也不要为匹配建议数量强行拆分一个不能单独通过测试的提交。

## 10. 阶段停止线

出现以下任一情况，立即停止新增实现/故障流量并记录 `BLOCKED` 或失败：

- 发现应用、脚本、MySQL schema、Redis sentinel、容器 label 或 RocketMQ NameServer 指向共享环境；
- 工作区出现无法归属的用户改动，且实现会覆盖同一文件；
- Redis pause/stop 后发生越权放行、秒杀绕过资格、重复订单、负库存或点赞恒等式破坏；
- 坏缓存被返回给用户或被解释为空数据；
- singleflight 出现死锁、entry 泄漏、异常吞掉或线程池无界增长；
- 全局 Redis timeout 使正常环境中的已接受订单消费/对账产生语义回归；
- 需要修改 Hikari、Redisson、MQ、ES、HTTP 429/503 或建立通用流控平台才能继续；这些属于 M5C/M5D，不能夹带；
- 两种受控 timeout 配置尝试后，Redis 故障请求仍无法在 2 秒内返回；保留测量结果并将具体连接阶段标为 BLOCKED，不无休止调参；
- cleanup 不能用 run-id 和 exact resource 证明范围；
- 任何测试只能靠放宽业务正确性、把缺失值填 0 或删除负面结果才能通过。

普通吞吐波动、没有显著性能提升或多实例仍各回源一次不是 P0。只要契约和门禁真实成立，可以作为边界记录；不能为了“更高级”继续堆组件。

## 11. 实施完成后的交接内容

实施会话最终必须报告：

1. branch、起始/最终 HEAD、完整本地提交链和 `git status`；
2. 实际 Java/Maven/MySQL/Redis 版本、run-id、schema、端口和隔离证明；
3. 默认、风险定向、真实 IT 的命令与精确计数；
4. C1-C4、F1a/F1b、F2a、R1 的样本数、正确响应数、P95/P99/max、DB fallback、指标 delta 和恢复时间；
5. Redis timeout/TTL 的实际配置值；
6. 未解决的 MySQL cold miss、HTTP 状态、跨实例放大、Redisson timeout 等 M5C/M5D 边界；
7. M5A Broker 新准入仍为 BLOCKED 的明确声明；
8. 精确清理了哪些本轮资源、是否可恢复、是否存在残留；
9. 未 push。

## 12. 可复制到实施会话的提示词

```text
请先阅读：
1. /home/sd101t/IdeaProjects/hm-dianping/docs/modernization-roadmap.md
2. /home/sd101t/IdeaProjects/hm-dianping/docs/m5b-bounded-cache-plan.md
3. /home/sd101t/IdeaProjects/hm-dianping/docs/m5a-observability-results.md
4. /home/sd101t/IdeaProjects/hm-dianping/docs/m5a-metric-catalog.md
5. /home/sd101t/IdeaProjects/hm-dianping/docs/blog-like-hot-rank.md

当前预期分支为 codex/platform-hardening，M5A 收口提交链末端为
5617826 docs: mark M5A complete and prioritize M5B。先用 git branch/log/status/diff
核对真实现场，保护已有改动；只实施 M5B，不开始 M5C/M5D/M6。

严格按 M5B 计划任务 0→7 执行。主线是：Boot 2.3 实际支持的 Redis command/pool
等待上界、商铺详情/类型字典 Redis 异常回 DB、每实例 per-key singleflight、短空值、坏值
修复和后台事务提交后精确失效。保持 cache key/payload 兼容，不使用布隆过滤器、逻辑过期、
Caffeine、Redis/Redisson 双重锁或请求线程池伪造 timeout。

真实测试只使用带 run-id 的隔离 MySQL/Redis/ES/RocketMQ。应用的专用 Broker 属性名必须是
ROCKETMQ_NAME_SERVER；禁止再次使用不存在的 LOCAL_DEALS_ROCKETMQ_NAME_SERVER。M5A 的
Broker unavailable 新秒杀准入继续保留 BLOCKED，本阶段不得补测或伪报闭合。

完成前通过第 9 节全部门禁，保存小型结果文档/CSV，原始 artifacts 留在已忽略目录。分小提交、
不 push；最后报告测试精确计数、故障期正确响应与时延、DB fallback、恢复时间、提交链、遗留
边界、精确清理和干净工作区。
```
