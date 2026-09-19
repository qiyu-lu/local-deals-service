# ADR 0003：M2 订单闭环——状态机、定时关单、签名回调、券资产与核销

- 状态：已采纳（2026-09-19，tag `v2.0-m2`）
- 关联：[V2 计划 M2](../plan/v2-high-concurrency-plan.md)、[M2 落库同场复测](../../benchmark/v2/m2/comparison.md)

## 背景

M1 结束时，订单一建成就是终点：`tb_voucher_order.status/pay_time/refund_time` 从来没人写，
唯一键 `uk(user_id, voucher_id)` 让取消后的用户永远不能再买（D6）。营销 Grant 只记录“发过”，
发出去的券没有有效期、不能核销，而且和秒杀订单是两套互不相干的“券”。面试里的
“抢到不付款怎么办”“支付回调和关单撞了怎么办”在代码里都没有答案。

## 备选与决策

### 1. 状态流转：集中的 CAS，而不是乐观锁版本号或分布式锁

- 备选：a) 读出订单、改状态、`version` 乐观锁写回；b) Redisson 按订单加锁；
  c) **一个事件只有一个源状态，流转就是 `UPDATE ... SET status=to WHERE order_no=? AND status=from`**。
- 决策：c。`OrderEvent` 枚举即合法图（PAY、CLOSE、VERIFY、REFUND_APPLY、REFUND_SUCCESS 五条边），
  `OrderStateMachine.fire` 是唯一调用 `TradeOrderMapper.transition` 的地方（源码扫描测试守护）；
  `status` 字段连 setter 都没有。影响 0 行即为并发失败方，InnoDB 行锁天然串行化竞争者，
  不需要额外的锁服务。状态日志在同一事务写入（`Propagation.MANDATORY`）。
- 关单另加 `expire_at <= NOW(3)`：截止时间由数据库时钟计算、由数据库时钟判断，应用与
  Broker 的时钟偏差只会让定时消息“早到一点”，此时 CAS 失败、消息重投。

### 2. 限购唯一键：生成列 `active_flag`

`active_flag = IF(status IN ('CLOSED','REFUNDED'), NULL, 1)`，`uk(user_id, voucher_id, active_flag)`。
MySQL 唯一索引允许多个 NULL，所以进行中的订单仍然唯一，关闭或退款后同一用户可再买。
Redis 侧的“判重”同样要放开：释放 Lua 删除该用户的预占并归还 1 个库存单位。

### 3. 超时关单：RocketMQ 5 定时消息 + 兜底扫描

- 备选：a) 只靠定时扫表；b) 4.x 的 18 级延迟消息；c) **5.x 任意时刻定时消息 + 扫表兜底**。
- 决策：c。Broker 升到 5.3.2（`timerWheelEnable`）。消费者在订单落库后投递 `expire_at + 1s`
  的定时消息，失败只记日志；`OrderTimeoutScanner` 每 10 秒扫
  `idx(status, expire_at)` 中已超时 30 秒的订单兜底。两条路径并发也安全：只有 CAS 胜者回补库存。
- 库存回补分两半：DB `stock+1` 与 CAS 同事务；Redis 回补在提交后执行，未完成的由
  `release_pending` 标记，扫描器重试。释放 Lua 不需要幂等标记：预占哈希里该用户指向这个
  订单号时才释放，释放后即删除，用户再次下单会写入新的订单号，所以重放必然找不到可释放的东西。
- 现有 `seckill_compensate.lua` 只处理 `PROCESSING` 状态，已落库（`SUCCESS`）的订单要新写
  `seckill_release.lua`，这是计划里“复用精确补偿 Lua 思路”的实际落点。

### 4. 支付：签名回调，按支付单行锁幂等；关单赢了就自动退款

- mock 渠道按真实渠道的语义实现：HMAC-SHA256 签名（排序字段、常量时间比较）、每条回调至少
  投递两次、非 2xx 指数退避重试、随机抖动导致乱序，退款按 `refund_no` 幂等。
- 回调处理锁 `payment_record` 行：重复回调排队后看到 `SUCCESS` 直接应答（`channel_txn_no` 唯一）；
  金额不符记 `ABNORMAL`，永不入账。随后订单 CAS `PENDING_PAY→PAID`：胜则同事务发券；
  败则说明关单（或另一笔支付）先到——钱已经在我们手里，同事务生成 `AUTO` 退款单
  （`RA<pay_no>`），提交后向渠道请求退款。
- 单号全部由来源确定：`pay_no=<order_no>-<序号>`、`refund_no=RU<order_no>`/`RA<pay_no>`、
  `coupon_no=P<order_no>`/`G<grant_id>`。确定性单号就是幂等键，不需要额外的发号服务。

### 5. 券资产统一与核销

- `user_coupon` 是唯一的券资产；`tb_voucher_grant` 保留为发放流水（幂等、额度）。支付成功与
  `VoucherGrantTransactionService` 各自在事务内发券——后者一处改动覆盖领取、后台发放、
  签到奖励、批量发放四个入口。有效期按新增的 `tb_voucher.valid_days` 从发券时刻起算
  （计划没说有效期从哪来，这是本次补的决定）。
- 核销码 16 位 Crockford base32（80 bit 随机）；陌生码和他人商户的码返回同一个“券码无效”，
  不给跨商户枚举留信号；每商户每分钟 120 次尝试上限（Redis 固定窗口，Redis 故障时放行，
  不能让门店停业）。
- **锁顺序**：核销和退款都先锁订单行、再动券行。核销 `PAID→USED` + 券 `AVAILABLE→USED`；
  退款 `PAID→REFUNDING` + 券 `→FROZEN`。两者任一先提交，另一方的 CAS 失败，没有死锁。
  失败方重读券状态用 `FOR UPDATE`：在 REPEATABLE READ 下普通读会读到事务快照里的
  `AVAILABLE`，把“已核销”误报成“已过期”——这是写测试时抓到的。
- 退款成功同样归还库存单位并允许再次购买（与关单共用 `returnUnit`）。

### 6. 后台审计：一个切面覆盖所有写接口

计划要求“一张表 + 一个切面”。切面挂在 `@RequireAdminPermission` 上，**所有非 GET 的后台接口
自动记录**（账号、商户、`<方法> <路由模板>`、路径变量、结果、错误码、IP），新接口不会漏；
`@AdminAudit` 只用来改名和取目标（核销记录券号，不记录核销码）。审计行独立事务写入，
写失败只记日志——业务已经发生，不能再对操作员报错。

### 7. 旧表迁移：搬过去，而不是删掉

计划的硬规则 2 允许直接删旧数据，但计划同时要求“迁移脚本 + 删除旧表”，而直接 `DROP` 会让
开发库里的旧订单无法找回。V14 把 `tb_voucher_order` 全部搬成 `PAID`（V1 没有支付环节，建单即
成交），每单补一张券，再删旧表。隔离栈实测：142 000 单、142 000 张券。

## 结果

- 计划要求的 5 类并发 IT 全绿，并且每一类都实际跑到了两种交错：

  | 竞态 | 测试 | 结果 |
  | --- | --- | --- |
  | 1 支付回调 vs 超时关单 | `PaymentRefundIT` 20 轮 | 支付胜 5 / 关单胜 15，每轮恰一方胜；关单胜时自动退款 1 笔、库存回补 1 次 |
  | 2 回调重复 10 次 | `PaymentRefundIT` | 1 PAID + 9 DUPLICATE，1 张券，1 条 PAY 日志 |
  | 3 关单消息重复 | `OrderCloseIT` 10 并发 + 1 重投 | 1 CLOSED，MySQL 与 Redis 库存各回补 1 次 |
  | 4 退款中再退款 / 核销后退款 / 核销 vs 退款 | `PaymentRefundIT` | 10 并发申请只生成 1 笔退款；已核销拒退；核销胜 3 / 退款胜 7，无双赢 |
  | 5 关单后再买 | `TradeOrderPersistenceIT`、`OrderCloseIT` | 生成列唯一键放行；经 Redis 准入端到端再下单成功 |
- 起初两个竞态循环里一方总是赢（关单 20/20、退款 10/10），只验证了一半交错；改为部分轮次给
  一方 20 ms 先手，并断言两种结果都出现，连续 3 次运行结果一致。
- 真实 Broker：`OrderCloseTimerIT` 3 秒超时的订单由定时消息关闭；HTTP 端到端冒烟（真实应用 +
  mock 渠道 HTTP 回调）走通 下单→支付→核销→审计、下单→支付→退款、未支付 20 秒后 23 秒内关单→
  同一用户再买，结束时 MySQL 与 Redis 库存一致（均为 3）。
- `mvn test` 356/356；隔离栈 `*IT` 72 个执行全绿（51 个仍在旧的 M5/M6 门控后跳过）；
  把门控环境变量指向隔离栈后，营销业务 IT 17/17，且之后每条 `tb_voucher_grant` 都有对应的券；
  按 CI 的 Testcontainers 方式（全新 MySQL 从 V1 迁到 V15）本地跑集成任务：63 个执行全绿。
- 落库速率同场 A/B（`drain 20000 2000`，交替 5 轮）：`v2.0-m1` 中位 189.4 单/s（151–271），M2 中位
  177.5 单/s（139–427），区间大幅重叠，测不出差别。wall 采样显示 M2 新增的联表插入、状态日志、
  定时消息投递合计不到消费线程时间的 2%；44% 花在同一行库存的 `UPDATE`，23% 在等连接池，
  25% 在通知补查商户——仍是 D4，留给 M4。

## 代价

- **每单落库多做了事**：`INSERT ... SELECT` 联表快照价格/商户、一行状态日志、一次同步的
  定时消息投递。同场复测（[comparison.md](../../benchmark/v2/m2/comparison.md)）测不出回退，但这是
  因为瓶颈在别处：M4 消掉热点行之后，这 2% 会按比例变大。定时消息目前在消费线程里逐单同步投递，
  M4 批量消费时应改为批量投递。
- **Broker 换成了 5.3.2**：M3 与 M0 基线对比准入时，Broker 版本是一个额外变量，要在同场对照里
  消掉（对照 jar 也跑在 5.3.2 上）。
- **mock 渠道状态在内存里**：多实例下，用户在 A 实例预下单、在 B 实例“付款”会找不到支付单。
  真实渠道没有这个问题；本项目的多实例验证（M8）需要把 mock 渠道固定到一个实例或放进 Redis。
- **回调密钥有开发默认值**（`local-dev-mock-channel-secret`），生产必须用环境变量覆盖。
- **过期未用的已付订单保持 `PAID`**：券变 `EXPIRED`，订单状态机没有“过期”这一态；过期券允许
  退款（冻结时接受 `EXPIRED`），“过期自动退”没有做。
- **退款回补库存是一个业务选择**：活动已结束时回补的库存不会再被卖出，无害；若要“退款不回补”
  只需把 `REFUND_SUCCESS` 的 `releasesInventory` 改为 false。
- **后台订单列表直接查 `trade_order.merchant_id`**：M6 按用户分片后这条查询会变成广播，届时
  改由 CDC → ES 读模型承担。
- **没有做前端**：用户端的支付/退款/券包、管理端的核销与审计页面仍待补；接口已齐。
- **遗留的 IT 门控**：51 个测试方法仍依赖 `M5*/M6*_ISOLATED` 等门控变量，其中 Flyway 升级和
  故障注入类（`M6*FlywayIT`、`M6aRedisAuthFailureIT` 等）需要额外的独立实例，本次没有跑。
