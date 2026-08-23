# 秒杀可靠性故障注入结果

## 记录方式

- 本文件只保留人工阅读总览，单轮完整证据写入 `docs/archive/redis-stream/reliability-results/<run_id>.md`。
- 机器可读总表写入 `docs/archive/redis-stream/reliability-results/metrics.csv`。
- 故障载荷固定为向 `stream.orders` 注入缺少 `orderId` 的异常消息：`userId=<fault_user_id>, voucherId=<voucher_id>`。

## 为什么要做故障注入

正常压测只能证明“常规请求能不能正确下单”。但真实异步系统还要考虑异常消息：字段缺失、生产者版本不一致、手工修复误写、历史脏数据、消费者反序列化失败等。Redis Stream 消费者如果没有失败闭环，坏消息可能一直卡在 pending-list 中，既没有真正处理成功，也没有进入可排查的失败归宿。

本测试故意向 `stream.orders` 写入一条缺少 `orderId` 的异常消息。这个消息不是正常业务请求产生的订单，而是用来模拟“消费者无法解析或无法落库的毒丸消息”。可靠性差异就看这条坏消息最后在哪里：

- 如果一直留在 `pending`，说明消费者读到了但没有处理完成，也没有明确失败归宿。
- 如果进入 `dead-letter`，说明系统能在有限重试后承认失败，把消息转存到死信 Stream，后续可以告警、排查或人工补偿。

## 字段解释

| 字段 | 含义 | 怎么判断 |
| --- | --- | --- |
| `预期类型` | 本轮用来验证哪种行为。`baseline` 表示预期复现原版缺陷，`current` 表示预期验证改造后的闭环能力。 | baseline 行的 `pass` 不是说原版可靠，而是说“原版 pending 残留这个问题被成功复现”。 |
| `voucher_id` | 本轮压测或故障注入使用的秒杀券 ID。 | 只用于定位测试数据，不代表业务结论。 |
| `injected_record_id` | Redis Stream 给这条异常消息分配的消息 ID。 | 后续排查 dead-letter 时可以用它追溯原始消息。 |
| `pending` | 消费者已经读到但没有 ACK 的消息数量。 | 对异常消息来说，baseline 为 1 表示坏消息卡住；current 为 0 表示坏消息已经被闭环处理。 |
| `dead-letter` | 死信 Stream 中的消息数量。 | current 为 1 表示异常消息被转存到 `stream.orders.dlq`；baseline 为 0 表示没有失败归宿。 |
| `retry_key_count` | Redis 中临时重试计数 key 的数量。 | current 最终为 0，说明消息进入 DLQ 后清理了重试计数；如果残留很多，说明重试状态本身也可能泄漏。 |
| `结论` | 脚本判断本轮是否符合预期行为。 | baseline 的 `pass` 表示缺陷复现成功；current 的 `pass` 表示改造闭环验证成功。 |

## 结果总览

| 日期 | 实现版本 | 预期类型 | voucher_id | injected_record_id | pending | dead-letter | retry_key_count | 结论 | 来源 |
| --- | --- | --- | ---: | --- | ---: | ---: | ---: | --- | --- |
| 2026-05-20 | baseline-lua-stream | baseline | 11 | 1779241499797-0 | 1 | 0 | 0 | pass | [run-summary](reliability-results/2026-05-20-seckill-baseline-lua-stream-fault-injection-r1.md) |
| 2026-05-20 | reliable-stream-v1 | current | 10 | 1779243827418-0 | 0 | 1 | 0 | pass | [run-summary](reliability-results/2026-05-20-seckill-reliable-stream-v1-fault-injection-r1.md) |

## 原版不可靠在哪里

原版并不是“正常秒杀一定会超卖”。在正常请求路径里，Redis Lua 已经能原子判断库存和一人一单，所以正常压测中 baseline 也可以通过。原版的问题在于：异步消费者遇到无法处理的异常消息时，缺少最大重试次数、死信队列和可观测信息，失败消息没有明确归宿。

本轮注入的坏消息缺少 `orderId`。baseline 消费者读到后无法构造完整订单，最终表现为 `pending=1`、`dead-letter=0`。这意味着消息已经被投递给消费者，但既没有成功 ACK，也没有被转存到死信队列。可能引发的问题包括：

- pending-list 长期残留，运维侧只能看到“有消息没处理完”，但不知道是临时积压还是毒丸消息。
- 如果异常消息越来越多，pending 会持续堆积，后续人工排查成本上升。
- 没有 dead-letter 样例，无法直接看到原消息 ID、异常类型、错误信息和原始 payload，问题复盘困难。
- 如果这类消息代表真实订单落库失败，系统缺少后续补偿入口，可能出现“用户以为抢到券，但订单没有最终落库”的风险。
- 处理方式依赖人工 `XACK` 或删除消息，容易在修复时丢失现场证据，也不利于自动告警。

`reliable-stream-v1` 的改造点是给失败消息一个有限生命周期：处理失败后记录重试次数，达到上限后写入 `stream.orders.dlq`，再 ACK 原消息并清理重试 key。所以结果变成 `pending=0`、`dead-letter=1`、`retry_key_count=0`。这不代表系统自动修好了所有业务问题，但至少把“卡住且不可见的失败”变成了“有归宿、可排查、可补偿的失败”。

## 当前观察

- baseline 版本没有异常消息闭环能力：缺少 `orderId` 的消息被消费者读到后残留在 pending-list，`stream.orders.dlq` 为空。
- `reliable-stream-v1` 经过有限重试后将异常消息转存到 `stream.orders.dlq`，pending-list 清空。
- current 版本的 dead-letter 样例包含 `sourceStream=stream.orders`、`sourceGroup=g1`、`sourceRecordId=1779243827418-0`、`retries=3`、`errorClass=java.lang.IllegalArgumentException` 和 `errorMessage=Missing stream field: orderId`。
- 这组结果适合支撑“补齐 Redis Stream 异常消费闭环和可观测证据”，不应包装成吞吐性能提升。

## 对比结论

| 对比项 | baseline | reliable-stream-v1 | 优势 |
| --- | --- | --- | --- |
| 异常消息处理 | pending 残留 1 条，DLQ 为空 | pending 清空，DLQ 生成 1 条 | 失败消息有明确归宿 |
| 重试边界 | 缺少可证明的最大重试闭环 | dead-letter 样例记录 `retries=3` | 避免异常消息无限卡在 pending |
| 排障信息 | 只能看到 pending 残留 | DLQ 中保留来源 Stream、消费组、原消息 ID、异常类型和错误信息 | 问题可追踪、可复盘 |

面试或 README 中可以这样解释：原版在正常秒杀下能防超卖，但异步消费失败时只有 pending-list，没有明确的失败终态；当前版本补上最大重试、死信 Stream 和排障字段，把异常消息从“卡住”变成“可观测、可追踪、可后续补偿”。
