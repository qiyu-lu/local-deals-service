# 03. 秒杀下单、PROCESSING 与恢复：源码级精读

## 业务场景

用户在短时间内抢同一张秒杀券。系统既要快速拒绝超限、未开始、已结束、库存不足和重复购买，也要回答更难的问题：Redis 已接受预约，但 RocketMQ 返回不确定、MySQL 暂时失败或 Redis 终态回写失败时，订单处于什么现场，后续由谁恢复。

## 旧方案

V1/tutorial 保留了 Redis Stream 风格的 `seckill.lua`、`stream.orders` 和简单异步消费配置。它适合演示预扣与异步下单，但不能代表当前主链路。当前 HTTP 入口使用 Redis Lua admission + RocketMQ 事务消息 + MySQL 幂等落库；`SeckillProperties.Stream` 和旧脚本只是兼容/历史对象，不应与当前 RocketMQ consumer 混写。

## 问题

“Redis 扣库存成功”“事务消息 COMMIT”“MySQL 插入成功”和“Redis 状态为 SUCCESS”是四个阶段。若仅凭用户购买 Set、HTTP 200、MQ send 返回或一次 DB 查询为空做结论，就可能重复落库、错误回补库存、吞掉待重试消息，或把 orderId 冲突误当成可安全补偿。

## 业务不变量与组件角色

- MySQL 是订单与数据库库存的长期事实源：`tb_voucher_order.id` 主键、V2 的 `uk_voucher_order_user_voucher(user_id,voucher_id)` 和 `tb_seckill_voucher.stock > 0` 条件更新共同兜底。
- Redis 是入口流控、活动元数据、快速库存、精确预约和恢复状态协议；它不是已持久化订单的最终证明。
- RocketMQ 负责事务消息提交/回查和 consumer 重试交付，不保存订单事实。
- WebSocket 只做 best-effort 在线提示；Elasticsearch 不参与秒杀链路。
- admission 接受必须原子写入 `userId -> orderId` reservation、每订单 `PROCESSING` Hash 和全局 due ZSET；64 位 orderId 始终按十进制字符串传给 Lua。
- `PROCESSING` 不设置短 TTL；`SUCCESS`/`FAILED` 移除 processing index 后才设置 7 天 TTL。
- consumer 和 reconciler 共享 `lock:order:{userId}`。`lock:seckill:reconcile:{orderId}` 只仲裁同一 due member，不能替代用户锁。
- DB 查询异常不能分类成 `ABSENT`。orderId 主键冲突破坏 ownership 证明，只能 suspend + quarantine，禁止自动补偿。

## HTTP 接口契约与错误码

`/voucher-order/**` 不在 `WebConfig.PUBLIC_GET_PATHS/PUBLIC_POST_PATHS`，因此由 `LoginInterceptor` 要求消费者登录。Controller 使用统一 `Result`；直接 `Result.fail(...)` 仍是 HTTP 200，`ApiStatusException` 才由 `WebExceptionAdvice` 改变 HTTP 状态。

| 请求 | Controller#方法 | 成功 | 业务拒绝/不可用 |
| --- | --- | --- | --- |
| `POST /voucher-order/seckill/{id}` | `VoucherOrderController#seckillVoucher` | HTTP 200，`success=true`，`data` 是十进制字符串 orderId | 流控：HTTP 429 + `SECKILL_RATE_LIMITED`；库存/重复/时间窗：HTTP 200 + `SECKILL_OUT_OF_STOCK`、`SECKILL_DUPLICATE`、`SECKILL_NOT_STARTED`、`SECKILL_ENDED`；元数据未就绪：HTTP 503 + `SECKILL_STATE_UNAVAILABLE`；ID/MQ/Redis 不可用且无法精确恢复：HTTP 503 + `SECKILL_SUBMIT_UNAVAILABLE` |
| `GET /voucher-order/status/{orderId}` | `VoucherOrderController#querySeckillOrderStatus` | HTTP 200，返回字符串 `orderId`、`voucherId`、`PROCESSING/SUCCESS/FAILED` 和可选 reason | 非 owner、暂不可用、不存在/已过期都返回 HTTP 200、`success=false`，当前没有稳定 code |

Redis 为 `PROCESSING` 但 owner 的 MySQL 订单已存在时，查询返回 `SUCCESS` 并 best-effort 调用 `markSuccess`；Redis 查不到或异常时，也按 `orderId + current userId` 回查 MySQL。若 Redis 明确显示另一个 userId，则直接拒绝，不泄露 DB 订单。

## 成功链路：逐方法推演

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `VoucherOrderController#seckillVoucher` | voucherId、request | `TrustedClientIpResolver#resolve` | 无 | MVC 登录拦截器已建立 `UserHolder` | 调 service | 未登录由拦截器拒绝 |
| 2 | `VoucherOrderServiceImpl#seckillVoucher` | voucherId、clientIp | `UserHolder` userId | 指标 | 无 | 进入流控 | 下游异常按 429/503 映射 |
| 3 | `SeckillTrafficGuard#check` | voucherId、userId、clientIp | Redis server time、三维固定窗口计数 | 仅 allow 时增加 activity/user/IP counter 和 TTL | `seckill_traffic_guard.lua` 原子执行 | 继续 | 超限 429；Redis 错误/null/未知码 fail closed 为 503 |
| 4 | `RedisIdWorker#nextId` | `"order"` | Redis `INCR icrorder:yyyy:MM:dd` | 当日序列 | Redis 单命令原子 | 组合相对 UTC 时间戳和 32 位序列得到 long orderId | Redis 异常：不发送 MQ，503 |
| 5 | `SeckillOrderProducer#sendSeckillTransaction` | voucherId、userId、orderId | `local-deals.seckill.topic` | RocketMQ half message | 阻塞到 local transaction 完成 | 返回 context 中 Lua code | 发送/客户端异常返回 `-1` |
| 6 | `SeckillOrderProducer#executeLocalTransaction` | half message、context | Redis stock/meta/Set/reservation/status/index | `seckill_check.lua` 原子预约 | RocketMQ callback + Redis Lua | 0→COMMIT；1..5→ROLLBACK | 参数错、Redis 异常、null/未知码→UNKNOWN |
| 7 | `SeckillOrderConsumer#onMessage` | message | Redis reservation/status/quarantine | 指标 | `lock:order:{userId}` | 仅 `PROCESS` 进入落库 | terminal ACK；缺状态/锁忙/poison 抛异常重试 |
| 8 | `SeckillOrderStateService#validateForConsumption` | message | exact status/reservation/quarantine | 无 | read-only Lua | `PROCESS/ALREADY_SUCCESS/ALREADY_FAILED` | `RETRYABLE_STATE_MISSING/POISONED` |
| 9 | `VoucherOrderServiceImpl#createVoucherOrder` | `VoucherOrder` | 两张 MySQL 表 | insert order；条件扣 stock | MySQL 事务 | 新订单提交或 exact replay 幂等 | 库存、pair、orderId conflict 或 transient DB 异常，事务回滚 |
| 10 | `SeckillOrderStateService#markSuccess` | message | exact reservation/status | SUCCESS、删除 due、TTL=7 天 | Redis Lua | Redis 终态收敛 | false/异常：consumer 抛出，MQ 重试；DB 可能已提交 |
| 11 | `SeckillOrderConsumer#notifyBestEffort` | 结果 | 无持久事实 | Pub/Sub/WebSocket | 不参与订单事务 | 在线提示 | 仅日志，不重试已完成订单 |

入口流控 Key 前缀是 `traffic:seckill:{voucherId}:activity:`、`traffic:seckill:{voucherId}:user:{userId}:`、`traffic:seckill:{voucherId}:ip:{sha256(clientIp)}:`，Lua 再追加 server-time bucket。默认配置为 `local-deals.traffic.seckill.enabled=true`、`window=1s`、activity 300、user 2、IP 100。只有 direct peer 属于 trusted proxy 时才采用转发头。

## RocketMQ half message 与事务回查

`sendMessageInTransaction(topic,message,context)` 先发送 half message，再触发本地事务回调。这里的“本地事务”不是 MySQL 事务，而是 Redis admission Lua：

| local transaction | Broker 动作 | HTTP 线程可见结果 |
| --- | --- | --- |
| `COMMIT` | half message 对 consumer 可见 | Lua 0 |
| `ROLLBACK` | 丢弃 half message | Lua 1..5 |
| `UNKNOWN` | broker 保留并回查 | context `-1`，或 producer 捕获异常返回 `-1` |

`SeckillOrderProducer#checkLocalTransaction` 的顺序是：payload 完整；quarantine 有 orderId 则 ROLLBACK；reservation 必须精确等于 orderId，否则 ROLLBACK；status Hash 的 `orderId/userId/voucherId` 必须一致，否则 ROLLBACK；`PROCESSING`/`SUCCESS`→COMMIT，`FAILED`→ROLLBACK，status 缺失/未知或 Redis 异常→UNKNOWN。legacy Set 的“用户买过”不能单独证明当前 half message 可提交。

## `seckill_check.lua` 完整契约

| 位置 | 值 | 语义 |
| --- | --- | --- |
| `KEYS[1]` | `seckill:stock:{voucherId}` String | admission 库存 |
| `KEYS[2]` | `seckill:order:{voucherId}` Set | 兼容旧数据的已购用户 |
| `KEYS[3]` | `seckill:meta:{voucherId}` Hash | `status/beginAt/endAt` |
| `KEYS[4]` | `seckill:reservation:{voucherId}` Hash | userId→exact orderId |
| `KEYS[5]` | `seckill:order:status:{orderId}` Hash | 每订单状态与 ownership |
| `KEYS[6]` | `seckill:order:processing` ZSET | member=orderId，score=due epoch second |
| `ARGV[1..4]` | userId、voucherId、orderId、stale-after seconds | orderId 保持字符串；默认 stale-after=120 秒 |

Lua 先验证 `staleAfter > 0`，再要求 meta 完整、`beginAt <= endAt`、status=`ACTIVE`；时间来自 Redis `TIME`。`now < beginAt` 未开始，`now > endAt` 已结束，因此 endAt 当秒仍可接受。随后要求 stock>0、legacy Set 不含 user、reservation 不存在。所有 guard 都在首次写入之前。

| 返回码 | 含义 | 原子副作用 |
| --- | --- | --- |
| 0 | accepted | `DECR` stock；`SADD` legacy Set；`HSET` reservation；写每订单 Hash：`PROCESSING/orderId/userId/voucherId/reason/createdAt/updatedAt/reconcileAttempts=0`；`PERSIST`；全局 ZSET 写 `now+staleAfter` |
| 1 | out of stock | 无 |
| 2 | legacy Set 或 reservation 已存在 | 无 |
| 3 | not started | 无 |
| 4 | ended 或非 ACTIVE | 无 |
| 5 | meta/stale 参数缺失或非法 | 无 |

`SeckillVoucherRedisInitializer#run` 在非 `test` profile 启动时从 `tb_seckill_voucher` 加载活动，只补齐缺失的 stock/meta 字段，不覆盖 live state；非法 DB 行或 DB/Redis 失败会阻止启动。它不同于 opt-in 的 processing index backfill。

## 数据与状态模型

### Redis

| Key | 类型/字段 | 角色与生命周期 |
| --- | --- | --- |
| `seckill:stock:{voucherId}` | String | admission 快速库存；不是 MySQL 库存事实 |
| `seckill:order:{voucherId}` | Set(userId) | 旧兼容已购标记；新 admission 同步维护 |
| `seckill:meta:{voucherId}` | Hash `status/beginAt/endAt`，可有 `suspendReason/updatedAt` | `ACTIVE` 才接单；永久冲突先改 `SUSPENDED` |
| `seckill:reservation:{voucherId}` | Hash userId→orderId | exact ownership |
| `seckill:order:status:{orderId}` | Hash `status/orderId/userId/voucherId/reason/createdAt/updatedAt/reconcileAttempts/lastReconcileAt` | 每订单 `PROCESSING/SUCCESS/FAILED`；terminal TTL 7 天 |
| `seckill:order:processing` | 全局 ZSET | due/retry 调度，不按 voucher 分 key |
| `seckill:order:processing:quarantine` | ZSET | 隔离时间 |
| `seckill:order:processing:quarantine:reason` | Hash raw member→reason | 隔离原因 |
| `lock:order:{userId}` | Redisson lock | consumer/reconciler 共同业务锁 |
| `lock:seckill:reconcile:{orderId}` | Redisson lock | scheduler 仲裁锁 |

### MySQL

`tb_voucher_order` 主键为 `id`，含 `user_id`、`voucher_id` 和教程 schema 中的 pay/status/time 字段；本链路只创建订单，不证明支付、退款或核销已实现。V2 唯一约束名为 `uk_voucher_order_user_voucher`。`tb_seckill_voucher` 以 `voucher_id` 为主键，保存 `stock/begin_time/end_time`。

`VoucherOrderServiceImpl#createVoucherOrder` 先 insert，再执行 `stock = stock - 1 WHERE voucher_id=? AND stock>0`，两步同属 MySQL 事务。条件更新失败回滚 insert。DuplicateKey 后按 writer DB 复核：同主键且 owner/voucher 一致为幂等 replay；同主键 ownership 不同抛 `OrderIdConflictException`；否则按 `(user_id,voucher_id)` 查到已有订单并抛 `OrderReservationConflictException`。

## Consumer 决策、异常分类与终态

| `ReservationDecision` | consumer 动作 |
| --- | --- |
| `PROCESS` | MySQL 持久化后 `markSuccess` |
| `ALREADY_SUCCESS` | 不访问 DB，ACK |
| `ALREADY_FAILED` | 不访问 DB，ACK |
| `RETRYABLE_STATE_MISSING` | 不访问 DB，抛异常等待 MQ 重试 |
| `POISONED` | ownership/reservation/quarantine 不安全，不访问 DB，重试并最终可能 DLQ |

`markSuccess` 只接受 exact reservation/ownership。`PROCESSING→SUCCESS` 时更新时间、删 reason、移除 due、设置 7 天 TTL；exact `SUCCESS` replay 幂等清理 due 并刷新 TTL。reservation 保留为成功归属证据。

`compensate` 先拒绝 quarantine，再要求 exact reservation + exact `PROCESSING`；一次性增加 Redis stock、删除 reservation 和 legacy Set member、写 `FAILED/reason/updatedAt`、移除 due、设置 7 天 TTL。exact `FAILED` replay 不会再次加库存。consumer 对 DB 库存耗尽或 user-voucher conflict 先 `suspendVoucher` 再补偿；补偿失败继续重试。orderId conflict 只 suspend + quarantine，故意不补偿并继续抛错。

## 状态查询 fallback

`VoucherOrderServiceImpl#querySeckillOrderStatus`：

1. 读取 `seckill:order:status:{orderId}`，要求 `orderId/userId/voucherId/status` 可解析。
2. owner 非当前用户时返回“订单不存在或无权查看”。
3. `PROCESSING` 时按 orderId 查 MySQL 并复核 userId/voucherId；存在 exact order 就返回 `SUCCESS`，并 best-effort 修复 Redis。
4. 其他合法 Redis 状态直接返回；`FAILED` reason 只映射为用户文案。
5. Redis 无状态或异常时，按 orderId + current userId 回查 MySQL；存在则返回 `SUCCESS`。
6. 两侧均无 owner 订单时才返回“状态暂不可用”或“不存在/已过期”。

这能修复“DB 已提交、markSuccess 失败”的窗口，但不能从未知 ownership 的 DB 行重建状态。

## Reconciler：due、claim、分类与隔离

`local-deals.seckill.reconciliation.enabled` 默认 `false`。默认 initial 30 秒、fixed 10 秒、stale 2 分钟、retry 1 分钟、final timeout 15 分钟、batch 100、backfill scan 500；`compensation-enabled`、`backfill-on-startup` 也默认 `false`，live worker 与 startup backfill 不能同进程同时启用。

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `SeckillOrderReconciler#reconcileDueOrders` | batchSize | 全局 processing ZSET 中 score≤Redis TIME 的有限成员 | 坏 raw member 可 quarantine | due Lua | canonical positive orderId 列表 | Redis 错误本轮 fail closed；坏成员不阻断后续 |
| 2 | `#reconcileOne` | candidate orderId | 无 | 无 | `lock:seckill:reconcile:{orderId}` | winner 继续 | loser 不移动 winner score |
| 3 | `#reconcileScheduled` | key-derived orderId | per-order status | unresolved member 仅 defer scheduler score | scheduler lock 内 | 解析 user/voucher | 无法解析 user 时不能越过共享锁 quarantine |
| 4 | `#reconcileUnderUserLock` | message | 无 | lock busy 时 defer score | `lock:order:{userId}` | 与 consumer 串行 | 不查 DB、不 claim |
| 5 | `SeckillOrderStateService#claimForReconciliation` | message、retryDelay | status/reservation/index/quarantine、Redis TIME | CLAIMED 时 attempts+1、lastReconcileAt/updatedAt、PERSIST、score 后移 | Lua + user lock | `CLAIMED` 后查 writer DB | skip 或把 unsafe claim quarantine |
| 6 | `VoucherOrderServiceImpl#classifyPersistence` | order/user/voucher | writer `tb_voucher_order` | 无 | read-only MySQL 事务 | EXACT/ABSENT/USER_VOUCHER_CONFLICT/ORDER_ID_CONFLICT | DB 异常传播，禁止变 ABSENT |
| 7 | `#reconcileAgainstDatabase` | claim + classification | age/开关 | markSuccess、保留、suspend、compensate 或 quarantine | user lock 内 | 按下表收敛 | 失败保持可重试现场 |

claim 枚举为 `CLAIMED/NOT_DUE/TERMINAL/OWNERSHIP_MISMATCH/STATE_INVALID/RESERVATION_MISMATCH/INDEX_MISSING/QUARANTINED`。CLAIMED 原子增加 attempts 并把 due score 移到 `now+retryDelay`；terminal/quarantined 会删除 processing member；ownership/state/reservation 不安全时在用户锁内 quarantine。

| writer DB 分类 | 恢复动作 |
| --- | --- |
| `EXACT_MATCH` | `markSuccess`，成功后 best-effort 通知 |
| `ABSENT` 且 age < final timeout | claim 已后移 score，保持 `PROCESSING` |
| `ABSENT` 超龄、compensation off | 保持 `PROCESSING`，记录 `COMPENSATION_DISABLED` |
| `ABSENT` 超龄、compensation on | exact compensate 为 `FAILED/PROCESSING_TIMEOUT` |
| `USER_VOUCHER_CONFLICT` | 先 suspend；off 时保留，on 时 exact compensate |
| `ORDER_ID_CONFLICT` | 总是 suspend + quarantine，永不补偿 |
| DB 异常 | 保持 `PROCESSING`，禁止补偿 |

quarantine 只移除 processing member并写 quarantine ZSET/reason Hash，不会伪造 `FAILED` 或恢复 stock。`SeckillProcessingIndexBackfillRunner#run` 仅在 `backfill-on-startup=true` 时扫描 `seckill:order:status:*`；exact legacy PROCESSING 用 `ZADD NX` 按 `createdAt+staleAfter` 补索引，canonical unsafe 状态会保留并使启动失败。

## 关键故障现场与恢复动作

| 场景 | 当下现场 | 恢复/最终边界 |
| --- | --- | --- |
| 正常 | Redis PROCESSING/due；MQ COMMIT；MySQL order/stock 提交；Redis SUCCESS | 查询 SUCCESS；通知失败不影响事实 |
| 重复 | Lua 2，新 orderId 无任何 admission 写入 | HTTP 200 + `SECKILL_DUPLICATE`；既有预约不变 |
| TrafficGuard Redis 故障 | 无 orderId、MQ、预约 | HTTP 503 |
| ID worker Redis 故障 | 流控可能已计数；无 MQ/预约 | HTTP 503，无订单补偿 |
| MQ local transaction Redis 故障 | half message UNKNOWN，无 admission 完成证明 | broker 回查；HTTP 仅在 exact PROCESSING/SUCCESS 可恢复 orderId，否则 503 |
| send 异常但 Lua 已接受 | Redis PROCESSING/due，broker 状态不确定 | service exact recovery 返回 orderId；broker check COMMIT |
| transient DB 异常 | MySQL 回滚；Redis PROCESSING/due | consumer 抛错、RocketMQ 重投；reconciler 也不补偿 DB error |
| MySQL 库存耗尽 | insert 回滚；Redis 曾预扣 | meta SUSPENDED；exact compensate：stock+1、删 reservation/Set、FAILED、删 due，然后 ACK |
| user-voucher conflict | DB 有同用户同券另一 order | consumer suspend+compensate+ACK；reconciler 受补偿开关控制 |
| orderId conflict | 主键属于另一 owner/voucher | suspend+quarantine；保留 reservation/stock；retry/最终 DLQ |
| DB 提交但 markSuccess 失败 | DB order/stock 已提交；Redis PROCESSING/due | MQ 重投后 exact replay；查询/reconciler 也可 repair SUCCESS |
| 超龄且 DB ABSENT | score 已后移；PROCESSING | compensation off 保留；on 且超过 final timeout 才 FAILED |
| 迟到消息遇 SUCCESS/FAILED | terminal state | 不写 DB，ACK |
| 迟到消息遇 quarantine/mismatch | POISONED | 不写 DB，retry/最终 DLQ，待人工判定 |

## 调试现场检查顺序

1. HTTP：用户 token、voucherId、HTTP status、`Result.success/code/errorMsg/data`，不能只记 200。
2. MySQL：按 orderId 查 `tb_voucher_order`，再按 `(user_id,voucher_id)` 查冲突行；核对 `tb_seckill_voucher.stock`。DB read failure 不是 ABSENT。
3. Redis admission：`seckill:meta:{voucherId}`、`seckill:stock:{voucherId}`、legacy Set、reservation，特别看 meta 是否 `SUSPENDED`。
4. Redis 状态：完整 `seckill:order:status:{orderId}`、TTL、全局 processing score、quarantine score/reason。PROCESSING 不应有短 TTL。
5. RocketMQ：核对运行时 topic/group，再区分 half message COMMIT/ROLLBACK/UNKNOWN、consumer retry 与 DLQ；单看 lag 不能决定订单真相。
6. 日志：按 orderId/userId/voucherId 串联 producer、consumer、reconciler 的 Lua、ownership、DB classification、compensation/quarantine 日志。
7. 指标：`local_deals.seckill.requests`、`local_deals.traffic.decision`、`local_deals.seckill.mq.consume`、`local_deals.seckill.mq.consume.outcome`、`local_deals.seckill.db.persist.duration`、`local_deals.seckill.reconciliation` 和 processing due/oldest_overdue/quarantine gauges。指标只帮助定位，仍须核对现场。

## 方案取舍

Redis Lua 提供低延迟原子预约，MySQL 提供长期事实与最终约束，RocketMQ 将写库移出 HTTP 线程；代价是必须显式维护 transaction check、consumer 幂等、无短 TTL 的 PROCESSING、精确补偿和 bounded reconciliation。

实现偏向“安全地保留不确定性”：DB 异常、未知 ownership 或 orderId 冲突时宁可保持 PROCESSING/quarantine 并暂停活动，也不错误恢复库存。reconciler 不补发 MQ，而是查询 writer DB 分类并修复 Redis。

## 代码导航

| 关注点 | 源码锚点 |
| --- | --- |
| HTTP/错误封装 | [`VoucherOrderController#seckillVoucher/#querySeckillOrderStatus`](../../src/main/java/com/localdeals/controller/VoucherOrderController.java)、[`WebConfig`](../../src/main/java/com/localdeals/config/WebConfig.java)、[`WebExceptionAdvice`](../../src/main/java/com/localdeals/config/WebExceptionAdvice.java) |
| service 入口/查询/DB | [`VoucherOrderServiceImpl#seckillVoucher/#querySeckillOrderStatus/#classifyPersistence/#createVoucherOrder`](../../src/main/java/com/localdeals/service/impl/VoucherOrderServiceImpl.java) |
| IP/流控/ID | [`TrustedClientIpResolver#resolve`](../../src/main/java/com/localdeals/service/TrustedClientIpResolver.java)、[`SeckillTrafficGuard#check`](../../src/main/java/com/localdeals/service/SeckillTrafficGuard.java)、[`RedisIdWorker#nextId`](../../src/main/java/com/localdeals/utils/RedisIdWorker.java)、[`seckill_traffic_guard.lua`](../../src/main/resources/lua/seckill_traffic_guard.lua) |
| RocketMQ 事务 | [`SeckillOrderProducer#sendSeckillTransaction/#executeLocalTransaction/#checkLocalTransaction`](../../src/main/java/com/localdeals/mq/SeckillOrderProducer.java) |
| admission | [`seckill_check.lua`](../../src/main/resources/lua/seckill_check.lua)、[`RedisConstants`](../../src/main/java/com/localdeals/utils/RedisConstants.java) |
| consumer | [`SeckillOrderConsumer#prepareStart/#onMessage`](../../src/main/java/com/localdeals/mq/SeckillOrderConsumer.java) |
| 状态 Lua | [`SeckillOrderStateService`](../../src/main/java/com/localdeals/service/SeckillOrderStateService.java)、[`seckill_validate_reservation.lua`](../../src/main/resources/lua/seckill_validate_reservation.lua)、[`seckill_mark_success.lua`](../../src/main/resources/lua/seckill_mark_success.lua)、[`seckill_compensate.lua`](../../src/main/resources/lua/seckill_compensate.lua) |
| reconciliation | [`SeckillOrderReconciler#reconcileDueOrders`](../../src/main/java/com/localdeals/service/SeckillOrderReconciler.java)、[`seckill_reconcile_claim.lua`](../../src/main/resources/lua/seckill_reconcile_claim.lua)、[`SeckillProcessingIndexBackfillRunner#run`](../../src/main/java/com/localdeals/init/SeckillProcessingIndexBackfillRunner.java) |
| 初始化/配置 | [`SeckillVoucherRedisInitializer#run`](../../src/main/java/com/localdeals/init/SeckillVoucherRedisInitializer.java)、[`SeckillProperties`](../../src/main/java/com/localdeals/config/SeckillProperties.java)、[`application.yaml`](../../src/main/resources/application.yaml) |
| 数据约束 | [`V1__baseline_schema.sql`](../../src/main/resources/db/migration/V1__baseline_schema.sql)、[`V2__voucher_order_constraints.sql`](../../src/main/resources/db/migration/V2__voucher_order_constraints.sql) |

## 验证证据：fixture、动作、核心断言与边界

以下是仓库已有测试的静态阅读结果；本轮未运行任何测试。

| 测试/代表方法 | fixture | 动作 | 核心断言 | 证据边界 |
| --- | --- | --- | --- | --- |
| `VoucherOrderServiceImplTest#acceptedOrderIdUsesExactStringWireContract`、`#ambiguousProducerFailureRecoversOnlyTheExactProcessingReservation` | mock producer/state/traffic/ID | 调 service | orderId String；仅 exact PROCESSING 可从 `-1` 恢复 | 单元测试，无真实 HTTP/MQ/Redis |
| `SeckillOrderProducerTest#executeLocalTransaction_acceptsAndPassesAllAtomicKeysWithoutNarrowingOrderId`、`#checkLocalTransaction_exactProcessingReservationCommits`、`#checkLocalTransaction_redisFailureReturnsUnknown` | mock Redis/MQ，超 JS 精度 orderId | 直接执行 callback/check | 六 Key 顺序、字符串 ID、COMMIT/UNKNOWN 分支；同类方法覆盖 ROLLBACK | 不执行真实 Lua/broker |
| `SeckillLuaScriptContractTest#admissionScript_checksActivityBeforeMutatingAndWritesExactProcessingReservation` 等 | Lua 文本 | 静态检查 | guard-before-write、exact key/status/index | 不证明 Redis 运行语义 |
| `SeckillOrderStateIT#admissionSuccessAndCompensationAreExactAndIdempotent` 等 | 正式 runner 注入的专用 Redis | 真实 Lua | 预约/补偿幂等、due/claim/backfill/quarantine | isolated Redis，无 MySQL/MQ |
| `VoucherOrderReliabilityIT#createVoucherOrder_stockExhausted_throwsAndRollsBackInsert`、`#createVoucherOrder_replayIsIdempotentButDifferentOrderIdIsRejected`、`#createVoucherOrder_primaryKeyOwnedByAnotherUserIsNotACompensablePairConflict` | 正式 runner 注入的专用 MySQL | 调 transactional service | 库存回滚、exact replay、pair/orderId conflict | isolated MySQL，无 MQ/Redis |
| `SeckillOrderConsumerTest#onMessage_stockExhausted_doesNotThrowSoRocketMQAcks`、`#onMessage_orderIdCollisionSuspendsAndQuarantinesWithoutCompensation`、`#onMessage_databaseCommittedButSuccessMarkFailed_retriesForIdempotentReplay` | mock state/DB/lock/notifier | 直接调 consumer | 补偿后 ACK、quarantine、markSuccess 失败重试；同类方法覆盖 terminal/transient | 不证明 broker 重投 |
| `SeckillOrderReconcilerTest#exactPersistedOrderRepairsSuccessAndNotifies`、`#databaseFailureNeverBecomesAbsenceOrCompensation` 等 | mock state/writer DB/锁 | 调 reconciler | exact repair、DB error 禁补偿、开关/冲突分支 | 单元分支契约 |
| `SeckillOrderReconciliationLockTest#reconcilerCannotClassifyOrCompensateWhileConsumerPersistsUnderSharedLock` | latch + shared lock | 制造并发 | 用户锁序列化；scheduler loser 不移动 score | 锁协议测试 |
| `SeckillWithRocketMQIT#sendSeckillTransaction_concurrentUsers_noOversell` | 专用 MQ/Redis/MySQL fixture | 并发发送并等待消费 | 订单、库存、reservation/终态联合不变量 | 仓库既有专用 IT，不是本轮复现/生产容量 |
| `SeckillOrderRetryIT#transientFailure_isRedeliveredByBroker`、`#permanentFailure_isNotRedelivered` | 专用 broker fixture | 注入 transient/permanent 结果 | broker 重投与 ACK 边界 | 不覆盖所有故障组合 |
| `SeckillTrafficGuardRedisIT#activityUserAndIpLimitsRejectWithoutConsumingOtherDimensions` | 正式 runner 注入的专用 Redis | 三维调用 | 拒绝不消耗其他维度、Redis TIME/TTL | 只证明流控 Lua |

这里的“正式 runner 注入的专用 Redis/MySQL”不是测试类自带能力。[`scripts/run-pre-m8-tests.sh`](../../scripts/run-pre-m8-tests.sh) 的 seckill 组会执行 `SeckillOrderStateIT`、`VoucherOrderReliabilityIT`，从第 69 行开始注入专用 Redis/MySQL 地址、端口、凭据与 schema，并为该组逐测试创建 topic 和 consumer group；`SeckillTrafficGuardRedisIT` 不在这个 Pre-M8 class 列表中，它要求 `M5C_ISOLATED=true`、M5C 专用 Redis 参数和 sentinel，正式入口是 [`scripts/run-m5c-traffic-check.sh`](../../scripts/run-m5c-traffic-check.sh)。这三个测试类本身都不创建或拥有 Testcontainers，仓库也没有 Testcontainers 依赖。隔离性来自相应正式 runner 注入的专用端口、schema、topic、group、sentinel 和资源所有权；脱离 runner 单独执行 IT 时不能自动假定仍连接专用依赖，必须先核对实际连接目标。

Pre-M8 文档还记录了限定 Java 8 本地环境的 1000 样本三轮和 100 样本拒绝场景，并联合检查 accepted、订单、reservation、SUCCESS、库存、processing 和重复订单等不变量。P99、throughput、drain 与本地恢复时间只是该次 run 的观测，不是生产 SLA。

## 不能证明的边界

- 本轮只静态阅读，没有重新运行测试、故障实验或压测。
- 没有 Redis Cluster/Sentinel、跨机房、RPO/RTO、滚动升级或生产 RocketMQ 集群容量证据。
- HTTP 200、进程存活、测试退出码、MQ lag、局部平均值均不能单独证明无超卖、一人一单或最终收敛。
- 专用环境 IT 不能泛化为生产 SLA；consumer 单元测试也不能代替 broker retry/DLQ 证据。
- schema 存在 pay/status 字段不等于支付、退款、核销与履约已实现。
- WebSocket 不是持久订单事实，也没有完整离线通知证据。

## 项目特色摘要

面向秒杀请求在重复消费、服务重启和依赖异常下的跨组件状态不一致问题，构建入口原子校验、事务消息、数据库幂等落库、状态机与定时对账组成的恢复链路。正式简历表述与证据映射见 [07. 简历与面试定稿](07-resume-and-interview.md)。
