# M7 故障证据整合与项目展示结果

> 状态：M7 COMPLETED；modernization mainline COMPLETED；M7 之后未开始新阶段；未 push。
>
> 基线：`codex/platform-hardening`，起点 `054b113`；本阶段没有修改生产 Java。

## 1. 最终结论

M7 复用了 M5C/M5D、M3 对账和 M6A/M6B/M6C 的已有结果，没有重新执行完整 M5D 或 M6C 故障矩阵。
新增内容限于：

- M7 范围/停止线计划、证据索引、故障 CSV、15 分钟演示手册；
- README 首页定位、系统边界、四条主线、M6 当前能力、安全 Quick Start、测试分层和限制；
- 用户端 WebSocket `onclose/onerror` 的有限持久列表轮询契约及 `scripts/check-m7-frontend.js`。

没有启动 MySQL、Redis、RocketMQ、Elasticsearch，也没有增加业务模块、RocketMQ topic/consumer、
Redis Cluster、任务 DSL、审批或技术升级。

## 2. 无共享依赖最终检查

| 检查 | 结果 | 证据/说明 |
| --- | --- | --- |
| Java 8 offline `compile` + `test-compile` | PASS | Dragonwell `1.8.0_472` + Maven `3.9.11` 显式路径；命令成功 |
| Java 8 默认安全单元测试 | PASS | `321` tests，0 failure/error/skip；Maven 默认不执行 `*IT` |
| M5C/M6A/M6B/M6C Node 契约 | PASS | 4 个既有脚本全部通过 |
| M7 Node 前端契约 | PASS | `scripts/check-m7-frontend.js`；close/error、最多 10 次、deadline、SUCCESS/FAILED 停止和无 interval |
| admin Vite build | PASS | `npm run build`；保留既有 CJS、依赖注释和大 chunk 警告，不升级依赖 |
| Shell 语法 | PASS | `bash -n scripts/*.sh` |
| Python CLI/语法 | PASS | scripts 下 Python 文件 AST parse 通过 |
| Markdown 相对链接 | PASS | 65 个 tracked Markdown 文件的链接目标和 fence 通过；benchmark/vendor 忽略产物不纳入入口检查 |
| Mermaid fence 成对 | PASS | README 和 M7 evidence index fence 成对 |
| CSV 列一致性 | PASS | M7 failure matrix 11 列，所有数据行一致 |
| `git diff --check` | PASS | 无空白错误 |

默认 shell 的 `java` 是 Java 17 且 PATH 没有 `mvn`；按仓库既有记录切换到本地 Dragonwell Java 8
和 Maven 3.9.11 后通过。这是工具选择记录，不是代码失败或环境 BLOCKED。

## 3. 复用的真实结果与指标

| 范围 | 结果 |
| --- | --- |
| M5D F1–F5 | `m5d-20260822a`；Redis pause、MySQL stop、consumer pause、ES pause、Broker stop 均有专用结果；F4 明确 consumer-level |
| M5C 单实例过载 | `m5c-20260821e`；activity/user/IP 准入、DB_READ/SEARCH semaphore 和 singleflight 结论保留为 JVM-local |
| M3/M5 对账 | `dd36b4b`；exact ownership、`PROCESSING`、永久冲突、`SUSPENDED`、`QUARANTINE` 和 reconciler 门禁可追溯 |
| M6A/M6B | 分别完成标签/统一 grant、签到/TASK reward 和 V9/V10 fresh/upgrade；M6A USER_CLAIM/ADMIN_GRANT 与 M6B TASK_REWARD 回归保留 |
| M6C | `m6c_20260823h`；V11 fresh/upgrade、100 item、Job pause/resume/retry、Outbox 事务和 Redis recovery 通过 |

M6C 批量验证指标：target/granted/idempotent/skipped/failed=`100/100/0/0/0`；活动剩余额度
`0`；小批次 `17`、worker-equivalent `6` 批、总收敛 `722ms`；item P99=`NA`；入口限流数=`NA`；
Outbox pending=`1`、oldest age=`0ms`（来自真实 pending 行 `create_time`，不是瞬时零替代）；
Redis 恢复发布 `25ms`，随后 pending=`0`、状态=`PUBLISHED`。这些是专用本地运行观测，不是生产
SLA；`PUBLISHED` 也不表示用户在线、已读或实际收到。

## 4. 已解决

- 故障场景现在有统一索引和 11 列 CSV；PASS、consumer-level、contract/manual、NOT_TESTED 和
  historical-only 不再混写。
- README 将 Redis Stream 降为历史实验，将 M6A/M6B/M6C 能力、Redis Pub/Sub 通知边界和 ES
  consumer-level/Canal E2E 边界放到首页；删除了不安全的通用 `-Dtest="*IT"` Quick Start。
- 演示顺序固定为 15 分钟，并给出 MySQL/Redis/RocketMQ 只读查询、专用 run-id 校验和精确清理方式。
- WebSocket 断线后只做有限 fallback：最多 10 次、总 deadline 30 秒、只用 `setTimeout`；
  `SUCCESS/FAILED` 终态或收到固定 `VOUCHER_GRANTED` 事件后停止，`eventId` 去重并刷新持久券包。

## 5. 仍有边界

- 没有验证 Redis 全量数据丢失恢复和确定 RPO；需要 AOF/备份恢复、reservation/status/库存/订单
  三方对账后才可形成结论。
- 没有验证完整 Canal Server → RocketMQ → ES E2E；M5D F4 和 `CanalSyncIT` 都保持 consumer-level
  边界。
- M6 通知未使用 RocketMQ；只验证 Redis Pub/Sub + WebSocket 投递边界和 `/voucher-grants/mine`
  持久事实。
- 本地恢复时间不是生产 SLA；当前不支持 Redis Cluster，也不支持新旧秒杀消息协议滚动混跑。
- 没有核销、支付、退款，也没有 Spring Boot 3/Java 17 升级。
- 历史 Redis Stream baseline/current 数据与当前 RocketMQ 版本不能直接计算性能提升百分比。
- 没有真实浏览器 WebSocket E2E；前端断线结论是 contract-level/manual-demo-ready。

## 6. 何时升级

只有在以下证据出现后才另立新阶段：

1. Redis 数据丢失的备份/AOF 恢复演练给出可接受 RPO、三方对账和回放边界；
2. 真实 Canal Server、RocketMQ 和 ES 在专用 run-id 下形成完整 E2E 事件证据；
3. 真实多实例/Redis Cluster 需求证明当前 hash-tag、锁和流控边界不足；
4. 实际业务需要核销、支付、退款、任务 DSL、审批或通知平台，并先定义独立数据不变量和升级门禁；
5. 性能、容量或生产 SLA 有新的受控基线，而不是把历史 Stream 数值与当前 RocketMQ 直接比较。

## 7. 可追溯入口

- [`m7-showcase-plan.md`](m7-showcase-plan.md)：范围、复用规则和停止线。
- [`m7-evidence-index.md`](m7-evidence-index.md)：证据级别、故障矩阵解释和 Mermaid 图。
- [`m7-failure-matrix.csv`](m7-failure-matrix.csv)：机器可读场景表。
- [`m7-demo-runbook.md`](m7-demo-runbook.md)：15 分钟演示、只读证据命令和精确清理。
- [`modernization-roadmap.md`](modernization-roadmap.md)：M5/M6/M7 收口路线。

M5/M6 的失败尝试、共享连接硬停止、parser/fixture/deadlock/可见性修正和原始隔离证据继续保留
在各阶段结果文档及其 run 目录；本阶段没有用新的“未跑故障”替换这些负面记录。
