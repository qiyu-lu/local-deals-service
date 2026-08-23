# M6C 可审计批量发券与通知 Outbox 计划

> HISTORICAL EXECUTION PLAN：本文保留阶段执行时的契约、状态与负面结果；最终结论以同目录 results 文档为准。

> 状态：M6C COMPLETED；收口结果见 docs/evidence/m6/m6c-batch-notification-results.md
>
> 日期：2026-08-23
>
> 分支基线：`codex/platform-hardening` / `38006564affb1edd0d884ce3829f0c9e85cf0faf`

## 1. 目标与边界

M6C 只补齐一条可审计的批量发券闭环：商户后台提交一个不可变目标快照 Job，后台 worker
按小批次调用 M6A/M6B 已有的 `VoucherGrantService`，真正新增 grant 时在同一数据库事务内
写入通知 Outbox，再由默认关闭的 Redis Pub/Sub worker 投递给用户 WebSocket。MySQL 中的
Job、item、grant 和 Outbox 是事实；WebSocket 只是在线提示，用户离线时仍以持久化的
`/voucher-grants/mine` 为准。

只支持：

- `grantMode=ADMIN` 或 `BOTH`；
- `eligibilityType=MANUAL_TAG`；
- 活动绑定的有效标签成员作为唯一目标人群；
- Job 固定保存 `operator`、`merchant`、`campaign`、`capturedRuleVersion`、`tag` 和目标快照；
- 发券统一复用 `VoucherGrantService`、merchant-scoped campaign lock 和 `ONCE` 幂等边界。

明确不做：用户 SQL/规则表达式、Excel/CSV、全库扫描、推荐或动态人群、多级审批、定时营销、
新 RocketMQ topic/consumer、通知已读、短信、邮件、推送平台、核销、支付和退款。

## 2. V11 数据与状态契约

新增 Flyway V11，必须同时验证 fresh V1→V11 和 upgrade V10→V11。既有 V1→V10 迁移、M6A/M6B
grant、`USER_CLAIM`、`ADMIN_GRANT`、`TASK_REWARD`、历史 `ONCE` 语义不能被回填或重写。
历史 grant 不追溯创建通知。

### 2.1 Job

`tb_voucher_batch_job` 保存商户、活动、操作员、客户端 `request_id`、捕获的规则版本、目标标签、
状态、目标数量和 create/start/finish/update 时间。`(merchant_id,request_id)` 唯一；merchant、
status、id 和外键索引齐全。状态为：

`SNAPSHOTTING -> READY -> RUNNING -> COMPLETED | PARTIAL_FAILED`，并允许
`RUNNING -> PAUSED -> RUNNING`。稳定的规则/资格/额度结果进入 item `SKIPPED`；技术错误进入
`FAILED`。快照完成后不再改变 item 集合。

### 2.2 Item

`tb_voucher_batch_item` 以 `(job_id,user_id)` 唯一保存目标、结果、grant、尝试次数和稳定/技术
错误字段。状态为 `PENDING、GRANTED、IDEMPOTENT、SKIPPED、FAILED`。Job worker 用 MySQL
行锁领取有限批次，不使用 Redis 分布式锁；pause 只在当前小批次结束后生效。

### 2.3 Notification Outbox

`tb_voucher_grant_notification_outbox` 以 `grant_id` 唯一保存 `merchant_id、user_id、event_type、
status、attempts、next_attempt_time、last_error、create_time、published_at`。新增 grant 和
Outbox 必须同事务提交；任何 Outbox 写失败都回滚 grant 与 `granted_count`。`PUBLISHED` 只代表
Redis 接受发布，不代表在线、已读或实际收到。Outbox worker 以小批次、有上限退避和至少一次
语义运行；消息带 `eventId、grantId、campaignId、voucherId` 和安全文案。

`tb_voucher_grant.source` 增加 `BATCH_GRANT`；历史行不需要通知回填。

## 3. Job 与统一发券流程

1. 创建请求只校验活动归属、`ADMIN/BOTH`、`MANUAL_TAG`、有效绑定标签、操作员和
   `requestId`，在事务内插入 Job；不会扫描成员或发券。相同商户的相同 `requestId` 返回原 Job。
2. worker 显式开启后，将 Job 从 `SNAPSHOTTING` 变为后台执行的 `INSERT ... SELECT` 快照：
   只插入活动绑定标签的有效成员。快照完成后写 `target_count` 并转 `READY`；快照后新加入或
   移除的成员不改变 item 集合，但发券时仍重新校验当前资格。
3. 发券按有限 batch 领取 item。每个 item 只通过内部 `VoucherGrantService` 结果对象获取
   `GRANTED` 或 `IDEMPOTENT`；Controller 不先查后写。实际新增 grant 写 `source=BATCH_GRANT`、
   Job 的 merchant/operator 和 `ONCE` key，并同事务写 Outbox。
4. 提前领取、管理员发放或其他 Job 已产生同活动用户 grant 时，item 为 `IDEMPOTENT` 且不增加
   额度、不写第二条 Outbox。
5. 规则版本变化、标签失效/当前不再具备资格、活动失效、额度耗尽等稳定结果为 `SKIPPED`，
   写稳定错误码；数据库、锁或 Redis 等技术错误为 `FAILED`，只能由 retry-failures 重置。
6. Job 统计从 item 状态得到，终态只有全部 item 为终态且无 FAILED 时 `COMPLETED`，否则
   `PARTIAL_FAILED`。规则变化不替换 `capturedRuleVersion`，管理员应创建新 Job。

## 4. API、权限与前端

后台沿用 `marketing:read/write` 与 merchant scope：

- `POST /admin/marketing/campaigns/{id}/batch-jobs`；
- `GET /admin/marketing/batch-jobs`；
- `GET /admin/marketing/batch-jobs/{id}`；
- `GET /admin/marketing/batch-jobs/{id}/items`；
- `POST /admin/marketing/batch-jobs/{id}/pause`；
- `POST /admin/marketing/batch-jobs/{id}/resume`；
- `POST /admin/marketing/batch-jobs/{id}/retry-failures`。

响应只暴露安全 DTO，不回传任意内部异常或跨商户对象。管理端只展示符合条件的活动，提供最小
Job 面板、状态/目标/结果计数、pause/resume/retry 和失败明细分页；轮询遇到终态立即停止，
不能无限轮询。用户端监听独立 `ws:voucher-grant:{userId}` 频道，收到 `VOUCHER_GRANTED` 后
做一次去重提示并刷新持久 grant 列表，不建设消息中心。

## 5. worker、指标与隔离

Job worker 和 Outbox worker 默认关闭，测试/演示通过显式配置开启。两者都按有限 batch 执行，
Job 依赖 MySQL 行锁协调多实例，Outbox 以 pending/next-attempt 索引取数。指标只使用低基数
`source=batch_grant`，不包含 job、campaign、merchant、user 等 ID；验证必须报告 target、
granted、idempotent、skipped、failed、剩余额度、worker batch 数、总收敛时间、item P99（若未
测量则 `NA`）、Outbox pending、oldest age 和 Redis 恢复时间。入口未限流时限流数写 `NA`，
不能用瞬时零冒充测量。

真实依赖验证使用新的 M6C run-id、专用 MySQL 和专用 Redis。每次测试先经过 fail-closed
datasource/Redis guard；strace 只允许本次 MySQL、Redis 端口和本机 Unix socket，禁止连接
3306、6379、9876、10911、9200 或未知目标。不启动 RocketMQ/ES。专用容器和网络只能按精确
run-id/名称清理，不执行 broad prune。

## 6. 验收与停止线

核心测试覆盖：requestId 并发幂等、快照不可变与发券时资格复核、100 item 状态/额度守恒、提前
领取和 Job 竞争的 `ONCE` 收敛、grant 已提交而 item 未更新时重跑、pause/resume、只 retry
技术 FAILED、规则/标签/额度/商户边界、grant+count+outbox 原子性、幂等不重复 Outbox、Redis
停止后的 PENDING 与恢复发布、真实 pending/oldest age/recovery time，以及 M6A/M6B 回归。

以下任一情况停止本次真实依赖运行并保留证据：出现共享或未知连接；grant/outbox 无法同事务；
批量逻辑绕过 `VoucherGrantService`；后台 worker 默认开启；必须引入 RocketMQ、任务 DSL、通用
审批或新规则平台才能继续。普通代码或命令错误修正后使用新 run-id 重跑，不把它永久记为环境
BLOCKED。

## 7. 收口节点

按以下意图分段提交，不 push：

1. `docs(marketing): define M6C batch delivery contract`
2. `feat(marketing): add auditable batch grant jobs`
3. `feat(marketing): add transactional grant notification outbox`
4. `feat(frontend): expose batch delivery progress`
5. `test(marketing): verify M6C delivery and recovery invariants`
6. `docs(marketing): close M6 modernization stage`

最终文档必须明确 `M6C COMPLETED`、`M6 COMPLETED`、`M7 not started`，保留失败尝试、测试边界和
`NA` 指标，不 push，工作区 clean。
