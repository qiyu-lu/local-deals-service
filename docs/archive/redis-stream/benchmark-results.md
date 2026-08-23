# 压测结果记录

## 记录方式

- 本文件只保留压测总览，不直接粘贴完整 JMeter 输出。
- 后续新增压测优先只向 `结果总览` 追加一行，不再为每一轮压测复制大段详情。
- 每轮完整来源写入 `docs/archive/redis-stream/JmeterTestSummary/<场景名>/<run_id>-run-summary.md`。
- 机器可读总表写入 `docs/archive/redis-stream/JmeterTestSummary/<场景名>/metrics.csv`。
- JMeter 导出的 Summary / Aggregate CSV 作为派生证据保存在 `docs/archive/redis-stream/JmeterTestSummary/<场景名>/` 下。

基线版本执行命令：

```bash
scripts/run-seckill-benchmark.sh --threads 100 --loops 1 --stock 100 --user-count 1000 --voucher-id 11
scripts/run-seckill-benchmark.sh --threads 1000 --loops 1 --stock 1000 --user-count 2000 --voucher-id 11
scripts/run-seckill-benchmark.sh --threads 5000 --loops 1 --stock 1000 --user-count 5000 --voucher-id 11
scripts/run-seckill-benchmark.sh --threads 5000 --loops 5 --stock 1000 --user-count 5000 --voucher-id 11
```

可靠性增强版执行命令：

```bash
scripts/run-seckill-benchmark.sh --threads 100 --loops 1 --stock 100 --user-count 1000
scripts/run-seckill-benchmark.sh --threads 1000 --loops 1 --stock 1000 --user-count 2000
scripts/run-seckill-benchmark.sh --threads 5000 --loops 1 --stock 1000 --user-count 5000
scripts/run-seckill-benchmark.sh --threads 5000 --loops 5 --stock 1000 --user-count 5000
```

当前本机仍复用旧 Docker 容器 `hmdp-mysql` / `hmdp-redis`。本轮对比中，baseline 连接 `hmdp` 库并使用 `voucher_id=11`，`reliable-stream-v1` 连接 `local_deals` 库并使用压测工具自动复用的 `voucher_id=10`。

## 如何阅读结果总览

这张表验证的是“正常秒杀链路”而不是故障场景。正常链路下，原版 baseline 本身已经可以依靠 Redis Lua 保证不超卖、不重复下单，所以这里不能用来证明新版性能明显更强。它的主要作用是确认：当前版本在加入 DB 唯一索引、pending 重试上限、dead-letter 和指标后，没有破坏原本正常链路的正确性，也没有明显拖慢异步落库追平。

| 字段 | 含义 | 怎么判断 |
| --- | --- | --- |
| `Throughput(samples/sec)` | JMeter 每秒完成的 HTTP 请求数。这里包含抢购成功、库存不足、重复下单等响应。 | 只能作为压力参考，不能直接等同于“每秒成功下单数”。库存耗尽后大量请求会快速失败，吞吐可能看起来更高。 |
| `P95/P99(ms)` | HTTP 响应时间的尾部延迟。P95 表示 95% 请求不超过该耗时，P99 表示 99% 请求不超过该耗时。 | 受本机 CPU、JVM、JMeter 调度影响很大，单轮波动不能直接当作性能优势。 |
| `drain_ms` | JMeter 请求结束后，到 MySQL 订单数达到预期且 Redis Stream pending 清空之间的等待时间。 | 这是异步消费者追平消息积压的时间。当前版本和 baseline 同量级，说明可靠性增强没有明显造成消费堆积。 |
| `订单/预期` | MySQL 中实际落库订单数 / 本轮按库存和用户数计算出的预期订单数。 | 必须相等。少了说明消息丢失或未消费完，多了说明可能超卖或重复下单。 |
| `pending` | Redis Stream 中已经投递给消费者、但还没有被 ACK 确认完成的消息数。 | 正常压测结束后应为 0。非 0 表示还有消息卡在消费者侧，需要结合故障注入进一步定位。 |
| `dead-letter` | 进入死信 Stream 的消息数。死信表示消息多次处理失败后被转存，等待排查或补偿。 | 正常压测应为 0。如果正常压测出现死信，说明存在无法处理的异常订单消息。 |
| `结论` | 脚本根据 HTTP、MySQL、Redis、Stream 共同校验出的结果。 | `pass` 只表示业务正确性通过，不表示性能大幅提升。 |

从这张表能得出的可靠性结论是：`reliable-stream-v1` 在正常高并发秒杀下仍然做到订单数正确、无重复下单、pending 清空、dead-letter 为空，并且 `drain_ms` 没有明显变差。真正体现“原版不可靠在哪里”的证据在下方故障注入文档：`docs/archive/redis-stream/reliability-results.md`。

## 结果总览

| 日期 | 实现版本 | 场景 | 请求配置 | 库存 | 请求数 | Throughput(samples/sec) | P95/P99(ms) | drain_ms | 订单/预期 | pending | dead-letter | 结论 | 来源 |
| --- | --- | --- | --- | ---: | ---: | ---: | --- | ---: | --- | ---: | ---: | --- | --- |
| 2026-05-16 | baseline-lua-stream | seckill-baseline-lua-stream | 100 线程 / 1 次循环 | 100 | 100 | 20.49180 | 2 / 17 | 76 | 100 / 100 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-baseline-lua-stream/2026-05-16-seckill-baseline-lua-stream-100t-1l-r1-run-summary.md) |
| 2026-05-16 | baseline-lua-stream | seckill-baseline-lua-stream | 1000 线程 / 1 次循环 | 1000 | 1000 | 205.88841 | 2 / 22 | 81 | 1000 / 1000 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-baseline-lua-stream/2026-05-16-seckill-baseline-lua-stream-1000t-1l-r1-run-summary.md) |
| 2026-05-16 | baseline-lua-stream | seckill-baseline-lua-stream | 5000 线程 / 1 次循环 | 1000 | 5000 | 1153.40254 | 155 / 1100 | 78 | 1000 / 1000 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-baseline-lua-stream/2026-05-16-seckill-baseline-lua-stream-5000t-1l-r1-run-summary.md) |
| 2026-05-16 | baseline-lua-stream | seckill-baseline-lua-stream | 5000 线程 / 5 次循环 | 1000 | 25000 | 5120.85211 | 16 / 43 | 75 | 1000 / 1000 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-baseline-lua-stream/2026-05-16-seckill-baseline-lua-stream-5000t-5l-r1-run-summary.md) |
| 2026-05-19 | reliable-stream-v1 | seckill-reliable-v1 | 100 线程 / 1 次循环 | 100 | 100 | 20.53388 | 3 / 19 | 73 | 100 / 100 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-100t-1l-r3-run-summary.md) |
| 2026-05-19 | reliable-stream-v1 | seckill-reliable-v1 | 1000 线程 / 1 次循环 | 1000 | 1000 | 208.33333 | 2 / 29 | 72 | 1000 / 1000 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-1000t-1l-r1-run-summary.md) |
| 2026-05-19 | reliable-stream-v1 | seckill-reliable-v1 | 5000 线程 / 1 次循环 | 1000 | 5000 | 1051.96718 | 42 / 1116 | 80 | 1000 / 1000 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-5000t-1l-r1-run-summary.md) |
| 2026-05-19 | reliable-stream-v1 | seckill-reliable-v1 | 5000 线程 / 5 次循环 | 1000 | 25000 | 5179.20033 | 61 / 100 | 76 | 1000 / 1000 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-5000t-5l-r1-run-summary.md) |
| 2026-05-20 | baseline-lua-stream | seckill-baseline-lua-stream | 1000 线程 / 1 次循环 | 1000 | 1000 | 207.25389 | 2 / 36 | 73 | 1000 / 1000 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-baseline-lua-stream/2026-05-20-seckill-baseline-lua-stream-1000t-1l-r3-run-summary.md) |
| 2026-05-20 | baseline-lua-stream | seckill-baseline-lua-stream | 5000 线程 / 1 次循环 | 1000 | 5000 | 1055.07491 | 107 / 151 | 71 | 1000 / 1000 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-baseline-lua-stream/2026-05-20-seckill-baseline-lua-stream-5000t-1l-r1-run-summary.md) |
| 2026-05-20 | reliable-stream-v1 | seckill-reliable-v1 | 1000 线程 / 1 次循环 | 1000 | 1000 | 205.76132 | 50 / 177 | 72 | 1000 / 1000 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-reliable-v1/2026-05-20-seckill-reliable-stream-v1-1000t-1l-r1-run-summary.md) |
| 2026-05-20 | reliable-stream-v1 | seckill-reliable-v1 | 5000 线程 / 1 次循环 | 1000 | 5000 | 1040.79933 | 27 / 100 | 81 | 1000 / 1000 | 0 | 0 | pass | [run-summary](JmeterTestSummary/seckill-reliable-v1/2026-05-20-seckill-reliable-stream-v1-5000t-1l-r1-run-summary.md) |

## 当前观察

- 2026-05-20 对比验证中，baseline 和 `reliable-stream-v1` 的 1000/5000 线程正常压测均通过：订单数达到预期，未超卖，未重复下单，Redis Stream pending-list 清空，dead-letter 为空。
- `reliable-stream-v1` 在 2026-05-20 的 `drain_ms` 为 72-81 ms，和本轮 baseline 的 71-73 ms 处在同一量级，说明增加 DB 唯一索引兜底、pending 重试上限、dead-letter 和指标后，没有明显拉长异步落库追平时间。
- `5000 线程 / 1 次循环` 的 P95/P99 波动受机器状态影响较大：本轮 baseline 为 107 / 151 ms，`reliable-stream-v1` 为 27 / 100 ms；旧一轮 current 曾出现 P99 1116 ms。该场景适合作为压力尖峰观察，不应只用单轮 P99 证明性能提升。
- `5000 线程 / 5 次循环` 的吞吐最高，主要因为库存耗尽后大量请求走库存不足/重复下单的快速拒绝路径，不能简单理解为完整下单能力提升。
- 2026-05-20 baseline 的 `1000t-1l-r1` 和 `1000t-1l-r2` 因旧 JMX 硬编码 token 路径导致 2 个 401、订单 998/1000，已标记为废弃证据，不纳入对比结论。
- 本轮对外表述重点应放在“可靠性和可观测性增强后，业务正确性仍通过，异步追平耗时保持稳定”，不要夸大为纯性能优化。

## 对比结论

| 对比项 | baseline | reliable-stream-v1 | 结论 |
| --- | --- | --- | --- |
| 1000 线程正常压测 | 订单 1000/1000，pending 0，DLQ 0，`drain_ms=73` | 订单 1000/1000，pending 0，DLQ 0，`drain_ms=72` | 正常链路正确性不退化 |
| 5000 线程正常压测 | 订单 1000/1000，pending 0，DLQ 0，`drain_ms=71` | 订单 1000/1000，pending 0，DLQ 0，`drain_ms=81` | 高并发尖峰下仍保持一致性 |
| 性能表述边界 | P95/P99 受机器状态和单轮波动影响 | 没有稳定证明吞吐或 P99 大幅领先 | 不主张“性能大幅提升” |
| 工程优势入口 | 需要结合故障注入结果判断 | 详见 `docs/archive/redis-stream/reliability-results.md` | 优势主要是可靠性闭环和可观测性 |

简单理解：正常压测表证明“新版加了可靠性机制之后没有把原来能跑通的秒杀链路搞坏”。如果只看这张表，baseline 和 current 都是 pass，所以不要把它讲成性能优化。项目真正比教程版更强的地方，是异常消息出现后 current 能有限重试、进入死信、保留排障信息，而 baseline 会把异常消息留在 pending-list 里。

## 后续记录方式

后续新增压测时，推荐流程：

1. 使用 `scripts/run-seckill-benchmark.sh` 执行压测。
2. 从脚本输出的 `run-summary.md` 中复制 `Markdown Row`。
3. 将该行追加到本文档的 `结果总览`。
4. 详细参数、JMeter 派生 CSV、JTL、HTML 报告和 Docker 校验结果以 `run-summary.md` 为准。

脚本同时会维护机器可读总表：

```text
docs/archive/redis-stream/JmeterTestSummary/<场景名>/metrics.csv
```
