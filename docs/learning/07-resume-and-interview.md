# 07. 简历与面试定稿

## 使用前提

项目及其原始版本由作者完整实现。以下内容可以直接作为正式简历材料；证据边界仍按 [06. 项目主张、证据与边界](06-evidence-and-ownership.md) 执行，不能把当前源码、本地隔离验证或历史结果扩大成生产 SLA。

## 一句话项目介绍

面向本地生活交易场景，构建覆盖消费者业务、多租户商户后台、秒杀订单恢复、内容互动和营销发放的 Java 后端系统，重点解决高并发下的一致性、幂等、租户隔离与故障恢复问题。

## 一行技术栈

Java 8、Spring Boot、MyBatis-Plus、MySQL、Redis、Redisson、RocketMQ、Elasticsearch、Flyway、WebSocket、Micrometer/Prometheus、Docker。

## 中文一页简历版

建议正文保留三条，每条都包含问题、方法和工程价值：

- 面向秒杀请求在重复消费、服务重启和依赖异常下的状态不一致问题，构建 Redis 原子准入、RocketMQ 事务消息、MySQL 幂等落库、订单状态机与定时对账组成的恢复链路，使未决请求能够被识别、重试、安全补偿或隔离，避免将不确定状态直接判定为失败。
- 面向消费者与商户后台共用系统时的越权和跨租户访问风险，拆分两套身份域，构建 RBAC、商户范围校验、资源归属复核和条件化数据访问，并通过凭据版本与一次性 WebSocket ticket 处理密码变更、撤权和长连接隔离。
- 面向点赞重试反转、关系不可审计和热榜缓存损坏问题，将点赞改为目标状态写入，以持久关系和事务 Outbox 驱动有界聚合；热榜采用代际隔离、完整快照发布和数据库回退，使聚合任务可重放、派生榜单可重建。

投递电商营销、用户增长或 CRM 方向时，可用下面一条替换第三条：

- 面向领取、签到奖励、管理员发放和批量任务之间规则不一致的问题，构建统一幂等发放链路，以活动级并发仲裁、规则版本、成员快照和可恢复 item 状态机控制额度与重试，并通过事务 Outbox 将持久发券事实与实时通知解耦。

## 招聘网站详细版

1. 秒杀高并发与订单恢复：针对入口接受、消息提交、数据库落单和状态回写无法组成单一事务的问题，构建原子准入、事务消息、数据库幂等写入和可恢复订单状态机；对数据库暂态异常保留待处理状态，对可证明未落库的请求执行受控补偿，对归属冲突进入隔离区，从而让异常请求保留明确现场和后续处置路径。
2. 多租户后台 RBAC 与数据隔离：针对消费者身份误入后台、仅靠接口权限无法约束资源归属以及长连接撤权滞后的问题，构建独立后台会话、角色权限、平台/商户范围、服务层归属检查和数据层条件查询；每次请求重载当前账号与权限，实时连接在发送前再次校验，降低跨商户读写与撤权后继续接收消息的风险。
3. 点赞持久化与可重建热榜：针对 toggle 请求不可安全重试、单一计数缺少用户关系以及旧构建器覆盖新榜的问题，采用目标状态接口、持久点赞关系和事务 Outbox，按有界批次维护聚合计数；热榜使用代际栅栏、临时榜单原子发布、单航班预热与稳定排序回退，避免把缓存错误解释为空榜。
4. 营销幂等发放与批处理：针对多入口发券可能重复扣减额度、批量任务中途失败和实时通知反向影响业务提交的问题，统一用户领取、管理员发放、签到奖励和批量发放规则；以活动锁、规则版本和唯一幂等边界收敛并发，通过快照—执行复核分离和可恢复 item 状态机处理暂停、恢复与失败重试，并用通知 Outbox 保留 Redis 不可用时的持久待发送现场。

## English resume version

Project: Local-deals backend for consumer transactions, multi-tenant merchant operations, content engagement, and campaign-based voucher distribution, with an emphasis on consistency, idempotency, tenant isolation, and recoverability.

Tech stack: Java 8, Spring Boot, MyBatis-Plus, MySQL, Redis, Redisson, RocketMQ, Elasticsearch, Flyway, WebSocket, Micrometer/Prometheus, and Docker.

- Built a recoverable flash-sale order pipeline for duplicate delivery, restarts, and dependency failures by combining atomic admission, transactional messaging, idempotent database persistence, explicit order states, scheduled reconciliation, controlled compensation, and conflict quarantine.
- Implemented a multi-tenant administration boundary with separate consumer/admin identities, role-based permissions, merchant-scoped resource checks, conditional data access, credential-version invalidation, and short-lived one-time tickets with authorization revalidation for WebSocket delivery.
- Reworked blog likes into desired-state commands backed by durable user relationships and a transactional Outbox; maintained counters through bounded replayable aggregation and published a generation-fenced, rebuildable top-K ranking with a stable database fallback.
- Unified user claims, administrative grants, daily rewards, and bounded batch distribution behind one idempotent grant workflow; used campaign-level concurrency control, versioned rules, immutable target snapshots, recoverable item states, and a notification Outbox that does not roll back committed grants when realtime delivery fails.

## 简历描述与证据映射

这张表不放进简历正文，用于面试前反向核验。每条描述先回到对应精读页，再定位生产源码、迁移和测试。

| 简历特色 | 详细分析 | 代表源码与数据约束 | 代表测试/历史证据 | 可陈述到什么程度 |
| --- | --- | --- | --- | --- |
| 秒杀一致性与恢复 | [03. 秒杀下单、PROCESSING 与恢复](03-seckill-order-chain.md)、[06. 特色 1](06-evidence-and-ownership.md#1-秒杀高并发与订单一致性恢复) | `VoucherOrderServiceImpl`、`SeckillOrderProducer/Consumer`、`SeckillOrderStateService`、`SeckillOrderReconciler`、当前 Lua 与 V2 约束 | `SeckillOrderStateIT`、`VoucherOrderReliabilityIT`、consumer/reconciler tests、Pre-M8 历史结果 | 当前实现和限定故障模型下具有可追踪恢复路径；不声称生产零超卖或消息零丢失 |
| RBAC 与商户隔离 | [02. 商户后台、RBAC 与 WebSocket](02-admin-rbac-chain.md)、[06. 特色 2](06-evidence-and-ownership.md#2-多租户后台-rbac-与商户数据隔离) | admin session/authorization interceptors、scoped services/mappers、V5/V6、WebSocket auth/handler | `AdminRbacIT`、MVC/interceptor/session/WS tests | 证明身份、permission、scope 和发送前复核契约；不扩大为生产 IAM/审计平台 |
| 点赞 Outbox 与热榜 | [04. 点赞关系、Outbox 与热榜](04-blog-like-hot-rank-chain.md)、[06. 特色 3](06-evidence-and-ownership.md#3-点赞可靠持久化与可重建热榜) | `BlogLikeCommandService`、`BlogLikeOutboxBatchService`、`BlogHotRankService`、两段 Lua、V7/V8 | command/outbox/reliability/hot-rank/after-commit tests | 证明关系—Outbox—aggregate 与坏榜回退契约；top-K 和本地结果不是生产排行榜 SLA |
| 营销 Grant、Batch 与通知 | [05. 营销发放源码级精读](05-marketing-grant-chain.md)、[06. 特色 4](06-evidence-and-ownership.md#4-营销发放幂等批处理状态机与可靠通知) | grant facade/transaction service、batch job/item services、notification outbox、V9～V11 | M6A/M6B/M6C IT、notification/MVC tests、三阶段历史结果 | 证明幂等、额度、状态收敛与 Redis 故障下持久现场；不声称通知必达或生产批处理 SLA |

## 30 秒项目介绍初稿

这是一个面向本地生活交易的 Java 后端项目，覆盖消费者、商户后台、内容互动和营销发券。我重点解决的不是简单接口开发，而是高并发和异步链路中的一致性问题：秒杀通过原子准入、事务消息、幂等落库和定时对账保留可恢复现场；后台通过独立身份、RBAC 和商户范围校验防止跨租户访问；点赞和营销则用事务 Outbox、可重放批处理和可重建读模型处理重试与依赖故障。当前结论有源码、单元测试和专用依赖下的历史验证支撑，但不会把这些本地结果描述成生产 SLA。

## 高频面试追问

| 问题 | 回答主线 |
| --- | --- |
| Redis 和 MySQL 谁是真相？ | 按业务对象回答：订单、点赞关系、发券、签到和 Outbox 是持久事实；Redis 分别承担准入状态、会话、派生榜单和在线提示。 |
| 为什么不能把数据库查询不到直接当成秒杀失败？ | 查询异常与确定不存在必须分开；无法确认时保留待处理状态，等待消息重试或对账，只有满足精确归属和超时条件才补偿。 |
| RBAC 注解为什么不够？ | 注解只检查功能权限，资源归属还要由 principal scope、Service 校验和带商户条件的数据访问共同约束。 |
| Outbox 为什么能支持重试？ | 业务事实和事件意图同事务提交，worker 只处理并锁定有限 pending 行，业务更新和 marker 同事务或依靠下游幂等收敛。 |
| 缓存坏了为什么不会返回空榜？ | 读取同时校验 ready、代际、数量、容量和发布时间；任一不一致都显式回退稳定排序的数据库查询。 |
| 通知状态为什么不代表用户收到？ | 它只记录 publish 调用和数据库 marker；用户可能离线或本地连接不存在，持久业务结果仍以数据库查询为准。 |

## 表达边界

可以直接写“设计、实现、构建、完善”，但不要写“完全避免超卖”“保证消息百分之百送达”“生产级高可用”或“达到生产 SLA”。Java 17/Spring Boot 3、Redis Cluster/Sentinel、支付/退款/核销、完整离线通知和 Canal 全链路 E2E 也不属于当前已完成范围。

测试类源码描述的是测试契约；正式 runner 注入的专用依赖描述的是隔离来源；结果文档描述的是过去某次限定环境运行。本阶段没有重新执行测试、服务、迁移、故障实验或压测。

三条核心业务的概括版、完整版与追问见 [09. 三条核心业务面试讲述稿](09-core-business-chain-review.md)；项目总览、登录、搜索、关注流、后台权限和 WebSocket 见 [08. 补充模块面试讲述手册](08-interview-playbook.md)。
