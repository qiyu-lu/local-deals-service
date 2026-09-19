# 文档索引

V2 以代码为准；V1 的过程文档、执行证据和学习笔记已从主干删除，完整保留在 tag `v1-final`
（`git show v1-final:docs/<path>` 可查）。

| 文档 | 说明 |
| --- | --- |
| [V2 重构计划](plan/v2-high-concurrency-plan.md) | 唯一执行入口：现状诊断、里程碑、进度表 |
| [ADR](adr/) | 每个里程碑一页：背景 / 备选 / 决策 / 代价 |
| [M0 基线与瓶颈分析](../benchmark/v2/m0/baseline.md) | 开环阶梯压测、落库速率、火焰图结论（对照组） |
| [环境与常见问题](guides/environment-setup.md) | 开发栈、隔离栈、压测命令、JDK 设置 |
| [设计（V1，待按 ADR 重写）](design/) | 与 V2 冲突的部分随对应里程碑改写为 ADR，M9 删除 |

目标形态（M9）：`README.md`、`adr/`、`plan/`、`benchmark-report.md`、`runbook.md`，合计 2000 行以内。
