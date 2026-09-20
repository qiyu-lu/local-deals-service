# ADR 0005：M4 消费侧——批量落库、每批每券一次扣库存、用 Redis 认领取代消费锁

- 状态：**已接受**，实测已回填（见「实测」一节）。计划里的待验证目标「落库 ≥ 3000 单/s」
  **未达成**：中位 319、最好 1056 单/s；未达成的原因已定位到「提交速率约 80 次/s × 实际批大小」，
  记在下面与 M4 复测第 4 节。
- 关联：[V2 计划 M4](../plan/v2-high-concurrency-plan.md)、[M0 基线](../../benchmark/v2/m0/baseline.md)、
  [M3 复测](../../benchmark/v2/m3/comparison.md)、[M4 复测](../../benchmark/v2/m4/comparison.md)、
  [ADR 0004](0004-m3-admission-funnel.md)

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

`scripts/bench.sh m4`（2026-09-20 14:32–15:18，`status=DONE`，commit `cc1ab1a`，基线 `v2.0-m3`
= `7a2ac04`）。完整表格与曲线分析见 [M4 复测](../../benchmark/v2/m4/comparison.md)；`mvn test`
429/429 绿。

| 指标 | `v2.0-m3` | M4 | 说明 |
| --- | --- | --- | --- |
| 落库速率（2 万单 × 3 轮，中位） | 156.3 单/s | **319.1 单/s** | 最好一轮 945.2，最差 294.1 |
| 落库速率（参数扫描最优 `32:16`） | — | **1056.2 单/s** | 默认 `64:16` 为 975.3 |
| 同一版本关掉批量（`1:16`） | — | 168.7 单/s | 与基线同档，**证明提速只来自批量** |
| 负载结束后的排空时间 | 105.6 s | 47.7 s（最好 10.7 s） | |
| 阶梯 1000 单档落库 | 144.8–172.9 单/s | **691.5–703.1 单/s** | 没有 2 万单的长尾 |
| 准入端（10k / 20k 档） | — | 无回退 | 吞吐、CPU 持平，half message 恒 0，`accepted = stock` |
| `kill -9` 收敛 | 102.8 / 102.7 s | **44.7 s** | 预占 = 订单，Redis 库存 = DB 库存，无残留 PROCESSING |
| 先杀 Broker 收敛 | 574.2 s | 572.1 s | 对账器重投未批量化，如本文「代价与边界」所料 |

### 目标没达到，瓶颈在哪

把落库采样拆成每秒速率后，三个版本只出现三种状态：约 80 单/s、约 450 单/s、2000–9200 单/s。
关掉批量的 `1:16` 整轮稳定在 81 单/s，基线 p50 是 84 单/s——**一次「插入 + 扣库存」提交的速率
上限约 80 次/s**（同一行 `UPDATE` 的行锁 + 提交）。批量峰值 2257–9198 单/s ≈ 80 × 30–110。
即：**吞吐 ≈ 80 × 实际批大小**，批量没有让提交更快，只是让一次提交带走整批。

三轮之间差 2.9 倍，是因为实际批大小不同；为什么在负载期间批攒不满（RocketMQ 每队列流控，
还是到达节奏），**这次没有证据**：`LocalDealsMetrics` 缺批大小的分布指标，`scripts/bench.sh`
也没把每轮的 `app.log` 与 actuator 指标归档。要补的观测写在「后续」。

线程数的结论是反直觉但一致的：16 线程（860–904 单/s）明显好过 32 线程（319–337 单/s），
因为多开的线程只是更多人去抢同一行的行锁；批大小 32 与 64 没差别，256 更差。默认 `64:16` 保留。

### 后续

- 要过 3000 单/s，两条路叠加：把批填满（80 × 40 = 3200），以及把「80 次/s」的上限本身抬上去
  ——后者就是 **M5 的库存分桶**，K 个桶 = K 行，每行各自约 80 次/s。本文「代价与边界」里
  「同一张券的并发批次仍然争同一行」这一条，现在有了数字。
- 需要补的观测（不改行为）：批大小的 `DistributionSummary`（照 `blog.like.outbox.events` 的做法）、
  退化计数，以及让 `scripts/bench.sh` 每阶段归档 `app.log` 与一次 actuator 指标快照。

### 冒烟（非正式，只证明脚本与链路可用，不证明快慢）

`scripts/bench.sh m4-smoke` 用来确认 status=DONE、目录结构正确、批量路径在真实
Broker/MySQL/Redis 上跑得通，以及演练的一致性结论成立（2026-09-20 的最后一次：先杀 Broker，
3000 单里 2304 个无消息预占全部重投，预占=订单、Redis 库存=DB 库存、无残留 PROCESSING，
133.8 s 收敛）。

**落库速率在冒烟里测不出来**：冒烟一轮只有 2000 单，同一场次内同样的参数会给出
406.6 与 82.8 单/s，关掉批量的 `1:8` 是 79.3 / 77.8，基线在两场里是 79.4 与 407.4。
这台机器上跑着别的项目的容器，2000 单的一轮完全被噪声主导。正式结论一律以
`scripts/bench.sh m4` 的结果为准。
