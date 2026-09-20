# ADR 0005：M4 消费侧——批量落库、每批每券一次扣库存、用 Redis 认领取代消费锁

- 状态：**草案**。设计决策已定并已实现；正式对比数字待 `scripts/bench.sh m4` 跑完后填入
  （计划 0 节第 7 条：长测由人启动，结果交回下个会话再下结论、合并、打 tag）。
- 关联：[V2 计划 M4](../plan/v2-high-concurrency-plan.md)、[M0 基线](../../benchmark/v2/m0/baseline.md)、
  [M3 复测](../../benchmark/v2/m3/comparison.md)、[ADR 0004](0004-m3-admission-funnel.md)

## 背景

M3 把准入端从 1.3 万拉到 2 万 req/s 拐点，消费端**一点没动**：落库中位仍是 155–220 单/s
（M3 复测第 3 节）。瓶颈位置从 M0 到 M2 的火焰图一直没变：

| 占比 | 位置 | 诊断 |
| ---: | --- | --- |
| 44% | `UPDATE tb_seckill_voucher SET stock = stock - 1`，每单一次、同一行 | D4 热点行锁排队 |
| 24% | 等 Hikari 连接（连接被上面的行锁等待占住） | D4 的放大 |
| 25% | `WebSocketNotifier` 每单补查一次 merchantId（同步 DB，且在持连接时） | M0 §4.2 |
| 0.2% | Redisson 每消息锁 | 不是瓶颈，但每条消息 ≥2 次 RTT |

## 决策

### 1. 自己持有 `DefaultMQPushConsumer`

rocketmq-spring 的监听容器把一批消息**逐条**喂给 `RocketMQListener`，所以只调大
`consumeMessageBatchMaxSize` 不会让任何一段工作变成批量。M4 因此自己建消费者
（`SeckillOrderBatchConsumer`），把整批交给 `SeckillOrderBatchProcessor`。

- `consumeMessageBatchMaxSize = batch-size`（默认 64），`pullBatchSize ≥ 32`，否则批永远填不满。
- 消费线程数默认 16，Hikari 池 24：线程数不超过池，消费线程不会在等连接。
- 老的 `@RocketMQMessageListener` 开关（`rocketmq.consumer.listeners...`）对它无效，改用
  `local-deals.seckill.consume.enabled`；测试 profile 默认关闭，理由与原来一样——
  缓存的 ApplicationContext 不该去和真实 Broker 抢消息。

### 2. 一批三步：认领 → 落库 → 收尾

1. **一次 Redis 往返认领整批**（`seckill_batch_claim.lua`）：对每条消息做与
   `seckill_validate_reservation.lua` 相同的归属校验，并给可落库的那些写
   `claimOwner` + `claimExpireAt`、把到期索引的分数推后一个租约。返回每条消息一个决策。
2. **每券一条 `INSERT IGNORE` + 一次 `stock - n`**（`SeckillOrderBatchPersister`，一个事务）：
   批内按 voucher 分组，多值插入后**按实际插入行数**一次扣减 `WHERE stock >= n`，再批量写
   `order_state_log`。这一步消灭 D4。
3. **一次 Redis 往返收尾**（`seckill_batch_mark_success.lua`）：逐条置 SUCCESS、清 claim 字段、
   从到期索引移除、加上留存 TTL。

### 3. 快路径要么全成，要么整组退化

`INSERT IGNORE` 的返回值只告诉我们插了几行，不告诉我们**哪几行**被忽略；而扣库存必须用准确的
n。所以：插入行数 ≠ 组内消息数，或 `stock >= n` 不成立时，抛 `BatchPersistDegradedException`
**回滚整组**，再用 M2/M3 的单条路径逐条重放——分类、补偿、挂起券的规则一条没改，只是不再是
默认路径。正常秒杀里被忽略的行只来自重投，占比极低。

### 4. 消费锁换成 Redis 内认领

计划要求「PROCESSING → PERSISTING(owner, lease)」。实现上**没有新增状态值**，而是在原状态
Hash 上加 `claimOwner` / `claimExpireAt` 两个字段：

- 对外状态仍是 `PROCESSING`，查询接口、DTO、前端轮询的契约不变（计划没说这点，这是取舍）。
- 对账器的认领脚本读同样两个字段：别人的租约没到期就答 `NOT_DUE`，因此**不可能**在消费者
  落库过程中把同一单补偿掉。租约到期（进程崩溃）后对账器照常接手。
- 消费者与对账器都不再取 `lock:order:<userId>`，`SECKILL_ORDER_LOCK_KEY` 已删除。对账器
  自己的调度锁（`lock:seckill:reconcile:<orderId>`）保留：它只作用在到期的少量订单上。
- 认领对自己是可重入的（同 owner 直接续租），所以一批被重投时不会被自己的租约挡住。
- `ZADD ... XX`：租约只移动已存在的成员，绝不会把已收尾的订单重新放回到期索引。

### 5. 通知不再每单查库

`WebSocketNotifier` 把 voucher → merchant 记在实例内的 `ConcurrentHashMap` 里。券的归属不会变，
在售券的基数很小。这去掉的是 M0 火焰图里那 25%。

### 6. 幂等与重投的边界

一批里只要还有没办完的消息就整批 `RECONSUME_LATER`——重投是安全的（认领、`INSERT IGNORE`、
置 SUCCESS 都幂等），但确实会让同批已完成的消息再走一遍前两步。用 `setAckIndex` 只能确认一个
前缀，对乱序的完成情况没用，所以按整批处理，代价写在这里。

## 代价与边界

- 批量化把「每单一次行锁」换成「每批一次行锁」，但同一张券的并发批次仍然争同一行；真正的
  分桶留给 M5。
- 组内退化是整组回滚再逐条重放：重投多的场景下这条路径会更慢，换来的是 n 一定正确。
- 认领租约默认 30 s：消费者崩溃后，这一批要等租约过期才会被对账器接手。
- 每实例的 merchant 缓存没有失效机制：券换店铺需要重启实例（本项目里券的店铺不可改）。
- 对账器的重投速度（M3 ADR 记的 574 s 收敛）本次**没有**批量化，仍是每 10 秒 100 单。批量落库
  会缩短每单的处理时间，但重投的节奏由 `reconciliation.batch-size` / `fixed-delay` 决定，
  真正的批量重投留到 M5 或按需调参。

## 实测

### 正式结果（待填）

`scripts/bench.sh m4`：与 M0/M3 相同的分核与 JVM 参数，基线 `v2.0-m3`，同场交替 3 轮全量接收落库，
外加 `batch-size:thread-count` 的参数扫描（`1:16` 即本版本关掉批量）与两次演练
（`kill -9`、先杀 Broker）。结果目录交回后填入本节与进度表。

### 冒烟（非正式，仅证明脚本与链路可用）

`scripts/bench.sh m4-smoke` 的结果目录只用于确认 status=DONE、目录结构正确、批量路径在真实
Broker/MySQL/Redis 上跑得通，不作为任何结论的依据。2026-09-20 的冒烟里，参数扫描的两行是
`1:8` 79.3 单/s 与 `64:16` 406.6 单/s（同一份 jar，只差批量参数）；但冒烟把对账器调得很激进
（stale-after 10 s）来让演练快速收敛，落库慢的那几轮因此被重投污染（2000 单收到 3090 条消息），
所以**这些数字只能说明批量路径真的在工作**，正式结论以 `scripts/bench.sh m4` 为准
（正式场景的对账器是默认参数）。
