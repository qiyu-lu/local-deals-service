# M7-RC：Java 17 / Spring Boot 3 升级前基线结果

> 状态：M7-RC COMPLETED；M7 与 modernization mainline 已完成；M8 未开始；未 push。
>
> 本地源基线：`codex/platform-hardening` / `d063764b7f82e590f977f9b25614670993d6c467`。
> 本阶段没有修改 `src/main` 生产 Java；只修改测试、隔离脚本、JMeter/benchmark 脚本、README 和结果文档。

## 1. 环境与隔离

最终正式外部运行使用 `m7rc_20260823i` / Compose project `m7rc-20260823i`，schema 为
`m5b_m7rc_20260823i`。a–h 轮的配置、fixture、消息 topic/group 和工具失败日志仍保留在本地
`benchmark/pre-m8/m7rc_20260823i/` 的 warmup/测试日志及历史 run 目录中；只有 i 轮计入正式结果。

| 项目 | 记录 |
| --- | --- |
| MySQL | `mysql:8.0`，`127.0.0.1:28338 -> 3306` |
| Redis | `redis:6.2`，`127.0.0.1:28387 -> 6379`，专用密码，AOF/Cluster 未启用 |
| RocketMQ | `apache/rocketmq:4.9.4`，NameServer `29884`，Broker `29127/29128` |
| Elasticsearch | 专用 `hm-dianping-elasticsearch:latest`，底层 7.17.18，`127.0.0.1:29208 -> 9200`；应用客户端仍为依赖中的 7.6.2 |
| 应用 | 单实例 `28091`，management `28192`；M6C Job/notification worker 均显式关闭 |
| 流控 | activity/IP limit 显式设置为 2000，流控模块仍开启 |
| 工具 | Dragonwell Java `1.8.0_472`、Maven `3.9.11`、JMeter `5.6.3`、Node `22.21.1`、npm `10.9.4`、Python `3.8.10`、Docker `28.0.1`、Compose `v2.33.1` |
| 主机 | 16 CPU、30 GiB 内存；启动前记录 load average `1.15/0.92/0.81`。该值只描述本机运行条件 |
| 连接边界 | 所有应用日志和 Compose 映射均指向本次专用端口；没有使用共享 `3306/6379/9876/10911/9200`。没有启动 RocketMQ/ES 之外的额外依赖 |

Docker image digest、容器名/端口和应用连接日志保存在 i run 目录及命令输出中；本阶段没有因可疑
连接启动 strace。测试完毕后只按 i project/run-id 做精确清理，未使用 broad prune。

## 2. Java、Flyway、显式集成组与前端检查

| 检查 | 结果 |
| --- | --- |
| Java 8 offline `clean test-compile` | PASS；205 main / 118 test source 编译成功 |
| 默认安全单元测试 `mvn -o test` | PASS；321 tests，0 failure/error/skip；没有使用 `*IT` 通配符 |
| 权限与商户隔离 | PASS；`AdminRbacIT`、`MarketingAdminIsolationIT`、`MarketingMvcSecurityTest`，3 个显式 target 全部 0 |
| M6A/M6B/M6C 业务 | PASS；`M6aBusinessFlowIT`、`MarketingGrantConcurrencyIT`、`M6bDailyTaskBusinessIT`、`M6cBatchBusinessIT`，4 个显式 target 全部 0 |
| Flyway | PASS；fresh V1→V11、V10→V11（保留历史 grant 且无历史 Outbox）和 V8→V11；history=11、V11 三张表、索引、`BATCH_GRANT` 枚举及 grant 外键 CASCADE 均通过 |
| 秒杀可靠链路 | PASS；`SeckillWithRocketMQIT`、`SeckillOrderRetryIT` 两个方法、`SeckillOrderStateIT`、`VoucherOrderReliabilityIT` 全部通过 |
| 其他核心能力 | PASS；`BoundedCacheMySqlRedisIT`、`BlogLikeReliabilityIT`、`BlogHotRankRedisIT`、`CanalSyncIT` 全部通过。`CanalSyncIT` 仍是 ES consumer-level，不是 Canal E2E |
| Node 契约 | PASS；M5C、M6A、M6B、M6C、M7 五个脚本 |
| admin Vite build | PASS；不升级依赖；保留已有 chunk/CJS warning |
| Shell/Python/Markdown/Mermaid/CSV/git diff | PASS；详见最终检查清单 |

显式外部组共 5 组、17 个 class/method target；日志和 status 文件在
`benchmark/pre-m8/m7rc_20260823i/tests/`。测试运行器曾漏传 M5B ES/NameServer 环境变量，修复后
只重跑 core；第一次重跑受到沙箱 socket 权限拒绝，使用相同专用目标的授权运行通过。这些失败没有被
删除或改写成产品失败。

## 3. M6C 1000 人业务样本

`M6cBatchBusinessIT` 默认仍为 100；本轮仅用 `M7_RC_BATCH_TARGET=1000` 和 batch size 17 参数化测试。

| target | GRANTED | IDEMPOTENT | SKIPPED | FAILED | worker batch size | batches | convergence | grant rows | campaign granted_count | outbox rows | duplicate grants | fixture cleanup |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1000 | 1000 | 0 | 0 | 0 | 17 | 59 | 4285 ms | 1000 | 1000 | 1000 | 0 | users/merchants/tags/campaigns=0 |

`item_latency_p99_ms=NA`，入口限流数=`NA`，没有用 0 冒充未测量。该结果是本地业务集成样本，
不是生产吞吐或 Item P99。M6C 既有 Redis Outbox 恢复证据仍为真实 pending 行：pending=1、
oldest age 由 `create_time` 得出，恢复发布 25ms 后收敛为 PUBLISHED；不把 `PUBLISHED` 解释为用户
在线、已读或实际收到，也不把 M6 通知说成 RocketMQ 通知。

## 4. 当前 RocketMQ 秒杀压测

先执行 100 请求 warmup，最终修正后的 warmup 不计入正式结果：accepted/orders/reservation/SUCCESS
为 `100/100/100/100`，库存为 900，最终 MQ lag 为 `0/0/0`，P99=21ms，drain=14574ms。
此前两次 warmup 解析失败也保留：第一次把不存在的 DLQ topic 记为 `NA`，第二次在 offset flush
完成前抓到了 main lag=8；脚本随后修正为“topic 不存在即真实深度 0”并等待 main/retry/DLQ 最终归零。

### S1：stock=1000，1000 threads / 1000 unique users / 1 loop / ramp-up 5s

三轮参数、环境和 fixture reset 一致。`1000 threads` 是 JMeter 配置，不是严格同时并发。

| 轮次 | accepted | rejected_stock | rejected_duplicate | rejected_rate | unavailable | orders | DB/Redis stock | reservation/SUCCESS | processing | duplicate orders | MQ main/retry/DLQ | P50 | P95 | P99 | JMeter throughput/s | drain ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1000 | 0 | 0 | 0 | 0 | 1000 | 0/0 | 1000/1000 | 0 | 0 | 0/0/0 | 2 ms | 3 ms | 64 ms | 208.85547 | 13042 |
| 2 | 1000 | 0 | 0 | 0 | 0 | 1000 | 0/0 | 1000/1000 | 0 | 0 | 0/0/0 | 2 ms | 3 ms | 45 ms | 209.55574 | 13171 |
| 3 | 1000 | 0 | 0 | 0 | 0 | 1000 | 0/0 | 1000/1000 | 0 | 0 | 0/0/0 | 2 ms | 4 ms | 61 ms | 206.14306 | 13696 |
| 三轮中位数 | 1000 | 0 | 0 | 0 | 0 | 1000 | 0/0 | 1000/1000 | 0 | 0 | 0/0/0 | 2 ms | 3 ms | — | 208.85547 | 13171 |
| 三轮最差 | 1000 | 0 | 0 | 0 | 0 | 1000 | 0/0 | 1000/1000 | 0 | 0 | 0/0/0 | — | — | 64 ms | — | 13696 |

### S2：stock=100，1000 threads / 1000 unique users / 1 loop / ramp-up 5s

| accepted | rejected_stock | rejected_duplicate | rejected_rate | unavailable | orders | DB/Redis stock | reservation/SUCCESS | processing | duplicate orders | MQ main/retry/DLQ | P50 | P95 | P99 | 总吞吐/s |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: |
| 100 | 900 | 0 | 0 | 0 | 100 | 0/0 | 100/100 | 0 | 0 | 0/0/0 | 1 ms | 2 ms | 26 ms | 211.14865 |

S2 总吞吐包含快速库存拒绝，只用于正确性样本，不称为成功下单吞吐。各轮 Prometheus counter
取自 `/actuator/prometheus` 前后差值；RocketMQ lag 取专用 Broker `consumerProgress` 和 DLQ
topic depth，不用 HTTP Error% 代替业务事实。

原始结果入口：

- [S1/S2/warmup run summaries](../docs/JmeterTestSummary/)；JTL、Prometheus 前后快照和 consumerProgress 在本地 `benchmark/` 目录。
- [i 轮显式 IT 日志](../benchmark/pre-m8/m7rc_20260823i/tests/)。
- [机器可读场景汇总](pre-m8-baseline-summary.csv)。

## 5. 失败证据与修正边界

| run | 观察到的失败 | 处理 |
| --- | --- | --- |
| a | Redis 密码保护导致旧 M6C 测试 factory 出现 NOAUTH | 只改测试配置读取专用密码；保留原日志 |
| b | marketing 通过；秒杀 fixture 缺少固定 voucher、测试 context 重复 notifier | 补测试 fixture/测试配置，不改生产设计 |
| c/d | `@TestConfiguration` 合并完整应用上下文，窄组启动失败 | 改为测试组件/普通配置并保留诊断重跑 |
| e | 默认 consumer group 与应用竞争，出现 accepted 数减少和 retry 不稳定 | 使用专用 consumer group；保留 e 轮失败 |
| f/g | 同 topic 的跨 class/旧消息污染，及中断后旧消息残留 | 每个 target 使用专用 topic/group，保留 f/g |
| h | 新 topic 路由/重试消费建立较慢，显式 method target 未在固定时间内收敛 | 运行前用 broker admin 预创建 topic/group；保留 h 轮 |
| i core 首次 | runner 漏传 M5B ES/NameServer，修正后又遇到沙箱 socket 权限 | 补传环境变量；授权同一 i 目标重跑通过 |
| warmup | DLQ 不存在和 offset flush 时序曾被解析成失败/非零 | 修正仅为证据等待/解析器，并留下旧日志；最终 warmup 通过 |

没有发现需要修改生产 Java 的功能缺口；没有通过降低断言、改变正式压测参数或删除异常轮制造 PASS。

## 6. 可用于简历的三条表述

1. 基于 Redis Lua 精确预约、RocketMQ 事务消息和 MySQL 幂等/状态对账构建秒杀链路；在专用本地样本中完成 3 轮 1000 请求全成功与 1 轮 100 库存竞争，保持零超卖、零重复并验证最终 MQ lag 归零。
2. 当前 RocketMQ 链路在 1000 threads、5 秒 ramp-up 条件下三轮 P99 为 64/45/61ms，三轮中位数吞吐 208.85547 req/s；异步 drain 的本机观测为 13042/13171/13696ms，不将其包装为生产 SLA。
3. 以 Redis 有界缓存/准入、故障 fail-closed、consumer backlog 恢复和 M6C 批量发券事务 Outbox 形成可审计验证闭环；1000 人样本达到 `1000/1000/0/0/0`，grant、额度计数和 Outbox 各 1000，重复 grant 为 0。

## 7. 不能声称的边界

- 没有验证 Redis 全量数据丢失恢复，也没有确定 RPO；当前 Redis 不是 Sentinel/Cluster 高可用。
- 没有验证完整 Canal Server → RocketMQ → ES E2E；`CanalSyncIT` 和 M5D F4 仍是 consumer-level。
- M6 通知使用 Redis Pub/Sub + WebSocket，不使用 RocketMQ；没有通知已读、短信、邮件或推送平台。
- 本地 drain/recovery 时间不是生产 SLA；1000 threads 不等于严格同时并发；本地业务集成样本不是生产吞吐或 Item P99。
- 不支持新旧秒杀消息协议滚动混跑；没有核销、支付、退款；没有 Spring Boot 3/Java 17 升级。
- 历史 Redis Stream baseline/current 与当前 RocketMQ 版本不能直接计算性能提升百分比。

M8 只有在另立阶段、定义 Java 17/Spring Boot 3 兼容矩阵和可比性能基线后才能开始；本阶段不创建或暗示
该技术升级阶段。
