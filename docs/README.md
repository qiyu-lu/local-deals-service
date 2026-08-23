# 文档索引

这里是仓库详细文档的唯一入口。主 [README](../README.md) 负责项目概览；本页只说明阅读顺序、文档位置和证据状态，不复制专题正文。

## 当前状态

- 运行基线：Java 8 / Spring Boot 2.3.12.RELEASE。
- Modernization mainline 与 M7-RC：COMPLETED。
- M8、Java 17、Spring Boot 3：未开始。
- 当前结论以 [Pre-M8 最终结果](evidence/pre-m8/pre-m8-baseline-results.md)、[M7 结果](evidence/m7/m7-results.md) 和当前 Git HEAD 为准。

## 推荐阅读顺序

1. [主 README](../README.md)：项目定位、系统边界、可信数据与 Quick Start。
2. [系统设计与证据边界](evidence/m7/m7-evidence-index.md)：系统图、状态图、证据级别和故障矩阵。
3. [环境搭建](guides/environment-setup.md)：本地依赖、JDK/IDE 设置与常见问题。
4. [Pre-M8 最终结果](evidence/pre-m8/pre-m8-baseline-results.md)：当前版本的最终可复用证据。
5. [M7 演示手册](guides/project-demo.md)：15 分钟演示顺序和只读核验方式。

## 文档状态

| 标记 | 含义 |
| --- | --- |
| CURRENT | 描述当前实现、运行契约或维护入口 |
| EVIDENCE | 某阶段在固定环境下形成的证据快照；不自动等同于生产 SLA |
| HISTORICAL | 历史实现、执行计划或实验；不代表当前正式架构 |
| BOUNDARY | 尚未验证或明确不在当前范围内的能力 |

阶段计划保留执行时状态，包括未勾选项、BLOCKED 和负面结果。最终结论应阅读同阶段 results 文档，不能用后续结果追溯改写历史快照。

## 设计文档

- CURRENT — [商户后台与 RBAC](design/admin-rbac.md)
- CURRENT — [秒杀一致性、恢复与升级门禁](design/seckill-consistency-and-recovery.md)
- CURRENT — [点赞 Outbox 与可重建热榜](design/blog-like-outbox-hot-rank.md)
- CURRENT — [可观测指标目录](design/observability-metrics.md)
- CURRENT / BOUNDARY — [Modernization 路线](modernization-roadmap.md)：主体是历史执行路线，开头与结尾记录当前阶段。

## 使用指南

- CURRENT — [环境搭建](guides/environment-setup.md)
- CURRENT — [项目演示](guides/project-demo.md)
- CURRENT — [秒杀压测](guides/seckill-benchmark.md)
- CURRENT — [JMeter 测试计划](testing/seckill-benchmark.jmx)
- CURRENT — [M5A HTTP 基线测试计划](testing/m5a-http-baseline.jmx)

## 阶段证据

### M5

- EVIDENCE — [M5A 可观测基线结果](evidence/m5/m5a-observability-results.md)
- EVIDENCE — [M5B 有界缓存结果](evidence/m5/m5b-bounded-cache-results.md)
- EVIDENCE — [M5C 资源流控结果](evidence/m5/m5c-resource-traffic-results.md)
- EVIDENCE — [M5D 可靠性结果](evidence/m5/m5d-reliability-results.md)
- HISTORICAL — 同目录中的 plan；EVIDENCE — 同目录中的 summary.csv。

### M6

- EVIDENCE — [M6A 定向发券结果](evidence/m6/m6a-targeted-grant-results.md)
- EVIDENCE — [M6B 签到与任务奖励结果](evidence/m6/m6b-daily-task-results.md)
- EVIDENCE — [M6C 批量发券与通知结果](evidence/m6/m6c-batch-notification-results.md)
- HISTORICAL — 同目录中的 plan；EVIDENCE — 同目录中的 summary.csv。

### M7

- HISTORICAL — [M7 展示收口计划](evidence/m7/m7-showcase-plan.md)
- EVIDENCE — [M7 结果](evidence/m7/m7-results.md)
- EVIDENCE — [M7 证据索引](evidence/m7/m7-evidence-index.md)
- EVIDENCE / BOUNDARY — [M7 故障矩阵](evidence/m7/m7-failure-matrix.csv)

### Pre-M8

- HISTORICAL — [Pre-M8 基线计划](evidence/pre-m8/pre-m8-baseline-plan.md)
- EVIDENCE — [Pre-M8 最终结果](evidence/pre-m8/pre-m8-baseline-results.md)
- EVIDENCE — [Pre-M8 机器汇总](evidence/pre-m8/pre-m8-baseline-summary.csv)

## 历史归档

- HISTORICAL — [归档说明](archive/README.md)
- HISTORICAL — [Redis Stream 实验](archive/redis-stream/README.md)
- HISTORICAL — [旧项目上下文](archive/legacy-context/project-context.md)
- HISTORICAL — [旧改造对比](archive/legacy-context/improvement-comparison.md)

归档内容用于追溯，不作为当前 RocketMQ 架构、当前分支状态或下一阶段计划的依据。
