# 秒杀 PROCESSING 自动对账与升级门禁

## 目标与一致性边界

本阶段处理“Redis 已精确预占且返回了 orderId，但 RocketMQ 消息长期没有使 MySQL 订单收敛”的超龄 `PROCESSING` 分支。权威关系为：

- MySQL 中完全匹配 `orderId/userId/voucherId` 的订单存在时，订单已成功，Redis 应修复为 `SUCCESS`。
- MySQL 明确无该 orderId，且不存在同 `(userId,voucherId)` 的冲突订单时，到达硬截止时间后才可执行 exact compensation。
- DB 访问异常不等于“订单不存在”，只能延后重试。对账器分类为 `ORDER_ID_CONFLICT` 时暂停活动后进 quarantine，永不自动释放；对账器分类为 `USER_VOUCHER_CONFLICT` 时先暂停活动，仅在 compensation 独立开关已审批时执行 exact compensation。

定时对账与 MQ 消费者共用 `lock:order:{userId}`。锁内必须二次读取 exact Redis 状态，才能查库和改变终态。这防止对账器在消费者已校验、但 DB 事务尚未提交时误补偿。

当前方案的信任边界是 Redis 数据持久性及 Redisson 锁语义正常。它不声称能在 Redis 数据全丢或分布式锁租约分区下仍保持跨 MySQL/Redis 的强原子性。需要这一级别时，应增加以 `order_id` 唯一行仲裁的 DB reconciliation ledger，而不是在 Redis Lua 后非原子地补写 inbox/outbox。

## Redis 状态与索引

- `seckill:order:status:{orderId}`：订单状态小 Hash，包含 owner 三元组及 Redis 服务端时间。
- `seckill:reservation:{voucherId}`：用户到 exact orderId 的预约 Hash，可能很大，升级工具禁止 `HGETALL`。
- `seckill:order:processing`：超时扫描 ZSET，member 是字符串 orderId，score 是下次对账时间。
- quarantine 记录：保留不能证明 exact ownership 的 orderId、Redis 时间和原因；已隔离 member 会阻断 backfill、claim、consumer 和 compensation，不会自动改库存。

Admission Lua 在一个原子操作中建立 reservation/status 并写入 ZSET。未决的 `PROCESSING` 不设 TTL；`SUCCESS/FAILED` 才保留有限 TTL。成功和补偿 Lua 在改终态的同一原子操作中删除 ZSET member。

对账器使用 `ZRANGEBYSCORE` 小批读取，不使用 `ZPOPMIN`；只有终态或显式 quarantine Lua 才能移除待处理项。多实例可能同时看到同一 orderId，因此先用 `lock:seckill:reconcile:{orderId}` 仲裁调度，再以固定的“order 调度锁 → 用户锁”顺序进入 exact claim。调度锁输家只跳过，不能推进 score；唯一赢家在用户锁被消费者占用时才延后该 member。业务状态仍由共享用户锁和 exact Lua 保护，不依赖单点 leader。

若 canonical due member 的 status 已缺失/损坏到无法解析 `userId`，live worker 不会在用户锁外写 consumer 可见 quarantine。它只用原子 Lua 延后该 member 的 scheduler score（并在并发终态/隔离已发生时清除旧索引），保留证据和告警，同时避免一批坏 member 永久饿死后续合法订单。

## 默认关闭的开关

```yaml
local-deals:
  seckill:
    reconciliation:
      enabled: false
      compensation-enabled: false
      backfill-on-startup: false
      initial-delay: 30s
      fixed-delay: 10s
      stale-after: 2m
      retry-delay: 1m
      final-timeout: 15m
      batch-size: 100
      scan-count: 500
```

- `enabled=false`：定时 reconciler 不运行。
- `compensation-enabled=false`：即使执行了对账，DB 无单与 user/voucher 冲突分支也只重试/告警，不自动释放预占。该开关只约束定时 reconciler；MQ consumer 已明确收到 DB 库存耗尽或 user/voucher 永久冲突时，仍会先暂停活动并立即执行原有的 exact compensation。
- `backfill-on-startup=false`：一次性历史索引回填不运行。只允许在停写升级窗口中临时开启。

应用会拒绝在同一次启动中同时设置 `backfill-on-startup=true` 和 `enabled=true`，避免长时间 SCAN 尚未完成时 scheduler 已开始处理半回填数据。`enabled=false` 始终是 worker 的安全总开关，即使 compensation 配置尚未清除也不会创建 reconciler。

延迟、超时、批大小和 SCAN count 由同一配置段提供。`final-timeout` 是显式的业务失败 SLA，不是“已证明 Broker 无在途消息”的技术时钟。它可以短于 RocketMQ 的完整重试窗口：迟到消息会被 exact `FAILED` 门禁安全 ACK，不再落库。上线前必须结合业务可容忍的等待时间、实际消费延迟 P99 与 Broker 重试配置选值并留档；默认 15 分钟只是补偿开关关闭时的本地占位值，不是生产结论。

## 一次性回填行为

回填 ApplicationRunner 只在 `backfill-on-startup=true` 时创建。它使用 Redisson lazy SCAN 扫描 `seckill:order:status:*`：

1. 从 key suffix 严格解析 orderId。
2. 只读取该 orderId 的小 status Hash，不枚举整个 reservation Hash。
3. 由 StateService exact backfill Lua 重新校验 `PROCESSING` 和 `userId -> orderId` reservation。
4. 对合法项执行 `PERSIST` 并以 `createdAt + staleAfter` 幂等写 ZSET。
5. 终态、已隔离或已索引项幂等跳过。可解析 orderId 但 owner 证据缺失/错乱时，runner 不知道应获取哪个消费者锁，因此保留源数据、计入 unsafe 并拒绝启动，禁止无锁写入 consumer 可见 quarantine。
6. 非规范十进制 suffix（包括符号、前导零、空 suffix）无法对应合法消费者消息，不删除源 status key、不猜测 owner；raw 证据写 quarantine。若隔离记录也无法持久化，则同样计入 unsafe 并拒绝启动。

已经过期且只剩 reservation 的历史孤儿无法从 status key 恢复 owner 证据，本工具不扫描大 Hash 猜测，必须人工与 MySQL/MQ 对账。

## 发布与回滚门禁

1. 关闭秒杀入口，确认不再产生新预占。
2. 等待旧消费者可处理的消息排空，同时核对 half message、普通 lag 和 DLQ；任一指标都不能单独证明无在途消息。
3. 停止所有旧实例。旧 admission 不写 processing ZSET，旧终态 Lua 也不会清理 member，因此禁止普通滚动混跑。
4. 部署新版单实例，保持 `enabled=false` 和 `compensation-enabled=false`，仅临时设置 `backfill-on-startup=true`。
5. 完成回填后检查汇总日志、quarantine、unsafe key，并抽样验证：每个合法 `PROCESSING` 都有 ZSET member 且 status `TTL=-1`；终态不在 due index。
6. 关闭 `backfill-on-startup` 并再启动，确认新 admission/终态 Lua 在写入新订单时维持索引。
7. 首先只开启 `enabled=true`、保持补偿关闭，进入“无补偿对账”观察期。该阶段仍会修复 DB exact 订单、暂停冲突活动并隔离不安全状态；观察 due backlog、oldest age、DB 分类、lock busy 和 quarantine。
8. 核对结果后才开启 `compensation-enabled=true`，从小 batch/rate 开始。恢复入口流量。

回滚到旧版前必须再次停写并排空消息。旧版不理解 processing ZSET 和无 TTL 的未决状态；不能在新版仍会产生这些数据时直接回滚。

## 禁止操作

- 禁止 `KEYS seckill:order:status:*` 或 `HGETALL seckill:reservation:{voucherId}`。
- 禁止在获取共享用户锁前按年龄直接补偿。
- 禁止 `ZPOPMIN`、先 `ZREM` 后处理，或用应用机器时钟修改截止时间。
- 禁止把 DB 超时/异常当成无单，或对 ownership mismatch 自动补偿。
- 禁止手工拆分执行 `INCR`/`HDEL`/`SREM`/写 `FAILED`。
- 禁止 reconciler 另行重发 N 次普通 MQ 消息。RocketMQ 已有有限重试；对账器在硬截止时间做最终判定。

## 验证与故障注入

最小上线门禁必须覆盖：

- admission/status/index 原子写入，成功和补偿时原子移除索引。
- 两个 reconciler 同时处理一单，库存只恢复一次。
- 消费者在 exact 校验后暂停、reconciler 同时到期，不得误补偿。
- MySQL 已提交但 `markSuccess` 故障，自动修复为 `SUCCESS`。
- DB 超时时不得释放库存；`ORDER_ID_CONFLICT` 必须暂停活动并 quarantine，永不自动释放。
- MQ 消费落库遇 DuplicateKey 时必须先按主键 owner 分类；跨 owner 的 orderId 碰撞只能暂停并 quarantine，不能降级成普通用户/券冲突补偿。
- Reconciler 的 `USER_VOUCHER_CONFLICT` 必须先暂停活动；只有 `compensation-enabled=true` 时才可 exact compensate，关闭时保留待处理。MQ consumer 在同步落库已确认该永久冲突时仍执行即时 exact compensation，这一行为不受 reconciler 开关控制。
- 补偿 Lua 完成后进程崩溃，重启后重跑不重复加库存。
- 迟到 MQ 在 `FAILED` 后被 ACK 且不写 MySQL。
- 回填面对合法、已索引、已隔离、终态、丢字段、错属、reservation mismatch，以及空白/符号/前导零等非规范 key suffix。
- 一个完整 batch 都是缺失 owner 的 canonical member 时，scheduler-only defer 后下一批合法到期订单仍可被扫描，且不得改库存、预约或业务状态。
- 压测清理后无该 voucher 的 processing index 残留；正常收敛后全部预约为 `SUCCESS` 且 index count 为 0。

压测脚本只能证明本轮样本在当前环境中的业务不变式，不代替 Broker half message/DLQ 运维检查，也不能用单轮延迟证明性能提升。
