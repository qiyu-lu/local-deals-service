# M7-RC：Java 17 / Spring Boot 3 升级前基线计划

> HISTORICAL EXECUTION PLAN：本文保留阶段执行时的契约、状态与负面结果；最终结论以同目录 results 文档为准。

> 状态：执行契约；本阶段不升级 Java、Spring Boot 或 Redis 拓扑，不增加业务模块，不 push。
>
> 基线：`codex/platform-hardening` / `d063764b7f82e590f977f9b25614670993d6c467`；M7 与 modernization 主线已完成；M8 未开始。

## 1. 目标与边界

M7-RC 只收口当前 Java 8 + Spring Boot 2.3.12 版本的可比较功能、业务、可靠性和本地性能证据，为未来 M8 升级保留可复现基线。允许修改测试、JMeter/验证脚本、隔离环境脚本、结果文档和 README；生产 Java 代码默认冻结。若发现真实生产缺陷，先保留失败日志和原始结果，再做最小、独立、可审计修复，不通过降低断言或调整压测参数制造 PASS。

不在本阶段实施 Java 17、Spring Boot 3、Redis Sentinel/Cluster、高可用、微服务、支付、退款、核销或新业务，也不重跑已经完成且证据可对应的完整 M5D/M6C 故障矩阵。M5B/M5C/M5D/M6C 的既有结果作为历史证据入口；Redis 全量丢失/RPO、完整 Canal Server→RocketMQ→ES、浏览器 WebSocket E2E 和 Redis HA 继续保持边界。

## 2. 专用运行契约

本次最终正式 run-id 为 `m7rc_20260823i`，Compose project 为 `m7rc-20260823i`。a–h 轮的配置/fixture/消息隔离失败和修正日志仍保留在本地 artifact；i 轮才作为最终正式业务结果。所有容器、网络、日志和结果目录必须包含该 run-id；脚本在资源已存在、端口占用、schema 不匹配或连接目标不明确时 fail closed，不复用共享资源。

默认端口（均绑定 `127.0.0.1`，运行前检查并允许通过同一 run-id 重新选择一组端口）：

| 角色 | 端口 |
| --- | ---: |
| MySQL 8 | 28338 |
| Redis | 28387 |
| RocketMQ NameServer | 29884 |
| RocketMQ Broker | 29127（HA 端口 29128，仅专用容器内部配置） |
| Elasticsearch 7.17.18 | 29208 |
| 单应用实例 | 28091 |
| Actuator | 28192 |

禁止连接或监听共享默认端口 `3306`、`6379`、`9876`、`10911`、`9200`。本阶段只核对容器名、Compose project、端口映射、镜像版本和应用启动日志；只有出现可疑目标时才启用 strace。测试结束后仅按 project/run-id 精确 down 并核对无残留，不执行 `docker system prune`、`docker volume prune` 或 broad prune。

专用依赖固定为 MySQL 8、Redis、`apache/rocketmq:4.9.4`、Elasticsearch 7.17.18 和一个应用实例。应用默认不启动 M6C 批量/通知 worker；需要后台处理时只在专用演示或测试命令中显式打开。秒杀流控模块保持开启，仅将本次测试 fixture 的 activity/IP 限额提高到至少 2000。

## 3. 验证顺序

1. 记录 Git SHA、分支、Java/Maven/JMeter/Node/Python、CPU、内存、Docker 镜像摘要和测试前主机负载。
2. Java 8 offline `clean compile test-compile`，随后默认安全单元测试全量；计数以实际 Surefire 结果为准。
3. 执行 M5C、M6A、M6B、M6C、M7 Node 契约，admin Vite build，Shell/Python/CSV/Markdown/Mermaid/git diff 检查。
4. 在本次专用 MySQL 上显式验证 Flyway fresh V1→V11 与 upgrade V8→V11，并检查 history、表、索引、外键和关键枚举；不为历史 grant 追溯创建 Outbox。
5. 按测试类名显式运行权限隔离、营销、秒杀可靠链路和小型核心能力回归；不使用不受控的 `-Dtest="*IT"`。
6. M6C 批量测试默认仍为 100；本次通过测试环境变量将同一业务场景参数化为 1000，只改测试，不改生产设计。
7. 先执行一次 100 请求 warmup，不计入正式结果；之后执行 RocketMQ S1 三轮和 S2 一轮，保留每轮原始 JTL、计数器、MQ lag、数据库/Redis 快照和 drain 时间。
8. 发生工具/配置错误时保留旧 run-id 证据，修复后用新 run-id 完整重跑；发生产品缺陷时停止扩展、记录并单独修复提交。

最终 i 轮还显式记录了应用日志中的 MySQL/Redis/NameServer/ES 目标、容器/Compose project、镜像版本和
测试前负载；`scripts/run-seckill-benchmark.sh` 负责保存 Prometheus counter 前后差值和 RocketMQ
`consumerProgress`/DLQ 深度原始输出。JMeter 报告中的“1000 threads”只表示配置的线程数和 ramp-up，
不表示严格同时并发。

## 4. 验收合同

### 4.1 显式测试组

- 权限与租户隔离：`AdminRbacIT`、`MarketingAdminIsolationIT`、`MarketingMvcSecurityTest`。
- 营销业务：`M6aBusinessFlowIT`、`MarketingGrantConcurrencyIT`、`M6bDailyTaskBusinessIT`、`M6cBatchBusinessIT`。
- 秒杀可靠链路：`SeckillWithRocketMQIT`、`SeckillOrderRetryIT`、`SeckillOrderStateIT`、`VoucherOrderReliabilityIT`。
- 其他核心能力：`BoundedCacheMySqlRedisIT`、`BlogLikeReliabilityIT`、`BlogHotRankRedisIT`、`CanalSyncIT`。

`CanalSyncIT` 的证据级别仍为 ES consumer-level；不能写成完整 Canal E2E。测试必须通过专用环境变量指向本次 MySQL/Redis/RocketMQ/ES，不能因默认配置而连接共享依赖。

### 4.2 批量发券 1000 人

记录 target、GRANTED、IDEMPOTENT、SKIPPED、FAILED、worker batch 数、总收敛时间、grant 数、`campaign.granted_count`、Outbox 数、重复 grant 数和测试后 fixture 清零结果。正式通过条件为 `1000/1000/0/0/0`，grant、额度计数和 Outbox 均为 1000，重复 grant 为 0。该结果是本地业务集成样本，不是生产吞吐或 Item P99；没有测量的入口限流记为 `NA`。

### 4.3 RocketMQ S1/S2

S1 固定 1000 threads、1000 unique users、1 loop、5 秒 ramp-up、stock=1000，连续三轮；S2 固定同一请求规模、stock=100，执行一轮正确性验证。另有一次 100 请求 warmup，不计入 S1/S2。

每轮同时保存：Prometheus 秒杀结果 Counter 前后差值（accepted、rejected_stock、rejected_duplicate、rejected_rate、unavailable）、RocketMQ main/retry/DLQ 最终 lag、MySQL order、Redis reservation/SUCCESS/processing、库存、重复订单、JMeter P50/P95/P99、吞吐和异步 drain。S1 必须每轮全成功且所有拒绝、最终 lag、processing、重复均为 0；正式性能只报告三轮中位数和最差 P99。S2 必须为 accepted=100、rejected_stock=900，其余拒绝/错误/processing/最终 lag/重复均为 0；S2 总吞吐包含快速库存拒绝，不称为成功下单吞吐。

历史 Redis Stream baseline/current 不与当前 RocketMQ 版本计算性能提升百分比。1000 threads 不写成严格同时并发；本机恢复时间不写成生产 SLA；不声称 Redis HA、完整 Canal E2E 或消息绝不丢失。

## 5. 交付物与停止线

结果写入：

- `docs/evidence/pre-m8/pre-m8-baseline-results.md`：完整环境、原始轮次、批量结果、测试计数、失败证据、简历表述和不能声称的边界。
- `docs/evidence/pre-m8/pre-m8-baseline-summary.csv`：严格固定列数，记录每个场景的证据级别和限制。
- `scripts/pre-m8-baseline-stack.sh` 及必要的测试/benchmark 脚本变更：只拥有本次 run-id 的资源。

遇到任意共享/未知连接、grant 与 Outbox 无法同事务提交、批量逻辑绕过统一 `VoucherGrantService`、后台 worker 默认开启、需要引入 RocketMQ 新 topic/任务 DSL/通用审批/新规则平台，立即停止本阶段对应真实运行并保留证据。M7-RC 不创建 M8 技术升级阶段。
