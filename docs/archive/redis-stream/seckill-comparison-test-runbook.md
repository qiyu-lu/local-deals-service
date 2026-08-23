# 秒杀对比验证执行手册

本文记录"原始教程版 baseline"与"当前可靠性增强版 reliable-stream-v1"的对比验证步骤。

## 验证目标

- baseline：保留黑马点评原始秒杀链路的行为边界。
- reliable-stream-v1：验证增加 DB 唯一索引兜底、pending 重试上限、dead-letter Stream 和指标后，正常压测正确性不退化，异常消息可以闭环处理。
- 对外表述重点：可靠性、可观测性、自动化验证增强；不要把单轮吞吐或 P99 波动包装成显著性能提升。

## 路径和版本

| 用途 | 路径 / 分支 | 说明 |
| --- | --- | --- |
| 当前主仓库 | `/home/sd101t/IdeaProjects/hm-dianping`，`main` | 保存脚本、JMeter 计划、所有测试证据和最终文档 |
| baseline worktree | `/home/sd101t/IdeaProjects/hm-dianping-baseline`，`feature-seckill-benchmark-baseline` | 只负责运行旧代码，不在里面沉淀最终证据 |

不要通过 `git switch` 在主仓库切换版本，使用独立 worktree 可同时保留两份输出。如果 baseline worktree 丢失：

```bash
git worktree add /home/sd101t/IdeaProjects/hm-dianping-baseline feature-seckill-benchmark-baseline
```

## 环境约束

两个版本连接不同的数据库：

| 版本 | 数据库 | 说明 |
| --- | --- | --- |
| baseline-lua-stream | `hmdp` | baseline worktree 自身配置 |
| reliable-stream-v1 | `local_deals` | 通过 `.env` 注入 |

注意：
- 两版本共用同一 Redis，切换前必须通过脚本重置 Stream 和 Redis 数据。
- 每次只启动一个后端服务（两边默认都监听 `8083`）。

## 启动服务

**启动 baseline：**

```bash
cd /home/sd101t/IdeaProjects/hm-dianping-baseline
/opt/idea/plugins/maven/lib/maven3/bin/mvn spring-boot:run
```

**启动当前版本：**

```bash
cd /home/sd101t/IdeaProjects/hm-dianping
set -a && source .env && set +a
/opt/idea/plugins/maven/lib/maven3/bin/mvn spring-boot:run
```

## 运行测试

完整命令参数见 `docs/guides/seckill-benchmark.md`。关键差异：

| 版本 | 必须追加的参数 |
| --- | --- |
| baseline | `--project-dir /home/sd101t/IdeaProjects/hm-dianping-baseline --mysql-database hmdp --voucher-id 11` |
| reliable-stream-v1 | `--mysql-database local_deals`（voucher 自动创建） |

故障注入同理，baseline 追加 `--expect baseline`，current 追加 `--expect current`。

## 正确性判定

正常压测通过条件：

- `error_pct = 0.000%`
- MySQL 订单数 = `expected_orders`，重复下单数 = 0
- MySQL 库存 ≥ 0；Redis 库存 = `stock - expected_orders`
- Stream pending = 0，dead-letter = 0

故障注入通过条件：

| 版本 | 预期表现 |
| --- | --- |
| baseline | pending > 0，dead-letter = 0（消息残留，无闭环） |
| reliable-stream-v1 | pending = 0，dead-letter > 0（有限重试后进入 DLQ） |

## 必须记录的证据

正常压测每轮：`run_id`、实现版本、场景、threads/loops/stock/user-count、throughput、avg/P95/P99/max ms、error_pct、drain_ms、mysql_orders/expected_orders、duplicate_orders、mysql_stock、redis_stock、stream_pending、stream_dead_letters、correctness。

故障注入每轮：`run_id`、实现版本、预期类型、voucher_id、injected_record_id、stream_pending、stream_dead_letters、retry_key_count、correctness、dead-letter 样例。

人工总览从 `run-summary.md` 的 `Markdown Row` 复制到：

- 正常压测：`docs/archive/redis-stream/benchmark-results.md`
- 故障注入：`docs/archive/redis-stream/reliability-results.md`

## 当前完成状态

截至 2026-05-20，baseline 与 reliable-stream-v1 对比验证已补齐：

| 状态 | 版本 | 场景 |
| --- | --- | --- |
| 已完成 | baseline-lua-stream | 1000 / 5000 线程正常压测；故障注入 |
| 已完成 | reliable-stream-v1 | 1000 / 5000 线程正常压测；故障注入 |
| 已废弃 | baseline-lua-stream r1/r2 | 使用旧 JMX 硬编码路径，出现 401，不作为有效证据 |

## 简历表述边界

可以说：

- 将教程版 Redis Stream 异步下单改造为带 DB 唯一索引兜底、pending 重试上限、dead-letter 和 Prometheus 指标的可靠消费链路。
- 搭建 JMeter + MySQL + Redis 校验脚本，自动记录吞吐、P95/P99、异步落库追平时间、订单一致性和 dead-letter 状态。
- 用异常消息注入验证原始实现会产生 pending 残留，改造后能进入 DLQ 并清空 pending。

不要说：
- "性能大幅提升"、"完全生产可用"、"解决了所有秒杀一致性问题"。
