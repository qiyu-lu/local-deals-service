# M5A 指标基线与故障行为盘点实施清单

> 文档状态：已实施；F3 Broker 新准入补测按停止线记为 BLOCKED
>
> 记录日期：2026-08-20
>
> 仓库：`/home/sd101t/IdeaProjects/hm-dianping`
>
> 预期分支：`codex/platform-hardening`
>
> 起始 HEAD：`f79a738 docs: mark M4 complete and plan M5`

本文是 `docs/modernization-roadmap.md` 中 M5A 的任务级执行手册。M5A 只建立可观测契约、采集当前基线并记录故障行为，不实施 M5B 的缓存改造、M5C 的限流策略，也不开始 M6 的标签和发券功能。

实施结果已形成 `4bbc2ba test(observability): capture isolated M5A baseline evidence`。完整环境、三轮基线、故障矩阵、负面结果、隔离事故和后续优先级见 `docs/m5a-observability-results.md`，小型机器可读数据见 `docs/m5a-observability-summary.csv`。本文以下 checkbox 保留为执行前契约，不以批量勾选覆盖实际证据；最终状态以结果文档逐项说明为准。

## 1. 阶段目标

M5A 要回答四个问题：

1. 当前系统在正常流量下，HTTP、MySQL、Redis、RocketMQ、Elasticsearch 和后台任务分别处于什么水位；
2. 发生缓存 miss、热榜回库、Outbox 积压、秒杀 PROCESSING 积压和索引同步失败时，能否从有限、稳定的指标中识别；
3. Redis、MySQL、MQ consumer 和 Elasticsearch 分别不可用时，当前代码实际返回什么、保留什么状态、恢复后是否收敛；
4. 后续 M5B/M5C 应优先解决哪些已测问题，而不是凭经验堆缓存和限流组件。

M5A 的成果是“可信的当前事实”，不是性能优化结论。完成 M5A 不代表吞吐提高，也不能在简历中写“限流和降级已完成”。

## 2. 范围与非目标

### 2.1 本阶段包含

- 盘点和规范现有 Micrometer 指标；
- 为 M4 点赞/热榜、商铺缓存、认证入口和 ES 消费补充最小低基数指标；
- 采样 MySQL Outbox、Redis 秒杀状态等可积压数据；
- 明确 Actuator/Prometheus 的管理面网络和数据暴露边界；
- 建立可重复的正常流量基线脚本；
- 在完全隔离的依赖环境中执行有限故障注入；
- 保存小型、可审计的结果摘要；
- 根据证据形成 M5B/M5C 问题清单和优先级。

### 2.2 本阶段不包含

- 不实现令牌桶、动态规则平台、429 限流器；
- 不改变商铺缓存为逻辑过期或增加布隆过滤器；
- 不新增熔断框架、服务网格、APM Agent、Grafana 大盘或告警平台；
- 不修改秒杀、点赞和权限系统的数据真相边界；
- 不升级 Spring Boot、Java、RocketMQ、Redis 或 Elasticsearch；
- 不实施标签、每日任务、主动发券或自定义角色；
- 不对共享开发 MySQL、Redis、ES、RocketMQ 执行故障注入；
- 不根据单轮结果宣称性能提升。

## 3. 当前事实与已知缺口

开始实施前，执行者必须确认以下事实仍与源码一致：

- 项目使用 Spring Boot 2.3.12、Java 8、Actuator 和 Micrometer Prometheus Registry；
- `/actuator` 当前配置暴露 `health,info,metrics,prometheus`；
- nginx 的 `/api/` 会代理所有后端路径，没有单独拒绝 `/actuator`；
- 现有自定义指标主要集中在秒杀请求、MQ 消费、DB 冲突和 reconciliation；
- 点赞 Outbox、热榜命中/回退、商铺缓存、认证入口和 ES 消费尚无统一指标契约；
- Hikari、JVM、进程和 `http.server.requests` 可由 Actuator 自动提供，但必须核对当前版本实际导出的名称和标签；
- RocketMQ consumer backlog 不能从应用内成功/失败计数推断，必须使用 Broker 管理面或 exporter；
- Canal 消息模型当前没有已验证的事件时间字段，不能虚构“同步 lag”指标；
- M4 的 Outbox pending 索引为 `(processed_time,id)`，任何周期采样 SQL 都必须先做 `EXPLAIN` 并控制频率。

如果其中任何一项已经变化，先更新本文，再继续实施。

## 4. 核心原则

### 4.1 指标只能描述有限状态

允许作为标签的值必须来自固定枚举，例如：

- `resource=shop_detail|shop_type|blog_hot_rank`；
- `operation=like|unlike|insert|update|delete`；
- `result=success|failure|hit|miss|unavailable`；
- `dependency=mysql|redis|rocketmq|elasticsearch`。

禁止作为指标标签：

- userId、orderId、voucherId、blogId、shopId、merchantId；
- phone、username、token、IP、URL 查询参数；
- exception message、SQL、Redis Key、任意错误字符串；
- 原始 HTTP path 中的动态 ID。

单条订单排障使用结构化日志，聚合趋势使用指标，二者不能混用。

### 4.2 指标类型必须匹配语义

- 只增事件使用 Counter；
- 延迟和批处理耗时使用 Timer；
- 批量大小使用 DistributionSummary 或 Timer event count 的配套统计；
- pending 数、最老积压年龄和 quarantine 数使用 Gauge；
- 采集依赖失败时 Gauge 不能写成 0，应输出不可用状态或 `NaN`，并另有 collector 状态；
- Prometheus 比较必须使用时间窗增量，不能直接比较进程启动以来的累计值。

### 4.3 观测不能改变业务结果

- 埋点异常不得令订单、点赞或登录请求失败；
- 采样任务不得持有业务事务锁；
- 采样 SQL 必须命中索引、频率可配置，并与 worker 使用不同的短事务；
- 指标采集不执行补偿、缓存重建、消息重投或数据修复；
- 禁止为得到好看的指标改变默认开关或故障语义。

## 5. 交付物

M5A 完成时应存在以下产物：

1. `docs/m5a-metric-catalog.md`：指标名称、类型、标签、来源、解释和失败语义；
2. `scripts/run-m5a-baseline.sh`：固定正常/突发场景、采集前后快照并生成摘要；
3. `scripts/run-m5a-fault-inventory.sh`：只针对隔离依赖的故障盘点脚本；
4. `docs/m5a-observability-results.md`：三轮基线、故障矩阵和后续优先级；
5. 小型机器可读汇总 CSV；
6. 必要的 Micrometer 埋点、采样器和管理端点配置；
7. 单元测试、管理端点测试和隔离集成验证；
8. README 与路线图中的阶段状态更新。

大体积 JTL、Prometheus 全量快照、HTML 报告、日志和临时容器数据放在已忽略的 `benchmark/m5a/`，不得提交。

## 6. 任务 0：冻结起点

- [ ] 执行 `git branch --show-current`，确认分支；
- [ ] 执行 `git log -6 --oneline --decorate`，确认起始 HEAD；
- [ ] 执行 `git status --short`，要求工作区干净；
- [ ] 执行 `git diff --check`；
- [ ] 阅读 `docs/modernization-roadmap.md` 的 M5 和本文全部内容；
- [ ] 阅读 M4 两份运行手册，确认不能破坏点赞和秒杀不变量；
- [ ] 记录 Java、Maven、MySQL、Redis、RocketMQ、ES、JMeter 版本；
- [ ] 记录机器 CPU、内存、操作系统和是否存在其他高负载进程；
- [ ] 将 commit、配置开关和环境版本写入本轮 run metadata；
- [ ] 不读取、打印或提交 `.env` 中的密码。

退出条件：起点可复现、工作区干净、所有依赖目标明确为隔离环境。

## 7. 任务 1：建立指标目录和命名契约

### 7.1 先盘点，不先新增

- [ ] 列出现有全部 `local_deals.*` 指标及其注册位置；
- [ ] 启动隔离实例，抓取一次 `/actuator/prometheus`；
- [ ] 确认 Spring Boot 2.3 实际导出的 HTTP、Hikari、JVM、进程指标名称；
- [ ] 检查 `http.server.requests` 的 `uri` 是否为路由模板或有限的 `NOT_FOUND`，不得出现原始 ID；
- [ ] 标出重名、重复注册、语义不清和未提供 description/base unit 的指标；
- [ ] 保留已提交秒杀指标名称，除非存在错误；如需更名必须提供兼容期和映射说明。

### 7.2 最小指标集合

以下是契约目标，最终名称以 `docs/m5a-metric-catalog.md` 为准，不能在不同类中自由发明同义标签。

#### 已有秒杀指标

- `local_deals.seckill.requests{result=...}`：准入结果；
- `local_deals.seckill.mq.consume{result=...}`：consumer 结果；
- `local_deals.seckill.db.orders{result=...}`：DB 幂等/库存结果；
- `local_deals.seckill.reconciliation{result=...}`：对账分类。

检查项：

- [ ] 每条业务分支只递增一次；
- [ ] malformed、ownership mismatch、DB 冲突和通知失败的统计语义明确；
- [ ] `failure` 不把暂态、永久、隔离混成无法解释的一个数；
- [ ] 不为了拆分语义引入动态异常类标签。

#### 点赞和 Outbox

- `local_deals.blog.like.command{operation,result}`：like/unlike 的 changed、unchanged、not_found、failure；
- `local_deals.blog.like.outbox.batch{result}`：success、empty、lock_busy、db_error、redis_lock_error；
- `local_deals.blog.like.outbox.duration`：一次 batch 的耗时；
- `local_deals.blog.like.outbox.events`：每批处理事件数；
- `local_deals.blog.like.outbox.pending`：待处理事件数；
- `local_deals.blog.like.outbox.oldest_age`：最老 pending 年龄，单位 seconds；
- `local_deals.blog.like.outbox.collector{result}`：采样成功/失败。

检查项：

- [ ] NOOP 点赞不计为 changed；
- [ ] DB 事务回滚不能计为成功处理；
- [ ] batch 成功指标必须在事务提交确定后记录，不能在提交前递增；
- [ ] pending 采样使用索引并限制为 15～30 秒一次；
- [ ] 采样失败不把 pending 和 age 写成 0；
- [ ] 关闭 worker 时仍可观察 backlog，但采样器本身可独立关闭。

#### 热榜

- `local_deals.blog.hot_rank.read{result}`：hit 及每个有限 miss reason；
- `local_deals.blog.hot_rank.rebuild{result}`：published、lock_busy、stale_generation、failed；
- `local_deals.blog.hot_rank.rebuild.duration`：完整构建耗时；
- `local_deals.blog.hot_rank.db_fallback`：回库查询耗时；
- `local_deals.blog.hot_rank.age`：已发布榜单年龄；
- `local_deals.blog.hot_rank.collector{result}`：metadata 采样状态。

检查项：

- [ ] 坏 metadata、ZCARD 不一致、过期、越界和 Redis unavailable 分开；
- [ ] 当前请求只统计一次 hit 或 miss；
- [ ] rebuild 返回旧 generation 时不能计为 failure；
- [ ] DB fallback Timer 只包住榜单 SQL，不混入前端序列化；
- [ ] metadata 缺失时 age 不输出 0。

#### 商铺缓存

- `local_deals.cache.access{resource,result}`，第一阶段 resource 仅允许 `shop_detail|shop_type`；
- `local_deals.cache.db_fallback{resource}`：缓存 miss 后的 DB 查询耗时；
- 允许结果：hit、empty_hit、miss、redis_error、db_success、db_empty、db_error。

检查项：

- [ ] 先记录当前 pass-through 行为，不在 M5A 改为逻辑过期；
- [ ] 区分空值命中和普通 miss；
- [ ] Redis error 与合法 miss 分开；
- [ ] 指标不记录 shopId 和缓存 Key；
- [ ] 指标本身不触发额外缓存读取。

#### 认证入口

- `local_deals.auth.request{flow,result}`；
- flow 仅允许 otp_send、user_login、admin_login；
- result 使用 success、invalid_input、rejected、locked、unavailable 等有限枚举。

检查项：

- [ ] 不记录手机号、用户名、IP、验证码或 token；
- [ ] 不因指标暴露不同的“账号不存在/密码错误”结果；
- [ ] 后台登录的 BCrypt 前 IP 门禁与身份失败桶分别可解释；
- [ ] 本阶段不改变任何阈值。

#### Elasticsearch 同步

- `local_deals.es.sync.messages{table,operation,result}`；
- `local_deals.es.sync.rows{table,operation,result}`；
- `local_deals.es.sync.apply.duration{table,operation}`。

检查项：

- [ ] table 只允许 shop、blog、ignored；
- [ ] operation 只允许 insert、update、delete、other；
- [ ] 解析失败、忽略 DDL、坏行、ES 写失败和成功分开；
- [ ] 当前 Canal DTO 没有验证过事件时间，不添加虚假的 lag 指标；
- [ ] Broker backlog 使用 RocketMQ 管理面采集，不从 messages counter 推算。

#### 秒杀积压采样

- `local_deals.seckill.processing.due`：当前已到期的预约数；
- `local_deals.seckill.processing.oldest_overdue`：最老已到期预约超时秒数；
- `local_deals.seckill.processing.quarantine`：隔离成员数；
- `local_deals.seckill.processing.collector{result}`：Redis 采样状态。

检查项：

- [ ] 使用 Redis TIME 或单个只读 Lua 获得一致快照；
- [ ] 不扫描全部 status Hash；
- [ ] 采样脚本不 ZPOP、不修改 score、不续期、不补偿；
- [ ] Redis 故障时不报告“积压为 0”。

### 7.3 指标目录必须回答

每项指标文档至少包含：

- Micrometer 名称和 Prometheus 导出名称；
- 类型、单位和 description；
- 所有允许标签和值域；
- 在哪一个提交点记录；
- 失败/重试/重复执行时如何计数；
- 如何从 counter 计算 rate；
- 是否适合告警，M5A 暂不配置阈值；
- 数据缺失与真实 0 如何区分。

退出条件：指标目录先通过审查，再开始埋点。不得边写代码边增加无文档指标。

建议 Git 节点：

```text
docs(observability): define M5A metric and fault contracts
```

## 8. 任务 2：收紧管理端点边界

当前 nginx `/api/` 会代理任意后端路径，业务 token 也不应成为 Prometheus 凭据。M5A 必须先定义独立管理面。

推荐契约：

- 应用管理端点使用独立、可配置的 management port；
- 本地默认只绑定 loopback；容器化生产由内部监控网络访问，不映射到公网；
- nginx 显式拒绝 `/api/actuator`，不能依赖业务 LoginInterceptor；
- 只暴露 `health` 和 `prometheus`；
- 不暴露 `env`、`configprops`、`beans`、`mappings`、`loggers`、`heapdump`、`threaddump`；
- health 不展示连接串、主机名、索引名或异常堆栈；
- Prometheus 指标中不得出现 token、手机号、用户名、SQL 参数和上传路径。

实施清单：

- [ ] 从 exposure 中移除 `info` 和 `metrics`，除非写出必须保留的理由；
- [ ] 增加可配置 management port/address，安全默认不对外；
- [ ] nginx 为 `/api/actuator` 增加优先级高于 `/api/` 的拒绝规则；
- [ ] 记录生产 Prometheus 应从哪个受信网络抓取；
- [ ] 区分 liveness 与依赖健康，不能因为 ES 可选读模型故障就让进程无限重启；
- [ ] 验证匿名公网路径无法访问 Prometheus；
- [ ] 验证管理网络可以抓取 health/prometheus；
- [ ] 验证普通消费者和后台 token 不能经 nginx 获取管理数据；
- [ ] 验证 health 响应不泄露依赖细节；
- [ ] 增加真实 Spring Context 测试，而不是只检查 YAML 字符串。

如果当前部署拓扑无法使用独立端口，必须在实施前选择“受信代理认证 + 网络 allowlist”的替代方案并写明威胁边界，不能默认把 `/actuator/prometheus` 暴露给所有登录用户。

## 9. 任务 3：实现最小埋点与低频采样

### 9.1 编码边界

- [ ] 新增统一 observability 配置，采样周期有下限且可关闭；
- [ ] 复用一个 MeterRegistry，不创建静态全局 registry；
- [ ] 使用构造器注入，测试可传入 `SimpleMeterRegistry`；
- [ ] 指标注册发生一次，禁止每请求动态创建带新标签的 meter；
- [ ] 对有限枚举在启动时预注册，避免第一次故障前指标不存在；
- [ ] Timer 开启适合 Prometheus 聚合的 histogram，仅覆盖关键 Timer；
- [ ] 不在所有 SQL 或 Redis 命令上加 AOP；只观测业务边界；
- [ ] 指标记录代码不得吞掉原业务异常，也不得改变返回值；
- [ ] 新采样调度器在 test profile 默认关闭；
- [ ] Spring 5.2 的 `@Scheduled` Duration 使用 SpEL 转毫秒，并做完整 Context 启动测试。

### 9.2 Outbox 采样

- [ ] 使用 `(processed_time,id)` 索引获取 pending count 和最早 pending ID；
- [ ] 再按主键读取对应 create_time，避免无索引 `MIN(create_time)`；
- [ ] 对 SQL 执行 `EXPLAIN`，证据写入结果文档；
- [ ] 默认 30 秒采样一次，最短配置下限不得造成高频全索引扫描；
- [ ] DB 异常设置 collector failure，积压 Gauge 进入 unavailable，不写 0；
- [ ] 测试 pending=0、pending>0、数据库异常、恢复四种状态。

### 9.3 秒杀状态采样

- [ ] 用只读 Lua 一次返回 Redis TIME、due count、最老 due score 和 quarantine count；
- [ ] 空集合返回合法 0；
- [ ] wrong type、Redis timeout 和返回畸形均记 collector failure；
- [ ] 脚本执行前后 Redis 数据完全一致；
- [ ] 在隔离 Redis 中做真实 Lua 测试。

### 9.4 热榜和缓存埋点

- [ ] 直接复用已有 `MissReason` 和 `RebuildOutcome` 枚举映射有限标签；
- [ ] hit/miss 在 `BlogHotRankService` 返回点记录，DB fallback 在调用方记录；
- [ ] 商铺 cache-through 先补可区分的返回结果，不改变现有缓存算法；
- [ ] Redis 异常仍按原契约处理，M5A 只记录；
- [ ] 加载作者、点赞批量回填不重复计为热榜 DB fallback；
- [ ] 单测覆盖每个 reason，验证一次请求只增加一个结果。

### 9.5 ES 和认证埋点

- [ ] ES consumer 逐消息和逐行语义分开；
- [ ] 单行失败不能把整条消息记为完全成功；
- [ ] 当前 consumer 对部分坏行只是日志并 ACK，指标必须能显示 partial failure；
- [ ] 登录失败指标不破坏统一错误文案；
- [ ] Redis 限流脚本失败与正常拒绝分开，但本阶段不改变 fail-closed 行为。

### 9.6 Meter 单元测试

- [ ] 每个结果分支验证 meter 增量；
- [ ] 重试和幂等分支验证不会重复记录成功；
- [ ] 事务提交前异常不记录 Outbox batch success；
- [ ] 标签集合和数量受控；
- [ ] 构造包含长 ID、手机号和异常文本的请求，确认 meter IDs 不包含这些值；
- [ ] collector 失败后恢复，Gauge 和 collector 状态恢复正常；
- [ ] 多线程调用不会重复注册或抛出 meter 冲突。

建议 Git 节点：

```text
feat(observability): instrument critical reliability paths
```

## 10. 任务 4：建立隔离观测环境

### 10.1 隔离要求

故障注入必须使用：

- 独立 MySQL schema；
- 独立 Redis 实例，而不是共享实例的另一个 DB；
- 独立 Elasticsearch 容器或独立测试集群；
- 独立 RocketMQ NameServer/Broker；
- 独立应用端口和 management port；
- 独立 topic/group 或完全独占的 Broker；
- 临时目录保存 JTL、日志和指标快照。

禁止：

- 停止 `local-deals-mysql`、`local-deals-redis` 等共享开发容器；
- 对共享 RocketMQ group 重置 offset；
- 删除共享 ES 的 `shop_index`、`blog_index`；
- 使用 `KEYS *`、全库清理或 broad wildcard 删除；
- 输出 `.env`、Redis AUTH、数据库密码或后台 bootstrap 密码；
- 让测试脚本在目标环境不明确时自动选择默认 localhost。

### 10.2 环境门禁

- [ ] 脚本要求显式 `M5A_ISOLATED=true`；
- [ ] MySQL schema 名必须含本轮 run ID，且不允许 `local_deals`、`hmdp`；
- [ ] Redis 实例写入随机哨兵 key，再验证 DB/端口属于本轮容器；
- [ ] RocketMQ Broker name/topic/group 包含测试前缀或由本轮独占；
- [ ] ES cluster name/index 前缀经验证后才允许删除；
- [ ] 应用启动日志记录依赖地址但隐藏密码；
- [ ] cleanup 使用显式容器、schema、index 名；
- [ ] 删除前再次验证 run ID；
- [ ] cleanup 失败只报告残留，不扩大删除范围；
- [ ] 结果文档记录哪些临时数据已删除且不可恢复。

如无法获得独立 RocketMQ/ES，不得把对应故障场景标为通过；允许记录为 BLOCKED，但其他依赖的基线仍可继续。

## 11. 任务 5：固定正常流量基线

### 11.1 通用运行协议

- [ ] 每个场景先预热，再进行 3 轮正式采样；
- [ ] 三轮使用相同 commit、JVM、数据规模和配置；
- [ ] 每轮前重置目标数据，但不重启依赖以伪造冷/热状态；
- [ ] 冷缓存和热缓存作为两个不同场景；
- [ ] 记录 JMeter samples、error%、throughput、P50/P95/P99；
- [ ] 记录应用 CPU、RSS、GC、线程和 Hikari active/pending；
- [ ] 记录 MySQL Questions/Com_select/Com_insert/Com_update/Com_delete 增量；
- [ ] 记录 Redis commandstats/keyspace hit/miss 增量；
- [ ] 使用 RocketMQ 管理面记录 consumer diff/lag；
- [ ] 记录 ES 节点请求/失败统计；
- [ ] 指标使用运行前后 delta，不能比较累计绝对值；
- [ ] 每轮结束验证业务不变量后才进入下一轮；
- [ ] 结果取三轮中位数，同时保留最差 P99，不只挑最好一轮。

### 11.2 B0：空闲基线

持续 5 分钟无业务流量：

- [ ] 记录后台调度频率和空批次数；
- [ ] 确认 Outbox、热榜 refresh、reconciliation 的开关状态；
- [ ] 检查无请求时是否出现 DB/Redis 高频轮询；
- [ ] 检查日志是否重复刷屏；
- [ ] 记录进程 CPU、内存、线程和连接池稳定水位。

### 11.3 B1：商铺详情与字典读取

分别执行冷缓存、热缓存两组：

- [ ] 固定存在 ID、合法不存在 ID 和类型字典请求比例；
- [ ] pilot 使用 100 请求，正式场景使用 1000 请求；
- [ ] 记录 cache hit、empty hit、miss、Redis error、DB fallback；
- [ ] 对比 MySQL 查询增量与 HTTP 样本数；
- [ ] 确认不存在 ID 的短空值不会被解释为系统错误；
- [ ] 不在 M5A 修复击穿，只记录同 key 冷 miss 下的 DB 放大量。

### 11.4 B2：热榜和社区互动

- [ ] 分别在 Redis 榜单 ready、not ready、stale 三种状态运行读取；
- [ ] 记录 hit、各 miss reason 和 DB fallback latency；
- [ ] 以小规模用户执行显式 PUT/DELETE，记录 changed/unchanged；
- [ ] 开启 Outbox worker 后记录 batch rate、pending 和 oldest age 回落；
- [ ] 验证 `liked = legacy_offset + COUNT(relation)`；
- [ ] 验证列表批量回填没有回归 N+1；
- [ ] 不执行 M4 的破坏性 legacy backfill。

### 11.5 B3：搜索与索引消费

- [ ] 固定关键词、类型、坐标和半径；
- [ ] 记录 ES 查询成功率和 HTTP P95/P99；
- [ ] 在隔离环境写入一条 shop/blog 变更；
- [ ] 若完整 Canal 链路可用，记录从提交到 ES 可见的外部测量时间；
- [ ] 若只能直接调用 consumer，明确标记为 consumer-level，不冒充 Canal E2E；
- [ ] 核对 MySQL/ES 样本 ID 与字段，不用“消息计数相同”替代数据一致。

### 11.6 B4：秒杀正常链路

优先复用现有 benchmark，第一组固定为：1000 请求、100 库存、1000 用户、单次循环。

- [ ] 预先创建 Topic，避免冷 Topic 自动创建竞态；
- [ ] 记录 accepted、stock rejected、duplicate/activity/unavailable；
- [ ] 等待所有 accepted 进入 SUCCESS 或明确终态；
- [ ] 记录 HTTP P95/P99、MQ consumer lag、PROCESSING oldest age；
- [ ] 核对 MySQL 订单数、DB/Redis 库存、重复订单、reservation、processing index、DLQ；
- [ ] 要求订单数等于预期、重复为 0、库存精确一致；
- [ ] 不用库存耗尽后的快速拒绝吞吐代表完整下单吞吐。

### 11.7 基线结果格式

每轮摘要至少包含：

```text
run_id, commit, scenario, warm_or_cold, samples, errors,
throughput, p50_ms, p95_ms, p99_ms,
mysql_queries_delta, redis_commands_delta,
cache_hits, cache_misses, db_fallbacks,
mq_lag_max, backlog_oldest_seconds,
invariant_result, notes
```

没有可靠采集来源的字段留空并解释，禁止填 0。

## 12. 任务 6：故障行为盘点

### 12.1 统一协议

每个故障场景分为：

1. 正常运行 30 秒，记录起始水位；
2. 注入故障 60 秒，维持固定小流量；
3. 恢复依赖，停止新增流量；
4. 等待 backlog 收敛或达到 5 分钟上限；
5. 验证业务不变量并清理本轮数据。

故障盘点只记录当前行为。发现可用性不足时创建 M5B/M5C 项，不在同一提交顺手改变降级逻辑。

### 12.2 F1：Redis 不可用

观察：

- [ ] 商铺详情冷/热请求的响应和 DB 放大；
- [ ] 热榜是否按设计回 DB，是否把 Redis 错误解释为空榜；
- [ ] 点赞命令是否仍以 MySQL 关系为准；
- [ ] Outbox worker 在 Redisson 不可用时是否仍由 DB 锁收敛；
- [ ] 用户/后台会话验证是否默认拒绝；
- [ ] 秒杀新准入是否默认拒绝且未生成孤立预约；
- [ ] collector 是否显示 unavailable 而非 0；
- [ ] Redis 恢复后的缓存/状态是否需要人工动作。

安全不变量：不能越权放行、不能绕过秒杀资格、不能把坏榜当空榜、不能产生重复点赞关系。

### 12.3 F2：MySQL 不可用

观察：

- [ ] Redis 命中的商铺详情是否仍可读，冷 miss 如何失败；
- [ ] 热榜 Redis 命中后批量 hydrate 对 DB 的依赖；
- [ ] 点赞写和 Outbox worker 是否保留可重试事实；
- [ ] MQ consumer 是否重试且不确认未落库订单；
- [ ] Hikari pending、timeout、HTTP 503/500 当前语义；
- [ ] DB 恢复后 Outbox/MQ 是否收敛；
- [ ] collector 失败是否不伪造 backlog=0。

安全不变量：不能显示未提交写入成功；MQ 消息不能在 DB 失败时被错误确认；恢复后不能重复应用事件。

### 12.4 F3：MQ consumer 暂停与 Broker 不可用

分别盘点 consumer pause 和 Broker unavailable：

- [ ] consumer 暂停时已接受预约保持 PROCESSING；
- [ ] backlog 和 oldest age 可从 Broker/Redis 被观察；
- [ ] 恢复 consumer 后订单收敛且无重复；
- [ ] Broker 不可用时新秒杀请求的返回语义；
- [ ] Broker 发送失败时 Redis 是否产生孤立预约；
- [ ] 半消息回查与 DLQ 证据可区分；
- [ ] 点赞 Outbox 不依赖 MQ，行为不应被错误影响。

安全不变量：Broker 不可用不能绕过事务消息直接预约；consumer 重放不能重复下单。

### 12.5 F4：Elasticsearch 不可用

- [ ] 搜索接口当前 HTTP 状态和耗时；
- [ ] Canal/RocketMQ consumer 的重试或 ACK 行为；
- [ ] `local_deals.es.sync.*` 是否记录失败/部分失败；
- [ ] ES 恢复后是否自动补齐，还是需要重建索引；
- [ ] 商铺详情、秒杀和点赞主链路不应因 ES 故障失败；
- [ ] 禁止临时改成无界 MySQL `%LIKE%` 以让测试变绿。

该场景很可能形成 M5C 的搜索降级任务；M5A 只保存事实。

### 12.6 停止条件

出现以下任一情况立即停止新流量：

- 超卖、重复订单、重复发放或点赞恒等式破坏；
- 越权请求成功；
- 测试目标指向共享数据；
- backlog 持续增长超过预设测试上限；
- 恢复后 5 分钟仍无收敛趋势；
- cleanup 需要扩大到无法精确验证的目标。

P0 数据安全或授权问题必须先修复并重新建立基线。普通可用性问题进入 M5B/M5C，不阻止保存负面基线。

## 13. 任务 7：分析与决策

结果文档必须分别回答：

### 13.1 缓存是否需要改造

- 同一热 shop 冷 miss 的 DB 放大量是多少；
- 空值缓存是否已足够应对不存在 ID；
- Redis 故障时 DB QPS 是否超过目标水位；
- 是否有证据支持 singleflight/逻辑过期；
- 是否真的存在需要布隆过滤器的穿透流量。

没有穿透证据则明确 M5B 不实现布隆过滤器。

### 13.2 哪些入口需要限流

- 各入口突发 P99、Hikari pending 和拒绝语义；
- 秒杀准入与已接受订单消费是否竞争资源；
- OTP/admin login 已有门禁是否足够；
- 读接口是否需要本地并发上限；
- Redis 故障时哪些入口必须 fail closed。

M5A 只给出资源、维度、目标水位和失败语义，不实现算法。

### 13.3 降级优先级

按当前证据列出：

1. 可暂停且不丢事实的后台任务；
2. 可返回有界旧值的读请求；
3. 必须 fail closed 的写请求；
4. 已接受后必须优先排空的任务。

### 13.4 不能声称的结论

- 单轮吞吐不能称为容量；
- 应用 counter 不能称为 MQ backlog；
- consumer 单测不能称为 Canal E2E；
- 依赖恢复后进程存活不能称为数据已恢复；
- 低流量下没有错误不能称为高可用；
- M5A 完成不能称为动态限流或自动降级已经实现。

## 14. 测试清单

### 14.1 单元与契约测试

- [ ] 所有 metric outcome 分支；
- [ ] 标签白名单和高基数拒绝；
- [ ] Timer/Counter 在异常和幂等路径的记录时点；
- [ ] Outbox collector 的空、积压、DB 错误、恢复；
- [ ] Redis 只读采样 Lua 的空、due、quarantine、wrong type、Redis error；
- [ ] 热榜每个 MissReason/RebuildOutcome；
- [ ] ES 消息成功、部分坏行、全失败、ignored；
- [ ] auth 指标不改变统一错误行为；
- [ ] Duration 配置边界和调度器完整 Context 启动。

### 14.2 管理面测试

- [ ] public nginx `/api/actuator/prometheus` 被拒绝；
- [ ] management network health/prometheus 可访问；
- [ ] 未暴露 info/metrics/env/configprops/loggers/heapdump；
- [ ] health 无敏感细节；
- [ ] Prometheus 文本不存在 token、手机号、username、动态 ID 标签；
- [ ] 默认业务端口不暴露 management endpoint。

### 14.3 回归测试

- [ ] Java 8 compile/testCompile；
- [ ] 默认单元测试全部通过；
- [ ] M4 点赞/热榜风险相关测试通过；
- [ ] 秒杀 producer/consumer/reconciliation 风险相关测试通过；
- [ ] Admin auth/RBAC 测试通过；
- [ ] ES consumer 测试通过；
- [ ] 脚本执行 `bash -n`；
- [ ] `git diff --check`。

### 14.4 隔离集成验证

- [ ] 真实 MySQL 验证 backlog 采样 SQL 和索引计划；
- [ ] 真实 Redis 验证只读 Lua 零副作用；
- [ ] 真实 Spring Context 验证 Actuator 端口和调度配置；
- [ ] 独立 Broker 验证 consumer lag 采集与 pause/resume；
- [ ] 独立 ES 验证成功/失败指标；
- [ ] 每种依赖 cleanup 后无测试残留。

## 15. 验收门禁

M5A 只有同时满足以下条件才完成：

- [ ] 指标目录覆盖所有新增和既有 `local_deals.*` 指标；
- [ ] 没有高基数或敏感标签；
- [ ] 管理端点不经公网业务入口暴露；
- [ ] 指标记录不会改变业务结果；
- [ ] backlog collector 失败不会报告 0；
- [ ] Outbox 采样 SQL 有索引计划证据；
- [ ] Redis 采样 Lua 有真实 Redis 零副作用证据；
- [ ] B0～B4 每项至少完成 pilot，正式纳入比较的场景完成 3 轮；
- [ ] F1～F4 在隔离环境执行，无法执行的场景明确标为 BLOCKED；
- [ ] 每个故障场景同时记录 HTTP、指标、数据不变量和恢复时间；
- [ ] 没有超卖、重复订单、越权或点赞恒等式破坏；
- [ ] 所有代码和脚本测试通过；
- [ ] README 不声称 M5B/M5C 已完成；
- [ ] 结果文档列出 M5B/M5C 的证据化优先级；
- [ ] staged diff 无凭据、原始大文件和非本阶段改动；
- [ ] 本地提交后工作区干净，未经授权不 push。

建议最终 Git 节点：

```text
test(observability): capture isolated M5A baseline evidence
docs: mark M5A complete and prioritize M5B
```

## 16. 阶段停止线

- 指标目录未定稿前，不开始批量埋点；
- 管理端点仍可能公网暴露时，不采集包含业务指标的正式基线；
- 隔离环境未验证时，不执行任何 stop/kill/delete 故障命令；
- 发现 P0 数据或授权问题时，停止基线并单独修复；
- 普通负面结果要保留，不为让图表好看而在 M5A 顺手优化；
- 没有真实 MQ 管理面数据时，不填写 backlog=0；
- 没有完整 Canal 链路时，不声称同步 E2E 已验证；
- M5A 未提交且工作区未干净时，不开始 M5B。

## 17. 新会话执行提示词

```text
请先阅读：
1. /home/sd101t/IdeaProjects/hm-dianping/docs/modernization-roadmap.md
2. /home/sd101t/IdeaProjects/hm-dianping/docs/m5a-observability-baseline-plan.md
3. /home/sd101t/IdeaProjects/hm-dianping/docs/blog-like-hot-rank.md
4. /home/sd101t/IdeaProjects/hm-dianping/docs/seckill-reconciliation.md

当前预期分支为 codex/platform-hardening，起始 HEAD 为 f79a738。先核对
git branch/log/status/diff，保护已有历史；严格只执行 M5A，不实施 M5B 缓存改造、
M5C 限流或 M6 业务功能。

按任务 0→7 顺序执行：先定稿低基数指标目录和管理端点边界，再做最小埋点及采样，
之后在独立 MySQL、Redis、RocketMQ、Elasticsearch 中运行 B0-B4 基线与 F1-F4 故障盘点。
禁止停止或清理共享开发依赖；MQ backlog 必须来自 Broker 管理面，Canal consumer 测试不得
冒充完整 E2E。故障盘点只记录当前行为，普通可用性缺口进入 M5B/M5C，不在 M5A 顺手优化。

完成后按第 15 节验收：运行 Java 8 全部测试、相关真实依赖测试、bash -n、git diff --check；
保存三轮基线、故障矩阵、业务不变量和恢复时间。显式暂存本阶段文件，分小提交记录，
不 push。最后报告 commit、测试计数、隔离环境、负面结果、BLOCKED 项和干净工作区状态。
```
