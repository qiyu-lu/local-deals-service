# 文档索引

V2 以代码为准；V1 的过程文档、执行证据和学习笔记已从主干删除，完整保留在 tag `v1-final`
（`git show v1-final:docs/<path>` 可查）。

| 文档 | 说明 |
| --- | --- |
| [V2 重构计划](plan/v2-high-concurrency-plan.md) | 唯一执行入口：现状诊断、里程碑、进度表 |
| [ADR](adr/) | 每个里程碑一页：背景 / 备选 / 决策 / 代价 |
| [M0 基线与瓶颈分析](../benchmark/v2/m0/baseline.md) | 开环阶梯压测、落库速率、火焰图结论（对照组） |
| [M1 同场复测](../benchmark/v2/m1/comparison.md) | Java 21 / Boot 3.5 对 `v2.0-m0` 的 A/B：准入、落库 |
| [M2 落库复测](../benchmark/v2/m2/comparison.md) | 订单闭环对 `v2.0-m1` 的落库 A/B 与消费线程耗时分解 |
| [M3 准入漏斗复测](../benchmark/v2/m3/comparison.md) | 本地漏斗 + 单次 Lua 对 `v2.0-m2` 的 A/B：拐点、天花板、故障演练 |
| [M4 批量消费复测](../benchmark/v2/m4/comparison.md) | 批量落库对 `v2.0-m3` 的 A/B：落库速率、参数扫描、瓶颈定位 |
| [运行手册](runbook.md) | 开发栈、隔离栈、Quick Start、压测与演练、排障 |
| [设计（V1，待按 ADR 重写）](design/) | 与 V2 冲突的部分随对应里程碑改写为 ADR，M9 删除 |

目标形态（M9）：`README.md`、`adr/`、`plan/`、`benchmark-report.md`、`runbook.md`，合计 2000 行以内。
