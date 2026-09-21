# 文档索引

V2 以代码为准；V1 的过程文档、执行证据和学习笔记已从主干删除，完整保留在 tag `v1-final`
（`git show v1-final:docs/<path>`）。V1 的设计文档删于 M9，在 tag `v2.0-m8` 里可查
（`git show v2.0-m8:docs/design/<file>`）。

## 主干

| 文档 | 说明 |
| --- | --- |
| [压测报告](benchmark-report.md) | 怎么测的、每个里程碑数字怎么动的、哪些话不能说 |
| [运行手册](runbook.md) | 开发栈、Quick Start、隔离栈与集成测试、压测场景、故障演练、按 traceId 排障 |
| [V2 重构计划与进度](plan/v2-high-concurrency-plan.md) | 唯一执行入口：现状诊断、里程碑、进度表（历史记录，不再改正文） |
| [M9 包装计划](plan/m9-packaging-plan.md) | 改名、文档收口、学习笔记、面试问答的分会话计划 |
| [学习笔记](notes/00-overview.md) | 十三章：每条链路的详细流程与口述版，面试复习用；术语表在 00 |
| [项目深挖问答](interview/project-deep-dive.md) | 114 题：面试官视角的追问与基于真实实现和真实数字的回答 |

## ADR：一个里程碑一页

| # | 决策 |
| --- | --- |
| [0001](adr/0001-m0-cleanup-and-baseline.md) | M0：瘦身与基线，压测工具链 |
| [0002](adr/0002-m1-java21-boot3.md) | M1：Java 21 / Spring Boot 3.5 / ES 8 |
| [0003](adr/0003-m2-order-lifecycle.md) | M2：订单状态机、支付与退款、券资产与核销 |
| [0004](adr/0004-m3-admission-funnel.md) | M3：三层准入漏斗与单次 Lua |
| [0005](adr/0005-m4-batch-consumption.md) | M4：自建批量消费者 |
| [0006](adr/0006-m5-cluster-buckets.md) | M5：Redis Cluster、hash tag 分桶与故障演练 |
| [0007](adr/0007-m6-batch-fill.md) | M6：消费参数扫描；为什么不拆库存热点行 |
| [0008](adr/0008-m6-order-sharding.md) | M6：订单分 2 库 × 4 表，基因法与片内唯一键 |
| [0009](adr/0009-m8-multi-instance.md) | M8：多实例、nginx、traceId 三跳、workerId 缺陷 |

## 压测证据

汇总结论在[压测报告](benchmark-report.md)；每次运行的原始记录在
`benchmark/v2/m<n>/<时间戳>-<场景>/`（`status` → `manifest.json` → `summary.csv`）。

| 文档 | 说明 |
| --- | --- |
| [M0 基线与瓶颈分析](../benchmark/v2/m0/baseline.md) | 开环阶梯、落库速率、火焰图结论（对照组） |
| [M1 同场复测](../benchmark/v2/m1/comparison.md) | Java 21 / Boot 3.5 对 `v2.0-m0` 的 A/B |
| [M2 落库复测](../benchmark/v2/m2/comparison.md) | 订单闭环对 `v2.0-m1` 的落库 A/B 与消费线程耗时分解 |
| [M3 准入漏斗复测](../benchmark/v2/m3/comparison.md) | 本地漏斗 + 单次 Lua 对 `v2.0-m2`：拐点、天花板、演练 |
| [M4 批量消费复测](../benchmark/v2/m4/comparison.md) | 批量落库对 `v2.0-m3`：落库速率、参数扫描、瓶颈定位 |
| [M5 分桶与 Cluster 复测](../benchmark/v2/m5/comparison.md) | 桶数扫描、Redis 主节点宕机 ×2 |
| [M6 消费参数扫描](../benchmark/v2/m6/consume-sweep.md) | 拉取间隔与批大小：批量为什么一直没生效 |

M6 的分片 A/B 与 M8 的三个场景没有单独的复测文档，结论在[压测报告](benchmark-report.md)第 2、3 节
与[进度表](plan/v2-high-concurrency-plan.md#5-进度表)里。
