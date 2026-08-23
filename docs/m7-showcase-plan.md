# M7 故障证据整合与项目展示计划

> 状态：M7 实施中；范围仅限证据整合、演示手册、README 收口和无共享依赖的最终契约检查。
>
> 基线：`codex/platform-hardening`，`054b113`；M5A--M5D、M6A--M6C 已完成。

## 1. 本阶段目标

M7 把已经完成的可靠性、营销和权限结果整理成一个可审计的展示入口，回答三件事：

1. 故障发生时新请求如何拒绝、已接受任务如何继续收敛；
2. 哪些结论来自专用依赖上的真实集成证据，哪些只是 consumer-level、单元/契约或人工演示准备；
3. 仍然缺少什么证据，达到什么条件才值得升级基础设施或扩大业务范围。

本阶段不重新建设故障注入平台，不改生产 Java 业务逻辑，不启动 MySQL、Redis、RocketMQ 或
Elasticsearch，不 push，也不进入 M7 之后的新技术升级阶段。

## 2. 证据复用规则

- M5D `m5d-20260822a` 是 Redis 短时不可用、MySQL 不可用、consumer pause、ES 不可用和
  Broker 不可用的正式故障证据；直接引用 `docs/m5d-reliability-results.md` 和机器摘要，保留
  F4 的 consumer-level/非 Canal E2E 边界。
- M5C `m5c-20260821e` 是单实例资源准入、semaphore/singleflight 和过载行为证据；不把
  JVM 本地结论扩展成集群流控结论。
- M3/M5 的对账文档和测试证明 `PROCESSING` 的 exact ownership、永久冲突暂停和 quarantine
  门禁；不把“进程仍存活”当作恢复证据。
- M6C `m6c_20260823h` 是 Redis Pub/Sub/通知 Outbox 的专用 MySQL+Redis 真实 IT；M6 通知
  不使用 RocketMQ，PUBLISHED 只代表 Redis 接受发布。
- 旧 Redis Stream baseline/current 仅是历史实验对照，不属于当前正式秒杀链路，也不能与当前
  RocketMQ 版本直接计算性能提升百分比。
- 没有对应的持久结果文档、run-id、提交或明确测试层级的场景，标记 `NOT_TESTED` 或
  `BOUNDARY`，不补跑整套 M5D/M6C 矩阵。

## 3. 交付物与验收

- `docs/m7-evidence-index.md`：统一入口、证据级别、系统边界图和秒杀状态图。
- `docs/m7-failure-matrix.csv`：固定列数的机器可读故障矩阵。
- `docs/m7-demo-runbook.md`：15 分钟演示顺序、命令、查询证据和清理边界。
- `docs/m7-results.md`：最终结果、已解决/仍有边界/何时升级和本地恢复时间声明。
- `README.md`：项目定位、四条主线、安全 Quick Start、测试分层和限制。
- `scripts/check-m7-frontend.js` 及用户端有限轮询契约：WebSocket `onclose/onerror` 只触发
  有总 deadline 和最多 10 次的持久列表轮询；成功/失败终态停止，不使用无限 interval。

最终无共享依赖检查包括 Java 8 offline compile/test-compile、安全单元测试、M5C/M6A/M6B/M6C
Node 契约、M7 Node 契约、admin Vite build、Shell/Python/Markdown/Mermaid/CSV 门禁和
`git diff --check`。这些检查不能替代外部故障 IT。

## 4. 停止线

- 发现证据对应不上当前提交或源码语义已变：记录缺口，停止扩展，不把历史结果改判为当前 PASS。
- 需要启动共享依赖、重复完整故障矩阵、引入 RocketMQ 通知、新 topic/consumer、Redis Cluster、
  新规则平台、审批或业务模块：停止并记录为超出 M7。
- 任何未知或共享网络连接、危险清理、宽范围删除：停止该检查并保留证据；清理只能按明确的
  专用 run-id 处理。
- 真实功能缺口若不能由文档或前端契约修复：只记录到结果边界，不修改生产 Java。
- 演示中的人工步骤、contract-level、consumer-level、Mock 和 NOT_TESTED 必须保持原标签，
  不得写成完整 E2E PASS。

## 5. 完成判定

M7 只有在五份文档、README、前端契约和所有无共享依赖检查均可追溯，工作区 clean，且没有
引入技术升级或新业务模块时完成。完成后 modernization 主线标记 `COMPLETED`；M7 之后不自动
开始 M8 或其他升级阶段。
