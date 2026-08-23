# M5D 指标与故障恢复收口结果

> 状态：完成（2026-08-22）
>
> 起点：`5677047 docs(traffic): record M5C evidence and prioritize M5D`
>
> 正式 run-id：`m5d-20260822a`

## 1. 结论与边界

M5D 已补齐路线图 8.5 的覆盖矩阵和秒杀 DB persist Timer，ES sync topic/group 已配置化且
默认值不变。目标 ES 消息的解析、行转换或写入失败现在会抛出，由 RocketMQ retry/DLQ；DDL
和无关表继续 ACK。固定业务主键作为 ES `_id`，重放最终只有一份文档。

F1--F5 均在 run-id 专用 MySQL/Redis/ES/RocketMQ 与两个 Java 8 应用实例上通过。F4 是向
专用 RocketMQ topic 投递后由真实 consumer 处理的 **consumer-level ES** 验证，不是 Canal E2E；
没有可信事件时间，因此 ES event lag 保持 `NA`，只采 Broker main/retry/DLQ。

## 2. 隔离身份

- schema：`m5d_m5d_20260822a`；另用 fresh schema `m5d_m5d_20260822a_flyway` 验证 V1--V8。
- loopback 端口：应用 `28081/28082`，management `29081/29082`，MySQL `23316`，Redis
  `26389`，ES `29201`，NameServer `29877`，Broker `30931`（HA `30932`）。
- seckill topic/group：`m5d-20260822a-seckill` / `m5d-20260822a-seckill-consumer`；ES sync
  topic/group：`m5d-20260822a-es-sync` / `m5d-20260822a-es-consumer`。
- 五个容器和网络均带 `com.localdeals.m5d.run-id=m5d-20260822a`；未使用共享依赖。

## 3. 故障结果

机器可读摘要见 `docs/evidence/m5/m5d-reliability-summary.csv`。

| 场景 | HTTP 与恢复 | 资源、backlog 与不变量 | 结果 |
| --- | --- | --- | --- |
| F1 Redis pause | OTP 5/5、秒杀 5/5 均 503 `AUTH_STATE_UNAVAILABLE`；shop 5/5 200 DB fallback；最大 546.083ms；恢复 79ms | PROCESSING collector=`NaN`，Hikari pending=0，两组 Broker 均 0/0/0；订单/库存无变化 | PASS |
| F2 MySQL stop | shop 5/5 503 `DATABASE_UNAVAILABLE`；p50 755.174ms，最大 30011.234ms；恢复后 1/1 200，恢复 11097ms | Hikari pending=2，Outbox oldest=`NaN`，两组 Broker 0/0/0；业务行不变 | PASS；首个 leader 仍保留既有约 30s Hikari/driver 边界 |
| F3 consumer pause | 接受 1/1 200，状态 1/1 200；恢复 21801ms | pause 时 Hikari active=1、PROCESSING oldest=34s、seckill 1/0/0；恢复 0/0/0；订单=预约=`SUCCESS`=1，DB/Redis 库存=999，无 processing/重复/负库存 | PASS |
| F4 ES pause | 搜索 1/5 429 `SEARCH_OVERLOADED`、4/5 503 `SEARCH_UNAVAILABLE`；最大 1161.601ms；恢复 13373ms | failure message/row Counter 从 1 增至 2；恢复 success=1；最终 ES 0/0/0；文档 `990001` 重放后 count=1，MySQL 不变 | PASS；恢复快照曾见 retry lag=1，最终收敛为 0 |
| F5 Broker stop | 严格预检通过后才发送；20/20 503 `SECKILL_SUBMIT_UNAVAILABLE`；p50 102.063ms，最大 110.290ms | 两个 PID 均连接专用 29877/30931 且不连接共享 9876；库存/预约/processing/DB/Broker 可见消息 delta=0；恢复后两组 0/0/0 | PASS |

F3 的 DB persist success Timer count=1、sum=47.706266845s；这是为制造 PROCESSING oldest
而持有行锁产生的真实等待，不解释为性能收益。F4 故障快照 main/retry/DLQ 为 0/0/0，是消息
正处于 in-flight/retry 时序，不能把这个瞬时 0 当作没有失败；failure Counter、随后 retry=1
以及最终 success/lag=0 共同构成恢复证据。

## 4. 负面证据与当前 BLOCKED

以下失败均保留，未改写为成功：

1. 首轮把 observability interval 设为 1s，违反应用最小 15s 校验，故障和请求均未开始；修正
   runner 为 15s，未修改应用契约。
2. 第二轮误复用 M5C probe 的 `m5c-u-*` token，F3 得到 401，在订单接受前停止；改用专用
   M5D probe 后重跑。
3. 首次 F5 预检把无关共享 `:9876` 进程计入全局连接，按契约 `BLOCKED`、0 请求且未停 Broker。
   修正为逐应用 PID 校验后，两个 PID 的环境和 TCP 连接均通过，才单独执行 F5。
4. 一次 Redis pause 后执行 fixture 删除被 Docker 拒绝；删除已移到 pause 前。
5. F5-only 重跑曾覆盖同名应用日志；runner 已改为 `application-f5-*`，旧 F4 仍有阶段 Prometheus、
   Broker、HTTP 和 ES 文档证据，但被覆盖的原日志不可恢复。

当前 `BLOCKED`：无。历史 F5 BLOCKED 证据保留在 run 目录，不取代随后通过严格门禁的正式结果。

## 5. 验证

| 门禁 | 结果 |
| --- | --- |
| Java 8 默认全量 `mvn clean test` | 302 tests，0 failure/error/skip |
| 风险定向（metrics、ES consumer/config、秒杀 consumer/reconcile、backlog、流控/cache） | 74 tests，0 failure/error/skip |
| 实现时首轮定向 | 24 tests，0 failure/error/skip |
| 真实依赖 IT | `CanalSyncIT` 3 + `ReliabilityBacklogRedisIT` 2 + `ManagementEndpointSecurityIT` 1 = 6 tests，全部通过 |
| fresh Flyway | V1--V8 共 8 条，`success=1` |
| 语法与仓库门禁 | `bash -n`、Python CLI、CSV 列数、`compile test-compile`、`git diff --check` 均通过 |

`CanalSyncIT` 仍是直接调用 consumer 的真实 ES 测试，名字不改变其 consumer-level 边界。

## 6. Cleanup 与保留范围

收尾只停止并删除 label 精确匹配本 run-id 的五个容器和网络；专用 schema/Redis/ES/Broker
容器数据随之删除，不触碰共享服务。`benchmark/m5d/m5d-20260822a/` 原始证据受 `.gitignore`
保护并保留在工作区，包括 HTTP 样本、Prometheus 快照、Broker progress、PID/env/TCP 预检、
业务不变量和负面 pilot。cleanup 后按 run-id 查询容器=0、网络=0，两个应用 PID 均已停止。
M6 未开始，未升级技术栈，也未调整 Hikari、Redisson 或 M5B 协议。

## 7. 提交切片

1. `bda2b85 docs(observability): define M5D closure contract`
2. `54b012c fix(observability): retry failed ES sync deliveries`
3. `15e07c8 test(reliability): add isolated M5D fault runner`
4. `8f965ab fix(reliability): preserve F5 rerun artifacts`
5. `docs(observability): record M5D recovery evidence`（本结果文档提交）

均为本地提交，未 push。
