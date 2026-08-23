# M5A 可观测基线与故障行为结果

> 状态：M5A 已执行，M5B/M5C 未实施
>
> 正式 run：`20260820m5a`
>
> 测量源码：`10bfb64 test(observability): add isolated M5A runners`
>
> 日期：2026-08-20（Asia/Shanghai）

本结果只描述当前实现。它不证明系统容量提升，也不代表有界缓存、429 限流或自动降级已经完成。小型机器可读结果见 `docs/evidence/m5/m5a-observability-summary.csv`；JTL、Prometheus 快照、日志和容器数据保留在已忽略的 `benchmark/m5a/20260820m5a/`，不进入 Git。

## 1. 隔离边界与环境

- 分支为 `codex/platform-hardening`，起始 HEAD 为 `f79a738`；正式测量时 HEAD 为 `10bfb64`。
- 应用使用 Dragonwell Java `1.8.0_472`；Maven 3.9.9；JMeter 5.6.3。
- 专用依赖为 MySQL 8.0.45、Redis 6.2.21、Elasticsearch 7.17.18 + IK、RocketMQ 4.9.4。
- 主机为 Ubuntu 20.04.6、Linux 5.15.0-134、AMD Ryzen 7 7700、约 31 GiB 内存。
- 专用 schema 为 `m5a_20260820m5a`；专用容器和网络都带 `com.localdeals.m5a.run-id=20260820m5a` 标签，并只映射 loopback 端口。
- 应用业务端口为 18083，management 端口为 `127.0.0.1:18084`。共享 `local-deals-*` 和其他项目容器未停止、未清理。
- M4 点赞 write/worker、热榜 read/refresh 在专用 cutover marker 下开启；可观测采样间隔 30 秒；秒杀自动补偿仍关闭。

正式基线开始时主机 load average 约 3，存在本机其他负载。因此本次数据只用于同一 run 内的行为盘点，不能外推生产容量。

## 2. 指标与管理面契约

指标目录见 `docs/design/observability-metrics.md`。实际 Prometheus 文本确认存在：

- `http_server_requests_seconds_*`、`hikaricp_connections_active`、`jvm_memory_used_bytes`、`process_cpu_usage`；
- 点赞命令、Outbox batch/duration/events/pending/oldest age/collector；
- 热榜 read/rebuild/duration/fallback/age/collector；
- 商铺缓存 access/fallback；
- 认证入口；
- ES message/row/batch；
- 秒杀细分 consumer outcome、PROCESSING/quarantine collector 和既有兼容指标。

所有业务标签来自预注册的固定枚举，没有 userId、orderId、手机号、token、异常消息、SQL 或 Redis key。management 只暴露 health 和 Prometheus，health 不显示详情；业务端口不暴露 Prometheus，nginx 对 `/api/actuator` 返回 404。

真实 MySQL 测试在插入 pending 行后确认 Outbox 采样使用 `idx_blog_like_outbox_pending`，访问类型为 `ref`，`Extra=Using where; Using index`。空表的 `MIN` 会被 MySQL 优化为 `No matching min/max row`，不能用空表 EXPLAIN 证明索引命中。

真实 Redis 测试确认 backlog Lua 在空、due/quarantine 和 wrong-type 场景只读；采样前后 key 类型、TTL、成员和值均不变。wrong type/连接错误输出不可用值，不伪造为 0。

## 3. B0–B4 基线

### 3.1 B0 后台水位

三轮各 300 秒，无 HTTP 流量，业务不变量均为 pass：

| 轮次 | MySQL 命令增量 | Redis 命令增量 |
| --- | ---: | ---: |
| 1 | 3295 | 15373 |
| 2 | 3299 | 15392 |
| 3 | 3299 | 15392 |

该水位包含 health、30 秒 collector 和热榜刷新，不是零活动。首版 runner 把 `process_cpu_usage` 四舍五入为整数，三轮都记录成 0；这些 CPU 数值无效，脚本已改为保留浮点值，但没有为了修饰结果重跑 15 分钟 B0。

### 3.2 B1 商铺缓存

20 线程、每轮 1000 请求的三轮结果：

| 状态 | 吞吐中位数 req/s | P95 | P99 中位数 | hit/miss/fallback（每轮） |
| --- | ---: | ---: | ---: | --- |
| cold | 1082.251 | 1 ms | 2 ms | 998 / 2 / 2 |
| warm | 1077.586 | 1 ms | 1 ms | 1000 / 0 / 0 |

100 请求 pilot 中，存在商铺为 98 hit/2 miss/2 DB fallback；不存在 ID 为 1 miss/1 DB fallback，后续由短空值吸收；shop type 为 99 hit/1 miss/1 fallback。当前低并发证据不支持引入布隆过滤器。冷启动出现 2 次并发回库，说明 singleflight 可以作为 M5B 的有界候选，但当前放大量很小，不能声称已构成瓶颈。

### 3.3 B2 热榜与点赞

首次 stale pilot 因 runner 写错 metadata 字段而实际 100 次全 hit，该结果判无效并保留。修正为 `publishedAt` 后只重跑 B2：not-ready 窗口记录 `not_ready=1, hit=99`；ready 为 `hit=100`；stale 为 `stale=1, hit=99`。后台 singleflight 很快重建榜单，因此同一批其余请求命中新榜。

点赞三步的指标分别为 `like changed=1`、重复 PUT 的 `like unchanged=1`、`unlike changed=1`；最终点赞恒等式为 pass。该结果验证幂等计数语义，不是吞吐测试。

### 3.4 B3 搜索与 ES consumer

- shop search：100 请求、0 JMeter HTTP error、109.769 req/s、P95 9 ms、P99 106 ms；
- blog search：100 请求、0 JMeter HTTP error、110.497 req/s、P95 6 ms、P99 52 ms。

真实 ES 验证是直接调用 `EsSyncConsumer.onMessage` 的 consumer-level INSERT/UPDATE/DELETE 测试，3 个测试通过。没有启动 Canal Server 或验证 binlog 到 MQ 的完整路径，因此不能称为 Canal E2E，也没有虚构事件 lag。

### 3.5 B4 秒杀正确性 pilot

1000 线程、100 库存：1000 样本、0 JMeter HTTP error、210.040 req/s、P95 12 ms、P99 115 ms；drain 675 ms。最终 MySQL 订单 100、DB 库存 0、重复订单 0；Redis 库存 0、reservation 100、SUCCESS 100、非成功 0、processing index 0，正确性 pass。

这是单轮 pilot，只能证明该流量下的不变量，不能称为容量或性能提升。

## 4. F1–F4 故障矩阵

| 场景 | 故障期事实 | 恢复事实 | 结论 |
| --- | --- | --- | --- |
| F1 Redis | 正常 29/29 2xx；停止后 20 次商铺请求均超过 2 秒客户端上限，无 HTTP 响应；collector 为空而非 0 | Redis 2 秒可连接，随后 3/3 2xx；数据不变量 pass | 当前读取没有有界 Redis-error fallback；隔离 Redis 重启后 benchmark 会话和 sentinel 丢失，认证因无会话而 401/fail closed，需要重新建会话。数据丢失程度受本轮无持久化容器配置限制，不能外推生产 |
| F2 MySQL | 正常 30/30 2xx；冷 miss 在故障期 20 次均超过 2 秒；DB 不变量只能记 unavailable | MySQL 2 秒可连接；首个 5 秒窗口 2 次 2xx、1 次仍超时；随后不变量 pass | Hikari/请求恢复有短暂尾部影响；collector 不伪造 backlog=0 |
| F3 consumer pause | consumer `consumeEnable=false` 后，一次请求返回 200；Redis 为 PROCESSING、reservation=1、DB 订单=0；Broker `Diff Total=1` | 恢复 consumer 后 4 秒内 DB 订单=1、Redis SUCCESS、Diff=0；重复订单=0、负库存=0 | 已接受事实保持并收敛。通用 runner 若没有注入夹具流量会明确输出 BLOCKED，不能仅凭管理命令成功报 pass |
| F3 Broker | 商铺读控制路径 59/59 2xx | Broker 管理面 9 秒可用，5/5 控制请求 2xx；不变量 pass | 只证明读路径独立；本轮未把 Broker 故障下的新秒杀准入完整语义报为已验证 |
| F4 ES | 60/60 请求 HTTP 200，但应用日志持续记录 ES `ConnectException`；没有切到无界 MySQL LIKE | ES 8 秒 ready，5/5 控制请求 HTTP 200；不变量 pass | HTTP 200 掩盖依赖失败，当前 ES sync 自动补齐也未证明；应进入 M5C 搜索降级/稳定错误语义 |

F1/F2 的“无 HTTP 响应”是 curl 在 2 秒超时后的 transport error，不是 5xx。F4 的 200 也不等于搜索成功；当前统一异常响应保留了 HTTP 200。这些都是需要后续处理的负面基线。

补做“Broker 停止时新秒杀准入”时发生过一次执行隔离事故：补测应用误用了不存在的 `LOCAL_DEALS_ROCKETMQ_NAME_SERVER`，实际连接到 `localhost:9876`，运行约 66 秒后由日志识别并立即停止。该请求及其结果全部判无效，未写入汇总；应用使用的 MySQL/Redis/ES 仍是 M5A 专用实例，但它可能令共享 Broker 中同名 consumer group 的既有消息增加一次重试次数。没有对共享 Broker 执行清理或补偿操作。正式 B0–B4/F1–F4 应用使用的是 `127.0.0.1:19876`；出于停止线要求，本轮不再发送新故障流量，Broker-unavailable 下的新秒杀准入明确保留为 BLOCKED。

## 5. M5B/M5C 证据化优先级

### M5B：有界缓存

1. 优先给 Redis 操作和商铺读取建立有界超时/错误回退契约；F1 已证明当前请求会超过 2 秒且不返回。
2. 评估商铺详情 cold miss 的 singleflight；当前 20 并发只放大到 2 次 DB fallback，先以该值作为门禁，不引入通用缓存平台。
3. 保留现有短空值；不存在 ID pilot 只有首次 DB fallback，没有布隆过滤器必要性证据，因此 M5B 不实现布隆过滤器。
4. 热榜已有安全 DB fallback 和 singleflight；优先补故障超时，而不是改写 M4 generation 协议。

### M5C：流控与降级

1. 搜索依赖故障应返回稳定 503/业务码或有界旧值，而不是 HTTP 200 + 异常日志；禁止无界 MySQL `%LIKE%`。
2. Redis 故障下认证和秒杀继续 fail closed，但要在目标时限内返回可识别 503；不能绕过资格或会话验证。
3. MySQL 冷 miss/Hikari 等待需要本地并发上限和稳定失败语义，恢复期保留已接受订单消费优先级。
4. Broker 故障下的新秒杀提交、半消息回查和孤立预约仍需专门场景验证；不能从商铺读 59/59 成功推断 producer 安全。
5. OTP/admin login 的规则只补指标和稳定返回，不重复建设未经需求证明的动态规则平台。

降级顺序仍为：先暂停热榜刷新/预热等可重建任务，再暂停可延迟通知，随后限制搜索和普通写；认证、秒杀资格等写入在依赖不可确认时 fail closed；已接受订单的消费、查询和对账优先恢复。

## 6. 测试边界与不能声称的结论

- 单轮吞吐不是容量；本轮没有 baseline/current 性能提升对比。
- 应用 Counter 不是 MQ backlog；F3 lag 来自 Broker `consumerProgress`。
- consumer-level ES 测试不是 Canal E2E；当前模型没有可靠事件时间，未提供 ES sync lag。
- 容器 ready/进程存活不等于数据恢复；每个可验证场景都另查业务不变量。
- F1 的 token 丢失来自本轮隔离 Redis 配置，不直接代表生产持久化策略。
- F3 Broker 新准入补测因上述隔离事故判为 BLOCKED；不能引用无效请求声称 fail-closed 已由真实 Broker 故障验证。
- M5A 没有实现限流、自动降级、逻辑过期、布隆过滤器、M6 业务或依赖升级。

## 7. 最终验证

- Dragonwell Java 8 `mvn clean test`：242 tests，0 failure / 0 error / 0 skipped；`compile` 和 `testCompile` 均从 clean target 完成。
- Java 8 风险定向套件：69 tests，0 failure / 0 error / 0 skipped，覆盖指标枚举、认证、点赞/热榜、Outbox、秒杀 consumer/reconciliation、ES 指标和 nginx boundary。
- 专用真实依赖套件：7 tests，0 failure / 0 error / 0 skipped，其中 CanalSyncIT 3、Redis collector 2、MySQL collector/EXPLAIN 1、management full-context 1。
- baseline/fault/stack 三个脚本通过 `bash -n`；机器可读 CSV 为 19 列、30 行且所有行列数一致；`git diff --check` 通过。
- public nginx boundary、management split port 和未暴露端点同时由源码契约测试及 full-context 测试验证。

正式测试阶段只访问 `m5a_20260820m5a`、本轮专用 Redis/ES/Broker 和随机 loopback Web 端口；补测误连事故已在第 4 节单列。原始 artifacts 不提交。结果审查后已删除且仅删除本轮 5 个带标签容器和专用网络，容器数据不可恢复；残留容器/网络检查均为空，其他运行容器保持存在。
