# M5D 指标与故障恢复收口计划

> HISTORICAL EXECUTION PLAN：本文保留阶段执行时的契约、状态与负面结果；最终结论以同目录 results 文档为准。

> 状态：已完成；正式结果见 `docs/evidence/m5/m5d-reliability-results.md`
>
> 起点：`5677047 docs(traffic): record M5C evidence and prioritize M5D`
>
> 范围：只收口路线图第 8.5 节指标、ES 消费失败重试和隔离故障证据；不进入 M6。

## 1. 停止线与证据规则

- Java 固定为 8；不升级 Spring Boot、MySQL、Redis、Elasticsearch 或 RocketMQ。
- 不调整 Hikari、Redisson、M5B cache key/payload/TTL、M5C semaphore/singleflight/错误码。
- 指标标签只取预注册有限枚举，禁止 userId、orderId、voucherId、IP、SQL、异常文本和动态 URI。
- 应用 Counter 不能替代 Broker lag；RocketMQ main/retry/DLQ 必须来自专用 Broker 管理面。
- `PROCESSING oldest age` 与 Outbox oldest age 必须区分 `0` 和采集失败 `NaN`。
- ES consumer 直接测试只称 consumer-level，不称 Canal E2E；没有可信事件时间时不伪造 ES lag。
- 故障场景必须同时记录 HTTP、Prometheus、Hikari、Outbox/PROCESSING oldest age、Broker
  main/retry/DLQ lag、恢复时间和业务不变量。采集不到的值写 `NA`/`BLOCKED`，不写假 0。
- F5 只有 run-id label、所有 loopback port、seckill/ES topic 与 group、MySQL/Redis/ES sentinel、
  两个应用的实际环境变量、两个应用 PID 到专用 NameServer/Broker 的 TCP 连接全部通过后才执行。
  任一项缺失则不停止 Broker、不发送请求，保留 `BLOCKED` 和 `0 requests`。

## 2. 路线图 8.5 指标覆盖矩阵

| 8.5 能力 | 当前来源 | 当前状态 | M5D 动作 / 最终证据 |
| --- | --- | --- | --- |
| 资源准入 allowed/rejected/unavailable | `local_deals.traffic.decision{resource,result,reason}` | 已覆盖，有限标签 | 故障轮采集 delta；不另建重复 `rate_limit_requests_total` |
| cache hit/miss/fallback/rebuild 与耗时 | cache access/singleflight/maintenance/db fallback；hot-rank read/rebuild/duration | 已覆盖 | Redis/MySQL 故障轮核对 Counter/Timer 与 HTTP |
| 秒杀 accepted/rollback | `local_deals.seckill.requests{result}`、DB order rollback Counter | 已覆盖 | consumer pause/MySQL/Broker 场景核对 delta 与库存/订单不变量 |
| 秒杀 DB persist duration | 无 | **缺失** | 新增 `local_deals.seckill.db.persist.duration{result=success\|failure}` Timer；只包实际 DB 调用 |
| consumer success/retry/DLQ | consumer outcome Counter；Broker 管理面 | 应用 outcome 已覆盖；retry/DLQ 不能由 Counter 推导 | 采集 main/retry/DLQ lag，consumer pause 后验证收敛 |
| PROCESSING oldest age/quarantine | due、oldest overdue、quarantine Gauge + collector Counter | 已覆盖 | Redis/MySQL/consumer pause 前中后采集，失败为 NaN |
| Outbox pending/oldest age/batch/apply/rollback | outbox Gauge、batch/duration/events/command | 已覆盖 | MySQL 故障前中后采集；collector failure 不伪造零 |
| 热榜 generation/age/fallback/singleflight/failure | rebuild/read/age/db fallback；generation 只在日志/metadata | 部分覆盖；generation 是动态值，不适合作标签 | 采集 age/fallback/failure；generation 保留在 metadata/日志，不新增高基数 Gauge 标签 |
| ES 同步失败与重放 | message/row/apply duration | 指标已有；失败却 ACK | 目标消息任一解析、转换、ES 写失败都抛出；DDL/无关表 ACK；固定 ID 重放幂等 |
| ES sync lag | 无可信事件时间 | **不可由应用构造** | 只记录 ES consumer group Broker main/retry/DLQ lag；事件 lag 保留 `NA` |
| DB pool wait | `hikaricp.connections.pending/active/idle/max/min` | Spring 自动覆盖 | 每个故障阶段抓取 Prometheus 快照 |
| 关键 SQL P95/P99 | cache DB fallback、hot-rank DB fallback、Outbox batch、秒杀 DB persist Timer | 秒杀 persist 缺失 | 补 Timer；Prometheus histogram 只报告实际系列，不把 Hikari wait 当 SQL latency |
| RocketMQ backlog | `mqadmin consumerProgress` | 外部来源 | seckill 与 ES group 分别记录 main/retry/DLQ；应用 Counter 不替代 |

## 3. 代码与配置切片

1. 在 `LocalDealsMetrics` 预注册秒杀 DB persist 的 `success|failure` Timer；consumer 对每次实际
   `createVoucherOrder` 调用只记录一个结果与耗时。
2. 将 ES sync listener 改为 `${local-deals.es-sync.topic:mysql-sync-topic}` 与
   `${local-deals.es-sync.consumer-group:es-sync-consumer-group}`，YAML 环境变量覆盖保留原默认值。
3. `EsSyncConsumer` 对无法解析的消息直接记录 failure 后抛出；目标消息逐行统计后，只要存在
   row conversion/ES apply failure 就记录 message failure/partial_failure 并抛出。DDL 与无关表
   继续 ignored ACK。重放仍使用业务主键作为 ES `_id`，upsert/delete 都是幂等目标状态操作。
4. 单元测试覆盖 parse、missing id、conversion、ES write、partial apply、DDL/无关表 ACK、重复
   INSERT/UPDATE/DELETE；真实 ES IT 保留 consumer-level 命名并验证重放最终状态。

## 4. run-id 隔离栈与故障矩阵

隔离栈独占 MySQL schema、Redis sentinel、ES cluster、RocketMQ cluster、seckill topic/group、
ES sync topic/group、两个应用/management 端口和全部容器 label。应用启动必须显式传入真实
`ROCKETMQ_NAME_SERVER`、两套 topic/group 环境变量，并保存 PID、`/proc/<pid>/environ` 摘要和
TCP 连接预检。

| 场景 | 注入 | 主要断言 |
| --- | --- | --- |
| F1 Redis | pause 专用 Redis | 认证/秒杀 fail closed；安全 DB read fallback；Gauge 为 NaN 而非假 0；恢复后会话/库存语义明确 |
| F2 MySQL | stop 专用 MySQL | DB_READ 只返回 429/503；Hikari pending 可见；Outbox collector failure；恢复后 backlog 与业务查询收敛 |
| F3 consumer pause | 先接受并确认 PROCESSING，再 pause 专用 seckill group | main/retry/DLQ lag、PROCESSING oldest age 增长；resume 后全为 0 且订单/库存/唯一性不变量通过 |
| F4 ES | pause 专用 ES 并向专用 ES sync topic 发送目标消息 | consumer 抛出触发 retry；ES group lag/失败指标可见；恢复后固定 ID 文档只存在一份并收敛 |
| F5 Broker | 仅在全部严格预检通过后 stop 专用 Broker | 新秒杀 100% 503；Redis/DB/visible message 无增量；否则 0 请求并 `BLOCKED` |

## 5. 验证与提交门禁

- Java 8 默认全量测试；ES/consumer/metric/秒杀风险定向测试；真实 Redis、MySQL、ES、RocketMQ IT。
- fresh Flyway V1-V8，并核对 8 条 migration；脚本 `bash -n`、Python CLI（如有）、CSV 列数、
  `git diff --check`。
- 分为代码/测试、隔离脚本、结果文档三个可回滚提交；每次只显式暂存意图文件并审查 staged diff。
- cleanup 只删除当前 run-id label 的容器和网络；不清理共享依赖，不删除 benchmark 原始证据；不 push。
