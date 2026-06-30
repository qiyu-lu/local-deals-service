<!-- AI session context — 供 Claude Code 等 AI 助手快速恢复项目上下文用，非用户阅读文档 -->

# 项目上下文恢复

本文用于关闭聊天后快速恢复 local-deals-service 项目上下文。它不是聊天流水账，而是记录当前阶段、重要修改、关键结论和下一步计划。

## 当前快照

- 当前分支：`main`
- 最新本地提交：`34a7356 fix: ThreadLocal cleanup, checkLocalTransaction safety, EsSyncConsumer row error handling, CORS pattern`
- 本地领先 origin/main 共 12 个提交（尚未推送）
- 冻结原始基线分支：`baseline-video-version`
- 当前本地阶段：已完成两阶段演进。第一阶段：秒杀 Redis Stream 可靠性增强 + 压测验证。第二阶段（2026-06-30 完成）：ES + RocketMQ + Canal + WebSocket 全链路升级。
- 本机仓库路径：`/home/sd101t/IdeaProjects/hm-dianping`

## 第二阶段（2026-06-30）完成内容

完整提交历史见 `git log --oneline`，主要节点：

| 提交 | 内容 |
|---|---|
| `0783ab8` | ES 7.17.18 + RocketMQ + Canal Docker 基础设施 |
| `0612467` | ES 商铺/博客搜索，IK 分词 + geo-distance |
| `36c663f` | RocketMQ 事务消息替换 Redis Stream 秒杀 |
| `770b6d1` | Canal → RocketMQ → ES 自动同步管道 |
| `e89e33a` | WebSocket 实时推送 + Redis pub/sub 多实例路由 |
| `ca1c5df` | docs/improvement-comparison.md |
| `34a7356` | 最终 code review 修复（ThreadLocal、checkLocalTransaction、EsSyncConsumer 错误处理） |

## 关键基础设施状态（docker-compose.yml）

- `local-deals-mysql`（3306）— MySQL 8，ROW binlog 已开启
- `local-deals-redis`（6379）— Redis 6.2
- `local-deals-es`（9200）— ES 7.17.18 + IK 7.17.18，`discovery.type=single-node`
- `local-deals-canal`（`network_mode: host`）— Canal 1.1.7，监听 `local_deals.tb_shop,local_deals.tb_blog`，连接 `127.0.0.1:9876`（RocketMQ 由 ragent 项目提供）
- RocketMQ：使用 `/home/sd101t/IdeaProjects/ragent` 的 `rocketmq-stack-5.2.0.compose.yaml` 提供，namesrv 9876 / broker 10911

## 下一步计划

恢复时先运行：
```bash
git log --oneline -5
docker ps
~/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn -Dtest='*IT' test
```

后续可做：
1. 将 12 个本地提交推送到远端（`git push`）
2. 面试讲法准备（参考 `docs/superpowers/specs/2026-06-30-es-rocketmq-websocket-design.md` 的”面试叙述要点”）
3. 补充 Canal 端到端验证（当前 CanalSyncIT 直接调用 consumer；若要验证完整管道需解决 Canal RocketMQ connector 与 RocketMQ 5.x 兼容性问题）

## 最近更新

按时间倒序记录，最新内容放在最前面。每次只记录“做了什么、改了哪些文件、得到什么结论”，不要记录完整聊天过程。

### 2026-06-30

ES + RocketMQ + Canal + WebSocket 第二阶段全部完成并通过 code review。

- 新增 `src/main/java/com/localdeals/dto/ShopDoc.java`、`BlogDoc.java` — ES 文档模型，`@Document`、IK 分词、`@GeoPointField`
- 新增 `src/main/java/com/localdeals/config/ElasticsearchConfig.java` — 覆写 `elasticsearchOperations()` 使 `ElasticsearchRestTemplate` 可注入（Spring Data ES 4.0.9 兼容性修复）
- 新增 `src/main/java/com/localdeals/init/ShopIndexInitializer.java` — `@PostConstruct` 批量导入，`@Profile("!test")` 防止测试时运行
- 修改 `IShopService`/`ShopServiceImpl`/`ShopController` — 新增 `searchShops(keyword, x, y, radius, typeId, current)`；`typeId` 为 `Long`
- 修改 `IBlogService`/`BlogServiceImpl`/`BlogController` — 新增 `searchBlogs(keyword, current)`
- 新增 `src/main/java/com/localdeals/mq/SeckillOrderMessage.java`、`SeckillOrderProducer.java`（事务消息 + Lua）、`SeckillOrderConsumer.java`、`EsSyncConsumer.java`、`CanalMessage.java`
- 大幅简化 `VoucherOrderServiceImpl`：删除全部 Redis Stream 消费代码（525→109 行）
- 新增 `src/main/java/com/localdeals/websocket/`：`SeckillWebSocketHandler`、`WebSocketAuthInterceptor`、`WebSocketNotifier`
- 新增 `src/main/java/com/localdeals/config/WebSocketConfig.java`
- 新增 `docker/elasticsearch/Dockerfile`（ES 7.17.18 + IK）、`docker/canal/Dockerfile`（修复 plugin 目录和 instance.properties）
- 新增 `docs/improvement-comparison.md` — before/after 对比数据
- 测试全通：`ShopSearchBeforeIT`（2/2）、`ShopSearchAfterIT`（2/2）、`SeckillWithRocketMQIT`（1/1）、`CanalSyncIT`（3/3）、`SeckillWebSocketIT`（1/1）
- 关键 API 兼容性经验：rocketmq-spring-boot-starter 2.2.3 的 `@RocketMQTransactionListener` 没有 `txProducerGroup` 属性，用 `rocketMQTemplateBeanName = "rocketMQTemplate"`；Spring Data ES 4.0.9 的 `createWithMapping()` 不存在，需 `ops.create(); ops.putMapping(ops.createMapping())`；`IndexCoordinates` 在 `...core.mapping` 包而非 `...core`

### 2026-05-20

- 补充 `docs/benchmark-results.md` 和 `docs/reliability-results.md` 的表格阅读说明：解释 `Throughput`、`P95/P99`、`drain_ms`、`pending`、`dead-letter`、`retry_key_count` 等字段，并明确 baseline 正常压测通过不等于异常链路可靠。
- 补齐 `reliable-stream-v1` 的 5000 线程正常压测：`2026-05-20-seckill-reliable-stream-v1-5000t-1l-r1`，订单 1000/1000，pending 0，dead-letter 0，`drain_ms=81`，结果为 `pass`。
- 补齐 `reliable-stream-v1` 故障注入：`2026-05-20-seckill-reliable-stream-v1-fault-injection-r1`，缺少 `orderId` 的异常消息经过 3 次重试进入 `stream.orders.dlq`，pending 清空，结果为 `pass`。
- 新增 `docs/reliability-results.md` 作为故障注入人工总览；更新 `docs/benchmark-results.md` 和 `docs/seckill-comparison-test-runbook.md`，将本轮对比状态从“待执行”改为“已补齐”。
- 更新 README 首页表达，把“相比教程原型的核心优势”和“对比验证摘要”前置，明确优势是可靠性闭环、可观测性和自动化验证，而不是性能大幅提升。
- 新增 `docs/seckill-comparison-test-runbook.md` 作为秒杀对比验证恢复入口，记录 baseline worktree、数据库差异、正常压测命令、故障注入命令、证据字段、通过标准和当前中断点。
- 当前对比验证采用 git worktree，不在主仓库 `git switch` 回旧版本；baseline worktree 为 `/home/sd101t/IdeaProjects/hm-dianping-baseline`，最终证据仍写回主仓库。
- 当前环境必须区分：baseline 使用 `hmdp-mysql` 容器中的 `hmdp` 数据库，current 使用同一 `hmdp-mysql` 容器中的 `local_deals` 数据库，Redis 均为 `hmdp-redis`。
- 已实现 `scripts/run-seckill-reliability-check.sh`，用于向 `stream.orders` 注入缺少 `orderId` 的异常消息并记录 pending / DLQ 结果。
- 已更新 `scripts/run-seckill-benchmark.sh`，支持 `--project-dir`、`--output-root` 和 `--jmeter-plan`，便于用主仓库脚本驱动 baseline worktree 并把证据统一写回主仓库。
- 当前有效结果：baseline/current 的 1000/5000 正常压测均通过；baseline 故障注入表现为 pending 残留且 DLQ 为空，current 故障注入表现为 pending 清空且 DLQ 生成异常消息。
- baseline 首两次 1000 线程尝试因为旧 JMX 硬编码 token 路径导致 2 个 401、订单 998/1000，应清理或明确标记为废弃证据，不纳入对比结论。

### 2026-05-19

- 本次收尾决策：今天暂不继续新增代码，停止在已推送的 `a9ae77a Repackage local deals seckill reliability` 之后；原计划中的故障注入脚本和 `docs/reliability-results.md` 尚未实现。
- 当前本地环境结论：新业务库 `local_deals` 是直接建在旧 Docker 容器 `hmdp-mysql` 中，Redis 仍使用 `hmdp-redis`；当前阶段不需要额外新建 MySQL/Redis 容器。
- 压测脚本现在从 `.env` 的 `LOCAL_DEALS_*` 自动读取 MySQL/Redis 连接参数，无需传入容器名。
- 对当前改进完备性的判断：已经可以作为第一版可靠性改造展示，但还不是“完备的生产化秒杀项目”。当前结果主要证明正确性、幂等兜底、异步消费可靠性和压测自动化；性能提升不是主要卖点。
- 当前主要短板：缺少消费失败场景证据、Prometheus 指标样例/截图、订单状态补偿闭环、CI/一键演示，以及和原黑马点评教程版更强的产品化差异说明。
- 下次如果继续实现，推荐优先做“异常闭环验证”：测试侧注入缺少 `orderId` 的 Redis Stream 消息，确认 pending 重试上限生效并进入 `stream.orders.dlq`，再把结果写入文档。
- 使用当前本机旧 Docker 容器 `hmdp-mysql` / `hmdp-redis` 和新业务库 `local_deals` 跑通 `reliable-stream-v1` 四组压测。
- `reliable-stream-v1` 结果已写入 `docs/JmeterTestSummary/seckill-reliable-v1/` 和 `docs/benchmark-results.md`：100、1000、5000、25000 请求四组均 `pass`，pending 为 0，dead-letter 为 0，`drain_ms` 为 72-80 ms。
- 不传 `--voucher-id` 时，工具自动创建或复用 `local_deals` 中的 benchmark 秒杀券；连接参数从 `.env` 读取。
- README 增加“项目亮点 / 相比教程版的改造”和可靠性增强版压测摘要，用于对外展示。
- 项目对外名重塑为 `local-deals-service`，Maven 坐标、Spring 应用名、Java 包名和主类迁移到 `com.localdeals` / `LocalDealsApplication`。
- 引入 Flyway，新增 `db/migration/V1__baseline_schema.sql` 和 `V2__voucher_order_constraints.sql`，用唯一索引作为一人一单的 DB 最终兜底。
- 改造 `VoucherOrderServiceImpl`：Redis Stream key/group/consumer/read-count/worker-count/max-retry/dead-letter 配置化，消费者支持批量读取、pending 重试上限和 dead-letter Stream。
- 落库流程改为先插入订单触发唯一索引幂等兜底，再扣减 DB 库存；库存扣减失败会抛异常回滚订单插入。
- 引入 Micrometer Prometheus Registry，暴露秒杀请求结果、消费成功/失败/重试/死信、DB 幂等冲突、Stream 长度和 pending 等指标。
- 更新 `scripts/run-seckill-benchmark.sh`，默认新场景为 `seckill-reliable-v1`，并把 dead-letter 数纳入正确性校验。
- 完成配置脱敏：真实 DB/Redis 密码改为通过 `.env` 或环境变量注入，仓库只提交 `.env.example`。
- 已执行 `/opt/idea/plugins/maven/lib/maven3/bin/mvn clean -DskipTests package`，打包通过。

### 2026-05-16

- 建立秒杀压测自动化流程，脚本统一执行测试用户/token 准备、秒杀数据重置、JMeter 压测、MySQL/Redis 校验、`drain_ms` 测量和报告输出。
- 参数化 `docs/Summary Report.jmx`，支持通过脚本参数控制线程数、循环次数、库存、用户数、优惠券 ID 等压测变量。
- 清理旧的手工压测文件，只保留当前脚本生成的结果入口和证据文件。
- 更新 `docs/benchmark-results.md`，将它定位为人工阅读总览表，不再堆积所有原始输出。
- 已提交并推送压测自动化相关改动：`511baa6 Automate seckill benchmark reporting`。
- 新增本文档作为后续聊天恢复入口。

## 当前专题：秒杀压测与优化

### 已执行的基线压测

```bash
scripts/run-seckill-benchmark.sh --threads 100 --loops 1 --stock 100 --user-count 1000 --voucher-id 11
scripts/run-seckill-benchmark.sh --threads 1000 --loops 1 --stock 1000 --user-count 2000 --voucher-id 11
scripts/run-seckill-benchmark.sh --threads 5000 --loops 1 --stock 1000 --user-count 5000 --voucher-id 11
scripts/run-seckill-benchmark.sh --threads 5000 --loops 5 --stock 1000 --user-count 5000 --voucher-id 11
```

结果以 `docs/benchmark-results.md` 和 `docs/JmeterTestSummary/seckill-baseline-lua-stream/metrics.csv` 为准。

### 当前观察

- baseline 与 `reliable-stream-v1` 两组结果均通过业务正确性校验。
- `reliable-stream-v1` 增加 DB 唯一索引兜底、pending 重试上限、dead-letter 和指标后，2026-05-20 对比验证的 `drain_ms` 为 72-81 ms，和 baseline 的 71-73 ms 处在同一量级。
- 故障注入验证显示 baseline 对缺少 `orderId` 的异常 Stream 消息会留下 pending，`reliable-stream-v1` 会在有限重试后写入 `stream.orders.dlq` 并清空 pending。
- `5000 线程 / 1 次循环` 仍然有 P99 秒级抖动，适合作为压力尖峰观察，不宜单独作为性能提升论据。
- `5000 线程 / 5 次循环` 吞吐较高，主要因为库存耗尽后大量请求走快速拒绝路径，不能简单理解为完整下单能力提升。

### 结果入口

- `docs/benchmark-results.md`
  - 人工阅读入口，只保留压测总览表和简短观察。
- `docs/JmeterTestSummary/seckill-baseline-lua-stream/metrics.csv`
  - 机器可读总表，每轮脚本压测自动更新。
- `docs/JmeterTestSummary/seckill-reliable-v1/metrics.csv`
  - 可靠性增强版机器可读总表。
- `docs/reliability-results.md`
  - 故障注入人工总览，记录 baseline pending 残留和 current 进入 DLQ 的对比结论。
- `docs/reliability-results/metrics.csv`
  - 故障注入机器可读总表。
- `docs/JmeterTestSummary/seckill-baseline-lua-stream/*-run-summary.md`
  - 每轮压测的完整证据快照。
- `docs/JmeterTestSummary/seckill-reliable-v1/*-run-summary.md`
  - 可靠性增强版每轮压测的完整证据快照。
- `summary.csv` 和 `aggregate.csv`
  - JMeter 派生证据，不是主要阅读入口。

## 关键文件

- `scripts/run-seckill-benchmark.sh`
  - 自动执行测试用户/token 准备、秒杀数据重置、JMeter 压测、MySQL/Redis 校验、`drain_ms` 测量、CSV/HTML/summary 输出。
- `docs/Summary Report.jmx`
  - JMeter 脚本已参数化，支持通过 `-Jthreads`、`-Jloops`、`-JvoucherId`、`-JtokensFile` 等参数运行。
- `docs/jmeter-usage.md`
  - 记录自动化脚本用法、参数含义、输出文件命名和 `drain_ms` 含义。
- `docs/seckill-comparison-test-runbook.md`
  - baseline/current 对比验证执行手册，包含 worktree 路径、数据库差异、压测命令、故障注入命令、证据字段和当前中断点。
- `docs/benchmark-results.md`
  - 压测结果人工总览。
- `docs/reliability-results.md`
  - 故障注入结果人工总览。
- `src/main/java/com/localdeals/service/impl/VoucherOrderServiceImpl.java`
  - 秒杀 Lua 判定、Redis Stream 入队、异步消费、pending/dead-letter 和落库幂等的核心实现位置。
- `src/main/java/com/localdeals/config/SeckillProperties.java`
  - 秒杀 Stream 消费配置入口。
- `src/main/resources/db/migration/`
  - Flyway schema 初始化和约束迁移。

## 维护规则

- 新聊天产生的重要决策或修改，追加到“最近更新”，并保持最新日期在前。
- 每次更新优先记录结论、文件路径和下一步，不记录完整对话。
- 如果某个专题变大，可以在“当前专题”下新增二级标题，例如“商铺缓存优化”“登录鉴权整理”。
- `benchmark/` 下的 JTL、HTML 报告、token 和 reset log 默认不提交。
- 终端中没有系统 `mvn` 时，脚本会使用 IDEA 内置 Maven：`/opt/idea/plugins/maven/lib/maven3/bin/mvn`。
- 脚本默认使用 Dragonwell JDK 8：`/home/sd101t/.jdks/dragonwell-ex-1.8.0_472`。

## 重启提示

后续新聊天可以直接粘贴：

```text
请先阅读 /home/sd101t/IdeaProjects/hm-dianping/docs/project-context.md，
再阅读 /home/sd101t/IdeaProjects/hm-dianping/docs/seckill-comparison-test-runbook.md，
再结合当前专题中列出的关键文件恢复 local-deals-service 项目上下文。
当前 main/origin/main 最新提交应为 b4572fe update project-context.md。
当前 baseline 与 reliable-stream-v1 的 2026-05-20 秒杀对比验证已补齐：baseline worktree 在 /home/sd101t/IdeaProjects/hm-dianping-baseline。
baseline 使用 hmdp-mysql 容器中的 hmdp 数据库，current 使用同一 hmdp-mysql 容器中的 local_deals 数据库，Redis 均为 hmdp-redis。
已完成 baseline/current 的 1000/5000 正常压测和故障注入对比；结果入口是 docs/benchmark-results.md 和 docs/reliability-results.md。
压测脚本从 `.env` 读取连接参数，按 runbook 指定 --mysql-database 可覆盖默认数据库名。
```
