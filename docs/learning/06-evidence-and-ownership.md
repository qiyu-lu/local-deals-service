# 06. 项目主张、证据与边界

## 这份表要解决什么

本页用于把项目特色拆成可验证的工程主张：解决了什么问题，采用什么方案，源码和测试在哪里，结论能说到哪一步。项目及其原始版本均由作者完整实现，后续不再逐模块审计设计、实现或测试归属；需要持续审计的是技术主张和证据强度，避免把源码存在、历史测试通过或本地计时扩大成生产效果。

## 当前 checkout 与事实优先级

本次阅读基线为当前 checkout 的 branch `codex/platform-hardening`、HEAD `9525b65e7aa88fa8e04670f1054cea5d5d752098`。若结果文档记录的源码 commit、端口、配置或运行规模不同，应同时保留，而不是挑选更有利的一项。

事实优先级如下：

1. 当前源码、测试和 V1–V11 数据库约束。
2. 标记为 CURRENT 的设计文档。
3. `docs/evidence/**` 的结果和机器可读摘要；它们是仓库记录的既有证据。
4. 历史 plan/roadmap。
5. `docs/archive/**`，只用于追溯。

## 证据等级怎么读

| 等级 | 能说明什么 | 不能说明什么 |
| --- | --- | --- |
| unit-and-isolated-it | 方法契约、状态边界、限定依赖下的行为 | 生产容量、HA、未覆盖链路 |
| isolated-real-it | 在专用真实依赖和明确故障模型下的不变量 | 所有部署拓扑、生产 SLA |
| consumer-level | 真正 consumer 的重试/DLQ/ES apply 语义 | Canal Server→RocketMQ→consumer→ES 完整 E2E |
| real-http-plus-contract | HTTP 入口、局部 bulkhead 和错误码行为 | 多实例全局流控、生产容量 |
| contract/manual-demo | DTO、权限、频道或前端交互契约 | 持久化、断电恢复或完整用户体验 |
| historical-baseline | 某次旧版本/旧 run 的观察 | 当前会话重新复现、当前生产结论 |

## 四项项目特色：主张—证据—边界

下面每项都按“场景问题 → 核心方案 → 关键机制 → 异常恢复 → 验证证据”组织。简历只保留业务可读的压缩表述，类名、Key、表和测试名留在本页用于反向核验。

### 1. 秒杀高并发与订单一致性恢复

| 维度 | 内容 |
| --- | --- |
| 场景问题 | 秒杀流量集中，入口成功、消息提交、数据库落单和状态回写并非同一原子步骤；重复消费、服务重启、数据库异常或订单标识冲突都可能留下未决现场。 |
| 核心方案 | 以 Redis 原子准入和精确预约承接高并发入口，以 RocketMQ 事务消息异步交付，以 MySQL 订单与库存作为长期事实，并用 PROCESSING 状态机、定时对账和隔离区收敛不确定状态。 |
| 关键机制 | `VoucherOrderServiceImpl#seckillVoucher/#createVoucherOrder`、`SeckillOrderProducer#executeLocalTransaction/#checkLocalTransaction`、`SeckillOrderConsumer#onMessage`、`SeckillOrderStateService`、`SeckillOrderReconciler#reconcileDueOrders`；V2 用户—券唯一约束、条件库存更新和 exact reservation/owner 校验共同仲裁重复请求与冲突。 |
| 异常恢复 | 数据库暂态错误保留 PROCESSING 并重试；数据库已提交而 Redis 回写失败时由状态查询或对账修复 SUCCESS；可证明未落库且满足安全前提时才补偿；订单标识归属冲突进入 suspend + quarantine，禁止盲目回补。 |
| 验证证据 | [秒杀源码级精读](03-seckill-order-chain.md)；`SeckillOrderStateIT`、`VoucherOrderReliabilityIT`、`SeckillOrderConsumerTest`、`SeckillOrderReconcilerTest`、`SeckillOrderReconciliationLockTest`；[Pre-M8 历史结果](../evidence/pre-m8/pre-m8-baseline-results.md)。 |

边界：现有证据支持当前源码契约、专用依赖下的状态收敛和有限故障模型，不支持“完全避免超卖”“消息百分之百送达”、生产 RocketMQ/Redis/MySQL 高可用或生产容量结论。

### 2. 多租户后台 RBAC 与商户数据隔离

| 维度 | 内容 |
| --- | --- |
| 场景问题 | 消费者与后台账号共享应用入口，但后台还要处理权限变化、跨商户资源枚举、账号/商户停用以及长连接建立后的撤权。 |
| 核心方案 | 独立消费者和后台身份域；后台请求依次执行 session 重建与 permission 检查，再由 Service 和 scoped SQL 复核 merchant 归属；实时订单连接使用短期一次性 ticket 并在每次发送前重验。 |
| 关键机制 | `AdminSessionService#resolve/#resolveAccount` 每次从 Redis session 回 MySQL 加载 account、merchant、role、permission 和 authVersion；`AdminAuthorizationInterceptor#preHandle` 检查显式 permission；`AdminCatalogService#requireScopedShop`、`MarketingAdminService#resolveMerchant` 与 scoped Mapper 形成纵深隔离。 |
| 异常恢复 | 密码或账号状态变化使旧 token 在下一次 resolve 失效；跨商户资源按 403 或 404 处理；WebSocket ticket 过期/复用拒绝握手，撤权、停用或 scope 变化使连接在下一次发送前关闭；商户路由解析失败时不退化为跨商户广播。 |
| 验证证据 | [RBAC 与 WebSocket 源码级精读](02-admin-rbac-chain.md)；`AdminRbacIT`、`AdminMvcSecurityTest`、`AdminAuthorizationInterceptorTest`、`AdminSessionServiceTest`、`AdminWebSocketAuthInterceptorTest`、`WebSocketSessionIsolationTest`。 |

边界：当前没有细粒度自定义角色平台、生产统一身份中心、完整后台审计系统或浏览器端到端通知证明；普通后台 session resolve 的依赖异常当前也不能统一表述为 503。

### 3. 点赞可靠持久化与可重建热榜

| 维度 | 内容 |
| --- | --- |
| 场景问题 | 直接修改计数无法回答具体用户关系，超时重试的 toggle 可能反向操作；聚合 worker 重跑可能重复计数，旧热榜构建器可能覆盖新快照，坏缓存还可能伪装成空榜。 |
| 核心方案 | 使用 desired-state PUT/DELETE，将用户点赞关系与不可变增量 Outbox 同事务提交；worker 有界聚合维护计数；Redis 只保存可丢弃、可重建的 top-K 热榜，读取不安全时回退 MySQL。 |
| 关键机制 | `BlogLikeCommandService#setLiked` 以关系唯一键实现重复 no-op；`BlogLikeOutboxBatchService#processNextBatch` 用 pending `FOR UPDATE`、按 blog 聚合和精确 eventId marker 保证可重放；`BlogHotRankService#readPage/#rebuild/#addNewBlogAfterCommit` 与两段 Lua 以 generation fence 发布完整榜单。 |
| 异常恢复 | Outbox 插入失败时关系回滚；aggregate 后 marker 前失败时整个批次回滚；Redis 不可用、metadata 损坏、live key 丢失、榜单过期或 generation 漂移均整页回退数据库，并可由 refresh/warmup 重建。 |
| 验证证据 | [点赞与热榜源码级精读](04-blog-like-hot-rank-chain.md)；`BlogLikeCommandServiceTest`、`BlogLikeReliabilityIT`、`BlogLikeOutboxBatchServiceTest`、`BlogHotRankServiceTest`、`BlogHotRankRedisIT`、`BlogServiceAfterCommitTest`。 |

边界：top-K 不是全量排行榜；热榜自愈不等于点赞 aggregate 自动修复；V8 协议要求停旧节点完成 cutover，不能写成普通滚动升级或生产 Redis RPO 证明。

### 4. 营销发放幂等、批处理状态机与可靠通知

| 维度 | 内容 |
| --- | --- |
| 场景问题 | 用户领取、管理员发放、签到奖励和批量发券若各自实现，会绕过统一额度与幂等边界；长批次、单项失败和实时通知故障又会让任务结果难以恢复。 |
| 核心方案 | 统一 Grant facade 和独立新事务，在活动锁内复核版本、时间窗、资格与额度；批量 Job 先固化成员快照，再以有界 item 状态机调用同一发放链；新 grant 与通知 Outbox 同库提交。 |
| 关键机制 | `VoucherGrantService#grantInternal` 负责服务端幂等字段和事务外竞争重查；`VoucherGrantTransactionService#grantWithResult` 以 REQUIRES_NEW、campaign 行锁、锁后二次查询和唯一键仲裁并发；`VoucherBatchJobService` 维护 SNAPSHOTTING 到 COMPLETED/PARTIAL_FAILED；通知 worker 对 due rows 加锁并记录 publish/retry marker。 |
| 异常恢复 | 新 grant 任一步失败时额度、grant、notification Outbox 一起回滚；grant 已提交而 batch item 未更新时，重跑收敛为 IDEMPOTENT；稳定业务拒绝记 SKIPPED，技术错误记 FAILED 且只重试后者；Redis 通知失败保留 PENDING 并退避，用户最终从持久券包查询。 |
| 验证证据 | [营销发放源码级精读](05-marketing-grant-chain.md)；`M6aBusinessFlowIT`、`MarketingGrantConcurrencyIT`、`M6bDailyTaskBusinessIT`、`M6cBatchBusinessIT`、`M6cRedisRecoveryIT`、`VoucherGrantNotificationOutboxServiceTest`、`MarketingMvcSecurityTest`；[M6A](../evidence/m6/m6a-targeted-grant-results.md)、[M6B](../evidence/m6/m6b-daily-task-results.md)、[M6C](../evidence/m6/m6c-batch-notification-results.md) 历史结果。 |

边界：通知状态不表示用户在线、收到或已读；当前没有 RocketMQ 发券通知、离线消息中心或 DLQ，attempts 到上限仍会按最大退避继续重试；局部批处理与恢复时间不是生产 SLA。

## 结果文档的使用方式

- [M7 证据索引](../evidence/m7/m7-evidence-index.md) 定义了 evidence level、失败矩阵和“PASS 到底证明什么”。先读它，再读各阶段结果。
- [Pre-M8 最终结果](../evidence/pre-m8/pre-m8-baseline-results.md) 是当前阶段的可复用本地快照，但其中部分 run 使用更早的源码 commit；当前 HEAD 的代码和这点版本差异必须同时注明。
- [M6A](../evidence/m6/m6a-targeted-grant-results.md)、[M6B](../evidence/m6/m6b-daily-task-results.md)、[M6C](../evidence/m6/m6c-batch-notification-results.md) 分别记录真实隔离 MySQL/Redis 场景，不能把没有启动的 MQ/ES/Canal 变成已验证依赖。
- `CanalSyncIT` 直接调用 `EsSyncConsumer.onMessage`，所以应称为 consumer-level；即使测试类名带 `IT`，也不能称为 Canal E2E。

## 必须保留的负面或未验证项

| 项目 | 正确说法 |
| --- | --- |
| Redis 故障 | 有局部 fail-closed/fallback 证据；没有生产 HA、RPO 或全量数据丢失结论 |
| MySQL 恢复 | 某次专用 run 的恢复/尾部观察；不能写生产 RTO/SLA |
| RocketMQ | 秒杀事务、consumer retry 和专用 run 有证据；不能推生产集群容量或发券 MQ 已实现 |
| ES/Canal | consumer-level apply 通过；没有完整 Canal Server→ES E2E |
| WebSocket | 在线、精确路由和撤权关闭有契约；没有完整离线通知、用户已读或浏览器 E2E |
| throughput/P99/drain | 限定机器、数据和线程模型的局部观测；不能单独证明正确性或生产 SLA |
| 默认配置 | 批量 worker、通知 worker、hot rank read/refresh、like worker 和 reconciler 有开关，不能默认写成全部启用 |
| 未完成范围 | Redis Cluster/Sentinel、支付、退款、核销、完整离线通知、Java 17/Spring Boot 3 均不能写成已实现 |

## 统一使用前提

- 项目及其原始版本由作者完整实现，可以在简历中直接使用“设计、实现、构建、完善”等工程动词。
- 简历按完整项目能力表达，不再逐模块拆分设计、编码、测试归属，也不按 Git 历史分配个人贡献。
- 作者前提不改变证据边界：源码实现不等于生产部署，历史隔离测试不等于本轮重跑，本地恢复时间不等于生产 SLA。
- 简历正文面向业务问题和工程价值；类名、Redis Key、数据库约束名、测试名只放在本页和 02～05 的反向证据中。

## 不能把这些东西混在一起

- “源码有类/接口”是实现存在性；“测试通过”是某个测试层的证据；“结果文档有 PASS”是某次运行快照；三者不能相互替代。
- “状态 PUBLISHED”是 Redis publish 调用成功且数据库 marker 提交；“用户已看到”仍需要在线客户端行为证据；“离线可取”依赖 MySQL 持久查询，而不是 WebSocket。
- “数据库行数正确”是核心不变量的一部分；“HTTP 200”只是 transport envelope，甚至可能包住依赖失败。
- “平均延迟改善”不等于 per-sequence/per-family 正确性，也不等于 full trajectory/production SLA；本项目的业务证据优先看不变量、coverage、fallback/recovery 和失败明细。
