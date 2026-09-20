# ADR 0007：批量消费的真正约束是「批里有几条」，不是「一次提交多快」

- 状态：**部分决定**（2026-09-20）。仪表与测量已完成并入库；`pull-interval` 的取值待
  `scripts/bench.sh m6-consume` 扫描后填入，拆 MySQL 热点行的取舍在那之后再定。
- 关联：[V2 计划 M6](../plan/v2-high-concurrency-plan.md)、[ADR 0005](0005-m4-batch-consumption.md)、
  [ADR 0006](0006-m5-cluster-buckets.md)、[M4 复测](../../benchmark/v2/m4/comparison.md)、
  [M5 复测](../../benchmark/v2/m5/comparison.md)

## 背景

M4 把落库做成批量，量到「吞吐 ≈ 80 次提交/s × 实际批大小」，并推断慢轮是「批里只有一两条」。
**那是推断**：`LocalDealsMetrics` 没有批大小指标，`scripts/bench.sh` 不归档 actuator 快照，
`app.log` 每次 `app-start` 被覆盖。M4 把这两件事交给 M5，M5 没做，于是 M5 的落库 A/B
九轮全部落在同一条「双稳态」曲线上，读不出任何版本差异（M5 复测第 4 节）。

M4 复测结尾还写了一句「抬高 80 次/s 这个上限正是 M5 的库存分桶」。M5 收尾时已经更正：
那 80 次/s 是 MySQL `tb_seckill_voucher` **同一行**的代价，M5 分的是 Redis 的 key。
于是「拆那一行」成了一件不属于任何里程碑的工作，M6 开头把它接了过来。

## 决策

### 1. 先补仪表，再改任何东西

- `local_deals.seckill.consume.batch.size{stage=delivered|persisted}`：
  **delivered** 是 Broker 交给消费者的一批有几条，**persisted** 是「一次 INSERT + 一次扣库存」
  实际带走了几条。两者的差就是分组损耗。
- `local_deals.seckill.consume.degraded{reason=insert_skipped|stock_short}`：
  退化不再只有一条日志和一个共用的 FAILURE 计时器。`BatchPersistDegradedException` 带上原因，
  「重投撞上已存在的行」和「库存撑不住这一批」是两件事，分开数。
- `scripts/bench.sh`：每个测量轮结束前在应用还活着时抓一次 `/actuator/prometheus`；
  `stack.sh app-start` 把 `app.log` **轮转**而不是覆盖，整场的日志都随结果归档；
  `summary.csv` 增加 `batch_mean` 列，取值是该轮前后两次抓取的差，所以它只描述这一轮。

### 2. 实测结果：批大小约 1.03

M5 冒烟（`batchSize=64`，`threadCount=16`）：

| 阶段 | 批数 | 订单数 | **均值** | 最大 | 退化 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 阶梯 | 1139 | 1200 | **1.05** | 6 | 0 |
| 落库 | 2904 | 3000 | **1.03** | 4 | 0 |
| Redis 宕机轮 | 3814 | 4859 | **1.27** | **32** | 4 |

`persisted` 与 `delivered` 完全相等，退化计数为 0——**分组没有损耗，批本身就没攒起来**。

成因：16 个消费线程 × 一次提交约 12 ms ≈ 1300 次/s 的处理能力，而到达速率是 1000–2000/s。
消费者本来就跟得上，队列里永远没有第二条消息在等，RocketMQ 自然一次只交一条。
`consumeMessageBatchMaxSize` 是上界，不是目标。

宕机轮是反证：故障转移期间积压形成了，同一份代码的批大小立刻到 max 32。**同一个机制被看到两次。**

这同时解释了 M5 那个「双稳态」：一轮快还是慢，取决于那一轮有没有攒出积压。

### 3. 因此先扫消费参数，再决定拆不拆 MySQL 那一行

拆行抬高的是「提交速率」这一项；而实测说明当前被钉住的是「批大小」这一项。批大小为 1 时，
把一行拆成 K 行几乎买不到东西。顺序因此反过来：

1. 先把批填满——`pull-interval`（队列两次拉取之间等多久）、`pull-batch-size`、线程数。
   新增的两个配置项**默认值等于 M4 的既有行为**（0 ms / 32），不扫描就什么都不变。
2. 填满之后如果 80 次/s 重新成为约束，再拆行，届时收益是可预期的（80 × K）。

扫描场景 `scripts/bench.sh m6-consume`，组合写作 `batch:threads:pullMs:pullBatch`。

## 代价与边界

- **`pull-interval` 拿延迟换吞吐**：等待积压意味着每条消息多等最多一个 pull 间隔才落库。
  秒杀的落库是异步的（用户看到的是准入结果），所以这笔交易大概率划算，但它**是一笔交易**，
  扫描要同时看 `drain_s_after_load`，不能只看 `persist_orders_per_s`。
- **`batch_mean` 是每轮的差值**，不是瞬时分布；某一轮内部的快慢切换仍然只能从 `.prom`
  的直方图桶里看。
- **对照组基线的环境假设变了**：`v2.0-m5` 是集群构建，不能再按「基线一律跑单节点」处理，
  否则准入脚本读不到元数据、整轮 503。`S_BASELINE_BUCKETS` 现在显式表达这件事。
- **拆 MySQL 热点行的设计尚未定稿**。若将来要做，真正让它生效的前提是消费批次尽量来自
  同一个桶（否则一个事务要持有 K 个行锁，竞争更糟），也就是生产端按桶选队列——
  那是一笔比「加一张表」大得多的改动，不应该在没有数据支持时就先做。

## 实测

- `mvn test` 448/448 绿。
- 上表全部来自 `benchmark/v2/m5/20260920-185600-m5-smoke/raw/*.prom`（`status=DONE`）。
  **冒烟数据只用于定位瓶颈方向，不作为任何吞吐结论。**
- 扫描结果待 `scripts/bench.sh m6-consume` 跑完后填入本节。
