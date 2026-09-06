# 05. 标签、活动、签到、统一发券与通知：源码级精读

## 业务场景

商户可以创建标签和活动，用户查看店铺可领取的券；用户完成每日签到后可以领取 TASK reward；管理员可以按标签建立一次性批量 Job。发券必须受活动时间、规则版本、资格、额度和“一人一份”约束，实时通知失败不能撤销已经提交的券。

## 旧方案

M6 之前的 tutorial 业务更接近单次领取和直接写券/计数；签到的历史思路也曾考虑 Redis bitmap。当前实现把签到日期和发券流水放到 MySQL，以服务端业务日期生成任务幂等键；M6A/M6B/M6C 再把 admin grant、TASK reward、batch grant 和通知 Outbox 收敛到统一 `VoucherGrantService`。

## 问题

如果先查额度再写 grant，100 个并发请求可能把 `granted_count` 加过头；如果用户领取和管理员发放使用两套写逻辑，二者会互相绕过幂等或资格校验。批量任务若直接遍历用户列表，成员在任务创建/执行期间变化、worker 崩溃或单个用户技术失败都会让结果无法解释。通知若与发券共用同步请求，则 Redis/WebSocket 故障会反过来阻塞或回滚业务事实。

## 业务不变量

- campaign、tag、tag member、voucher、shop 必须属于同一 merchant；admin API 的 scope 由 principal 和 SQL 双重约束。
- campaign 的 `rule_version`、expected status/version、时间窗、grant mode、绑定普通券和额度在一次 MySQL 事务中复核。
- grant 新增前先锁 campaign 并二次查询 existing；`USER_CLAIM`/`ADMIN_GRANT`/`BATCH_GRANT` 使用 `ONCE` 语义，`TASK_REWARD` 使用服务端生成的当天 key。
- 真正新 grant、`granted_count + 1`、`tb_voucher_grant` 和一条 `VOUCHER_GRANTED` notification Outbox 必须同事务提交；幂等结果不能增加额度或产生第二条通知。
- TASK reward 必须存在当天 `tb_sign` 事实；签到自身靠 `tb_sign(user_id,date)` 唯一键幂等，签到连续天数直接从 MySQL 日期计算。
- batch Job 先固定 active tag member 快照，后续逐 item 重新复核当前资格；item 的稳定业务拒绝进入 `SKIPPED`，技术错误进入 `FAILED`，只重试后者。
- `PUBLISHED` 只表示 Redis Pub/Sub 接受发布；用户是否在线、是否看到消息、是否已读都不是该状态的含义。用户以 `/voucher-grants/mine` 查询持久事实。

## M6A：统一 Grant 与定向发券

### 三条入口与 HTTP 身份

| source | 发起者与路由 | command 中客户端/入口提供 | facade 强制拥有 | operator/merchant |
| --- | --- | --- | --- | --- |
| `USER_CLAIM` | 登录消费者 `POST /voucher-campaigns/{id}/claim` | campaignId、expectedRuleVersion、current userId | `idempotencyKey=ONCE`、taskDate=null | operator=null、merchant=null；事务从 campaign 解析 |
| `ADMIN_GRANT` | 有 `marketing:write` 的后台 `POST /admin/marketing/campaigns/{id}/grants` | userId、expectedRuleVersion；`resolveMerchant` 得 scope | `idempotencyKey=ONCE`、taskDate=null | operator=current admin accountId、merchant=resolved scope |
| `TASK_REWARD` | 登录消费者 `POST /voucher-campaigns/{id}/task-reward` | campaignId、expectedRuleVersion、current userId | server today、`TASK_REWARD:DAILY_SIGN_IN:yyyy-MM-dd` | operator/merchant 必须 null |
| `BATCH_GRANT` | batch worker，不直接暴露单用户 HTTP | Job 捕获的 campaign/merchant/user/version/operator | `idempotencyKey=ONCE`、taskDate=null | operator/merchant 来自 durable Job |

消费者入口由 `LoginInterceptor` 要求普通 user session，后台入口由 RBAC interceptor 要求 admin identity + `marketing:write`；两套身份不能互换。成功返回 HTTP 200 + `Result.ok(grant/view)`；参数错误 400，稳定活动拒绝 409，数据库不可用由 facade 映射 503，未登录/无后台 permission 分别是 401/403。

### facade 与 REQUIRES_NEW 成功链

`VoucherGrantService` 故意不加事务。它必须让 transaction bean 中的 DuplicateKey 竞争完整回滚（尤其是 `granted_count + 1`），回到事务外之后才做幂等 re-read；若 facade 自身包着同一事务，捕获数据库异常后继续查询可能仍处于 rollback-only 或看到错误副作用。

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Controller/UserService | route DTO + current identity | User/Admin holder、permission/scope | 构造 `VoucherGrantCommand` | 无 | 调统一 facade | 身份/scope/参数拒绝，不进入 grant transaction |
| 2 | `VoucherGrantService#grant/grantInternal` | command | `BusinessDateProvider` | `prepareServerOwnedFields` 覆盖 taskDate/idempotencyKey | 故意非事务 | server-owned 字段不能被客户端伪造 | command null/非法 source→400 |
| 3 | `VoucherGrantService#findExisting` | source-specific identity | `tb_voucher_grant` | 无 | 事务外快速幂等读 | 已存在直接返回 IDEMPOTENT | DB 异常→503，事务 bean 不调用 |
| 4 | `VoucherGrantTransactionService#grantWithResult` | validated command | TASK 先查当日 `tb_sign`；再首次 transaction-local existing | 无 | public `@Transactional(REQUIRES_NEW)` | 无 existing 才竞争 campaign | 未签到→409 `TASK_NOT_COMPLETED`；已有→IDEMPOTENT、零新副作用 |
| 5 | `#lockCampaign` | campaign/source/merchant | user source 用 `selectForUserClaimForUpdate`；admin/batch 用 `selectScopedForUpdate` | 行锁 | campaign 排他锁是 quota/规则串行边界 | exact campaign | missing/cross merchant→404 |
| 6 | `#grantInternal` 拿锁后复查 | 同一 idempotency identity | `tb_voucher_grant` | 无 | campaign lock 内 | 等锁期间别人已 grant 则 IDEMPOTENT | 不能跳过二次读，否则会重复扣 quota |
| 7 | validation | locked campaign | ruleVersion、grantMode、DB current time、status/window、voucher type/status/shop merchant；MANUAL_TAG 再锁 tag/member | 无 | lock order campaign→tag→member | 当前规则全部满足 | 稳定拒绝均 409；零 grant/quota/outbox |
| 8 | `VoucherCampaignMapper#incrementGrantCount` | campaign/merchant/voucher/version | 条件再次检查 ACTIVE/window/quota | `granted_count+1` | 同一事务；条件更新必须 1 行 | quota 预留 | 0 行重新分类 rule/window/quota 后 409 |
| 9 | transaction service | resolved campaign/command | 无 | insert `tb_voucher_grant`；insert one PENDING notification outbox | 同一 REQUIRES_NEW transaction；唯一键 + FK | created grant、count、outbox 一起 commit | 任一步失败三者回滚；DuplicateKey 逃出 bean |
| 10 | facade DuplicateKey handler | original command | 事务外再次 `findExisting` | 无 | 已失败新事务已回滚 | 找到 winner→IDEMPOTENT | 仍无 existing→异常继续抛出 |

新 grant 的副作用是 count+1、grant row、唯一 notification outbox；idempotent grant 只返回既有 row，不增加 quota、不创建第二条通知。V10 唯一键 `(campaign_id,user_id,idempotency_key)` 让 `ONCE` 与每日 task key 分开；V11 的 notification outbox 又以 `grant_id` 唯一。

### 规则/来源决策

| 条件 | USER_CLAIM | ADMIN_GRANT | TASK_REWARD | BATCH_GRANT |
| --- | --- | --- | --- | --- |
| campaign grantMode | CLAIM/BOTH | ADMIN/BOTH | TASK | ADMIN/BOTH |
| idempotency | campaign+user+`ONCE` | 同一 ONCE，与 USER_CLAIM 竞争同一永久 grant | campaign+user+当天 task key | 同一 ONCE，与 claim/admin/其他 Job 共享 |
| merchant | 从锁定 campaign 得到 | command merchant scoped lock | 从 campaign 得到，command 不准带 | durable Job merchant scoped lock |
| operator | null | 当前后台 account | null | 创建 Job 的后台 account |
| 额外资格 | ALL 或当前 MANUAL_TAG | 同左 | 当日 sign + campaign 资格 | 快照只选候选，执行时仍复核 tag/规则 |

## M6B：签到与每日任务奖励

`tb_sign(user_id,date)` 是签到事实源；V10 在清理历史重复后建立 `uk_sign_user_date`。`UserServiceImpl#sign` 用服务端业务日期插入，DuplicateKey 仍返回 `Result.ok()`，因此同一天重放幂等。它不读写 Redis Bitmap。

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `UserServiceImpl#sign` | `UserHolder.userId` | `BusinessDateProvider#today` | insert `tb_sign(user,year,month,date)` | 单 insert；唯一键仲裁 | 新/重复签到均 HTTP 200 success | 非 DuplicateKey DB 异常传播；不会伪造成功 |
| 2 | `VoucherCampaignUserService#taskReward` | campaign/version/current user | 无 | source=TASK_REWARD command | 无 | 调统一 grant | 参数/身份错误在事务前拒绝 |
| 3 | `VoucherGrantService#prepareServerOwnedFields` | source | Asia/Shanghai today | 覆盖 taskDate/key | 非事务 | key=`TASK_REWARD:DAILY_SIGN_IN:{date}` | 客户端无法选择日期或重放旧日 key |
| 4 | `VoucherGrantTransactionService#grantInternal` | task command | `SignMapper#countByUserAndDate` | 后续同 M6A grant/count/outbox | REQUIRES_NEW | 当日已签到且规则/额度满足才发 | 未签到 `TASK_NOT_COMPLETED`，零副作用 |

`BusinessDateConfiguration` 默认读取 `local-deals.business-time-zone=Asia/Shanghai` 并注入统一 `Clock`/provider；签到、任务 key 和用户活动视图共用该业务日。日期必须服务端生成，否则客户端可伪造明日/昨日 key 绕过每日边界。

普通 `USER_CLAIM/ADMIN_GRANT/BATCH_GRANT` 永远使用 `ONCE`，同 campaign/user 一生一条；TASK 使用每天不同 key，允许跨日新 grant。当天重复 task reward 返回同一 grant；跨日需新一天签到后产生新 grant。未签到→409；ruleVersion 变化→409 且零副作用；额度耗尽→409；日期切换后旧日 sign 不满足新日 task。

`UserServiceImpl#signCount` 查询 `date <= today ORDER BY date DESC`，从 today 开始逐日比较，第一处缺口停止。它计算“截至今天且必须包含今天”的连续天数；昨天以前连续但今天未签返回 0。MySQL 唯一日期行是最终真相，Redis Bitmap 没有参与当前实现，不能用旧教程方案描述。

## M6C：批量 Job 与通知 Outbox

### Batch Job 状态机

HTTP create 是 `POST /admin/marketing/campaigns/{campaignId}/batch-jobs`，要求 admin `marketing:write`。创建只固化 Job 元数据，不在请求线程遍历用户；目标成员由 worker 后续单条 `INSERT ... SELECT` 快照。

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `VoucherBatchJobService#create` | campaignId、merchant/requestId/version、principal | scoped campaign `FOR UPDATE`、active tag | insert Job `SNAPSHOTTING`，捕获 ruleVersion/tag/operator | public `@Transactional`；唯一 `(merchant_id,request_id)` | 并发请求通过 `ON DUPLICATE KEY ... LAST_INSERT_ID` 后都 re-read 同一 durable Job | scope/not found/unsupported mode/tag/version→4xx，零 items |
| 2 | `#snapshotOne` | scheduler tick | 最早 SNAPSHOTTING Job `FOR UPDATE` | `INSERT ... SELECT` active、未过期 tag members；target_count；READY | public transaction；item `(job_id,user_id)` 唯一 | immutable member snapshot | 任一步失败整次 snapshot 回滚，仍可下轮重试 |
| 3 | `#processNextBatch` | bounded batchSize | 最早 READY/RUNNING Job `FOR UPDATE`；有限 PENDING items `FOR UPDATE` | READY→RUNNING | public outer transaction | 锁定本批 items | PAUSED Job 不被选；空队列 finalize |
| 4 | 同上逐 item | captured job + user | 调 `VoucherGrantService#grantInternal`，事务内重新检查当前 campaign/tag/quota | grant 在独立 REQUIRES_NEW 中提交；item 写 outcome | grant 与 item 不是同一事务，靠 grant 幂等恢复窗口 | GRANTED 或 IDEMPOTENT | 稳定 4xx→SKIPPED；其他异常→FAILED |
| 5 | `#finalizeIfDrained` | jobId | PENDING/FAILED count | COMPLETED 或 PARTIAL_FAILED | outer batch transaction | 无 pending 且无 failed→COMPLETED；有 failed→PARTIAL_FAILED | 有 pending 保持 RUNNING |
| 6 | pause/resume/retry | scoped job | current status | PAUSED/READY；仅 FAILED→PENDING | pause/resume 条件更新；retry public transaction + job lock | 重复 pause/resume 幂等；retry 后重新收敛 | 非法状态 409；没有 FAILED 409 |

快照回答“创建时有哪些候选”，执行复核回答“发放时是否仍满足规则”。成员后来移除、活动停用/过期、ruleVersion 变化、quota 耗尽属于稳定业务事实，保存 `SKIPPED`，重试不会改变它；数据库/代码等技术异常保存 `FAILED`，`retry-failures` 只重置 FAILED。新增到 tag 的用户不在旧 Job；移除用户仍保留 item，但执行时变 SKIPPED。

Job 外层事务锁 items，而每个 grant 通过 REQUIRES_NEW 独立提交。若 grant 已提交、item marker 随后失败或 outer transaction 回滚，item 仍 PENDING；下一轮统一 grant 按 ONCE 返回既有事实，然后 item 收敛为 IDEMPOTENT，不会第二次增 quota/outbox。这是“grant 事实优先、item 可恢复”的刻意窗口。

`VoucherBatchJobWorker` 默认关闭；每 tick 最多 snapshot 一个 Job，再处理 `batchSize` 个 item。batchSize 配置强制 1..500。有限批次避免长事务/无界锁；它不是吞吐保证。并发 create 靠 requestId 唯一边界收敛；并发 worker 靠 Job/item `FOR UPDATE`；pause 通过状态让 runnable 查询看不到 Job，resume 回 READY，最终以 PENDING 是否清零收敛。

### Notification Outbox

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | grant transaction | newly created grant | 无 | insert one `VOUCHER_GRANTED/PENDING` outbox | 与 count+grant 同一 REQUIRES_NEW；grant_id 唯一 | durable notification intent | insert 失败回滚整个新 grant；idempotent grant 不新增 |
| 2 | `VoucherGrantNotificationOutboxWorker#scheduledRun` | tick | notificationWorkerEnabled | 日志 | 无 | enabled 调有限 batch | disabled no-op；异常留 durable row |
| 3 | `VoucherGrantNotificationOutboxService#processNextBatch` | batchSize | due PENDING rows join grant，`FOR UPDATE` | 无 | public `@Transactional` | 锁定 exact due prefix | empty 返回 0；坏 event type 整批回滚 |
| 4 | `WebSocketNotifier#notifyVoucherGranted` | outbox/grant fields | 无 | Redis Pub/Sub `ws:voucher-grant:{userId}` | 仍在 outbox DB transaction；Redis publish 非 XA | Redis 接受 publish | Redis 异常→markRetry；grant 不受影响 |
| 5 | outbox marker | publish outcome | row still PENDING | 成功→PUBLISHED/publishedAt；失败→attempts/nextAttempt/lastError | 同一 DB transaction | marker commit 后不再选 | marker 写失败使 DB transaction 回滚；成功 publish 可能已发生，重试会重复通知 |
| 6 | WebSocket + durable read | exact user channel；客户端刷新 | local user session；`/voucher-grants/mine` join MySQL | 在线 send；持久列表无写 | 在线 best effort | 提示用户刷新 durable grants | 离线/撤 token/send 失败不改变 grant 或 PUBLISHED |

`PUBLISHED` 只表示 Redis `convertAndSend` 返回而后 DB marker 成功提交；它不证明订阅实例在线、本地 WebSocket 存在、用户收到或已读。Redis publish 成功但 marker 失败时会重复发布，这是 at-least-once 提示窗口；客户端应把 event/grant ID 当提示身份并刷新 `/voucher-grants/mine`，不能把通知当发券事实。

Redis publish 异常时 PENDING row 的 attempts 增加，nextAttempt 使用 1s 指数退避并受 `notificationMaxBackoff` 封顶。`notificationMaxAttempts` 当前只封顶 attempts 数值；mapper 的 due 查询不排除到达上限的行，所以它仍会按最大退避持续重试，并不存在 FAILED/DLQ 终态。marker update failure 与 publish failure 必须区分：前者抛异常回滚整批，后者正常记录 retry 后继续其他行。

这不是 RocketMQ 发券通知，也不是离线消息中心。链路只使用同库 Outbox + Redis Pub/Sub + 当前在线 WebSocket；持久可恢复入口是 MySQL `/voucher-grants/mine`。

## 数据/状态模型

### 活动与资格

`tb_marketing_tag` 以 `uk_marketing_tag_merchant_code(merchant_id,code)` 保证商户内 code 唯一；`tb_marketing_tag_member` 以 `uk_marketing_tag_member_tag_user(tag_id,user_id)` 保证标签内用户唯一，并通过 `(tag_id,merchant_id)` 复合外键约束同商户 scope，状态为 ACTIVE/REMOVED 且可带 expiry。`tb_voucher_campaign` 记录 merchant、voucher、grant mode（CLAIM/ADMIN/BOTH/TASK）、eligibility（ALL/MANUAL_TAG）、时间窗、quota、granted_count、status 和 rule version。活动只能绑定当前 merchant 上架的普通券。

活动状态的精确流转是 `DRAFT → ACTIVE/CLOSED`、`ACTIVE → PAUSED/CLOSED`、`PAUSED → ACTIVE/CLOSED`；同状态请求幂等，`CLOSED` 是终态。编辑只接受 DRAFT/PAUSED，并要求 expected status/version。已有 grant 后不能随意替换绑定券或降低额度。

### Grant 与签到

`tb_voucher_grant` 保存 campaign/merchant/voucher/user/source/rule_version/operator/idempotency_key。V10 将历史 grant 的 key 回填为 `ONCE`，并把 TASK key 纳入唯一边界；V11 增加 `BATCH_GRANT`。`BusinessDateProvider` 使用 `Asia/Shanghai` 默认业务时区，`VoucherGrantService.prepareServerOwnedFields` 生成 `TASK_REWARD:DAILY_SIGN_IN:yyyy-MM-dd`，客户端不能提交这个 key。

`tb_sign` 的 `(user_id,date)` 唯一键是签到事实。`UserServiceImpl.sign` 遇 DuplicateKey 仍返回成功；`signCount` 查询截止今天的 MySQL 日期，只数连续日期，不使用 Redis 作为签到真相。

### Batch Job 与通知

| 对象 | 状态 | 含义 |
| --- | --- | --- |
| `tb_voucher_batch_job` | SNAPSHOTTING/READY/RUNNING/PAUSED/COMPLETED/PARTIAL_FAILED | requestId 幂等、捕获 rule/tag、批量总体状态 |
| `tb_voucher_batch_item` | PENDING/GRANTED/IDEMPOTENT/SKIPPED/FAILED | 每个快照用户的可恢复结果 |
| `tb_voucher_grant_notification_outbox` | PENDING/PUBLISHED | durable notification queue；有 attempts/next/error |

Job 创建的 requestId 以 merchant 为范围唯一；snapshot 是一次 `INSERT ... SELECT`，不是运行时全库扫描。worker 每次锁一个 Job 和有限 item，调用 `VoucherGrantService.grantInternal`，因此批量不会形成第二套额度/资格算法。

## 异常与恢复

- 数据库在 grant facade 的首次 existing 查询就不可用时，映射为 503，事务服务不应被调用；不能返回“可能已发放”的伪成功。
- 并发 grant 由 campaign 行锁、二次 existing 查询、条件额度更新和唯一边界共同收敛；DuplicateKey 逃出事务 bean 后再 idempotent re-read，避免把竞争事务的额度增量误提交。
- 旧 rule version、非活动、未开始、已结束、额度耗尽、标签不满足、未签到和 grant mode 不匹配都属于稳定业务拒绝，不能进入技术失败重试。
- tag 移除和 claim 使用相同的 tag/member 锁顺序，竞态下要么先领取成功，要么按 `CAMPAIGN_INELIGIBLE` 退出，额度与 grant 数仍一致。
- batch 单项技术错误保存为 FAILED；`retry-failures` 只重置 FAILED，稳定资格/规则拒绝保持 SKIPPED，Job 最终是 COMPLETED 或 PARTIAL_FAILED。
- notification worker publish 抛 Redis 异常时保留 PENDING、递增 capped attempts、设置 next attempt；数据库 marker 更新失败抛出而不伪装成 Redis 失败。
- Redis Pub/Sub 恢复后可再次 publish；重复事件由 event/grant identity 和用户端刷新持久列表处理。这里的恢复是通知收敛，不是离线消息中心。

## 调试现场检查顺序

1. 先按 campaign/user/idempotencyKey 查 `tb_voucher_grant`，确定是否已有 durable grant；TASK key 必须来自服务端业务日期。
2. 查 campaign 的 merchant/voucher/status/ruleVersion/window/quota/grantedCount，再查 voucher→shop merchant；用 `grant count == granted_count` 评估事实，不以 HTTP 200 判断。
3. MANUAL_TAG 再查 tag/member status、expiry 和 merchant；TASK 再查 exact `tb_sign(user_id,date)`。稳定拒绝保留 code，不重试成技术异常。
4. 新 grant 缺失时查同一事务的 quota 条件更新、grant unique/FK 和 notification outbox insert；任一失败都应是三者回滚。
5. batch 先查 Job captured version/tag/status，再按 item status/attempt/error 分组；只把 FAILED 重置，SKIPPED 需新 Job/新业务决定。
6. notification 先查 PENDING 的 nextAttempt/attempts/lastError，再查 publish/marker 日志；PUBLISHED 不等于在线收到，持续 PENDING 也不会自动进入 DLQ。
7. 用户反馈“没券”最终查 `/voucher-grants/mine` 对应的 MySQL join；WebSocket、Redis channel 和在线 session 只用于解释提示路径。

## 方案取舍

统一 grant facade 让不同来源共享规则和事务，代价是 command 需要明确 source、operator、expected version 和 server-owned key。campaign 行锁是商户活动额度的简单序列化边界，牺牲部分并发换取 `granted_count == grant count` 的可解释性。batch 使用有限快照和小批次，避免一次长事务或全库扫描，但当前只支持手工标签、ADMIN/BOTH 活动，不是通用营销平台。

通知 Outbox 与 grant 同库提交，解除“券已发但通知丢失”的同步耦合；Redis/WebSocket 仍然是在线体验优化，必须保留持久查询入口。

## 代码导航

| 关注点 | 路径与方法 |
| --- | --- |
| 用户入口 | [`VoucherCampaignController`](../../src/main/java/com/localdeals/controller/VoucherCampaignController.java)、[`VoucherGrantController`](../../src/main/java/com/localdeals/controller/VoucherGrantController.java)、[`VoucherCampaignUserService.claim/taskReward`](../../src/main/java/com/localdeals/service/VoucherCampaignUserService.java) |
| 管理入口 | [`MarketingAdminController`](../../src/main/java/com/localdeals/controller/MarketingAdminController.java)、[`MarketingAdminService`](../../src/main/java/com/localdeals/service/MarketingAdminService.java) |
| 统一 grant | [`VoucherGrantService.grant/grantInternal`](../../src/main/java/com/localdeals/service/VoucherGrantService.java)、[`VoucherGrantTransactionService.grantWithResult`](../../src/main/java/com/localdeals/service/VoucherGrantTransactionService.java)、[`VoucherGrantCommand`](../../src/main/java/com/localdeals/dto/VoucherGrantCommand.java) |
| 签到/日期 | [`UserServiceImpl.sign/signCount`](../../src/main/java/com/localdeals/service/impl/UserServiceImpl.java)、[`BusinessDateProvider`](../../src/main/java/com/localdeals/service/BusinessDateProvider.java)、[`SignMapper`](../../src/main/java/com/localdeals/mapper/SignMapper.java) |
| batch | [`VoucherBatchJobService.create/snapshotOne/processNextBatch/retryFailures`](../../src/main/java/com/localdeals/service/VoucherBatchJobService.java)、[`VoucherBatchJobWorker`](../../src/main/java/com/localdeals/service/VoucherBatchJobWorker.java) |
| notification | [`VoucherGrantNotificationOutboxService.processNextBatch`](../../src/main/java/com/localdeals/service/VoucherGrantNotificationOutboxService.java)、[`VoucherGrantNotificationOutboxWorker`](../../src/main/java/com/localdeals/service/VoucherGrantNotificationOutboxWorker.java)、[`WebSocketNotifier.notifyVoucherGranted`](../../src/main/java/com/localdeals/websocket/WebSocketNotifier.java) |
| 数据约束 | [`V9__targeted_voucher_campaign.sql`](../../src/main/resources/db/migration/V9__targeted_voucher_campaign.sql)、[`V10__daily_sign_task_rewards.sql`](../../src/main/resources/db/migration/V10__daily_sign_task_rewards.sql)、[`V11__batch_grant_notification_outbox.sql`](../../src/main/resources/db/migration/V11__batch_grant_notification_outbox.sql) |

## 验证证据

本轮只做静态精读，没有重跑下列测试。

| 测试 | fixture | 动作 | 核心断言 | 证据层级 | 未证明内容 |
| --- | --- | --- | --- | --- | --- |
| `M6aBusinessFlowIT` | narrow persistence Spring context；runner 通过 DynamicPropertySource 注入 MySQL；创建 owner/tag/member/campaign/user | 用户列表、claim、重放、ineligible | claim state；同一 grant；count=grant；不合资格零副作用 | MySQL 集成契约 | 不加载 Redis/MQ/ES/WS；类不创建 MySQL 容器 |
| `MarketingAdminIsolationIT` | 两 merchant/account/shop/voucher；窄上下文明确断言无 MQ/Redis/ES client | 跨 merchant tag/campaign 操作、版本更新/状态流转 | scope 拒绝；业务关系；ruleVersion；复合归属 | MySQL 集成契约 | 不证明 MVC interceptor 或在线依赖 |
| `MarketingGrantConcurrencyIT` | 专用 schema fixture；100 threads/users | 同用户并发、quota=10、claim/admin race、stale version、tag removal race | one grant/count；恰好 10 winner；稳定失败零副作用 | MySQL 并发集成 | 线程模型不是生产负载/容量 |
| `VoucherGrantFailureTest` | mock first existing query 抛 DataAccessException | facade grant | 503 `DATABASE_UNAVAILABLE`；transaction service 未调用 | 单元 | 事务内后续 DB 故障的真实 rollback |
| `VoucherGrantTaskContractTest` | fixed BusinessDateProvider + mocked transaction | TASK command | facade 覆盖客户端字段为指定 business date/key | 单元 | MySQL sign/unique/quota |
| `M6bDailyTaskBusinessIT` | 可变 Clock、专用 MySQL；大量 sign/reward fixture | 100 并发、跨午夜、未签到/旧版本/mode/quota、M6A ONCE race | 每日一 sign/key/grant；跨日新 grant；稳定失败零副作用 | MySQL 并发集成 | 不是调度/SLA 或时钟漂移证明 |
| `BusinessDateProviderTest` | UTC instant + Asia/Shanghai zone | 午夜前后取 today/key | 日期和 task key 在本地午夜切换 | 单元 | 生产主机/NTP/DB 时钟一致性 |
| `UserServiceDailySignTest` | mock SignMapper/Redis、ThreadLocal user | DuplicateKey sign、日期序列 signCount | 重复签到成功且不触 Redis；只数 today 向前连续日期 | 单元 | 真实唯一键并发和 HTTP 登录链 |
| `M6cBatchBusinessIT` | 专用 MySQL；tag/campaign/users/jobs；部分故障注入 | 并发 requestId、snapshot、100 items、小批 pause/resume、retry、两 Job/early claim、outbox failure | 同一 Job；快照不扩张且执行复核；GRANTED/IDEMPOTENT/SKIPPED/FAILED 收敛；grant/outbox/count 不变量 | MySQL 集成契约 | worker 长期调度、公平性、生产吞吐 |
| `M6cRedisRecoveryIT` | `M6C_ISOLATED=true`；runner 提前创建并标记 owned Redis/MySQL；类验证 run-id/role | 测试类主动 docker stop/start exact owned Redis，处理 grant/outbox，再恢复发布 | Redis down 不撤 grant；PENDING+attempt；恢复 PUBLISHED；持久 grant=1 | 有所有权保护的故障集成 | 本轮未运行；局部 recovery_ms 非 SLA；不是 Testcontainers |
| `VoucherGrantNotificationOutboxServiceTest` | mock due row/notifier/mapper | Redis failure、publish accepted、marker failure | capped attempt/backoff；exact PUBLISHED；marker failure不伪装为 Redis error | 单元 | 真实 Pub/Sub delivery、事务外重复窗口 |
| `MarketingMvcSecurityTest` | MVC slice，mock user/admin sessions/services | admin read/write、consumer campaign/task/mine、batch DTO 路由 | 401/403 与身份域；read/write permission；请求形状 | MVC slice | 不证明 Service SQL/scope/事务 |

隔离口径必须分层：这些 IT 没有 Testcontainers 依赖，普通 M6A/M6B/M6C persistence 类不自行创建容器，专用 MySQL/Redis 的端口、schema、run marker 来自正式 runner 注入。`M6cRedisRecoveryIT` 是特殊情况：它不创建容器，但会在 `M6C_ISOLATED=true` 且标签校验通过后主动 stop/start runner 已创建的 exact Redis 容器。脱离正式 runner 不能假定依赖专用，也不应单独运行该故障测试。

历史 M6 result 文档记录过并发、100-item、Redis 恢复和后续 Pre-M8 1000-target 等限定环境结果。测试类契约、runner 隔离、历史结果和本轮静态阅读是四种不同证据；历史计时只能描述当时本地恢复/收敛，不是生产 SLA、P99 或容量。

## 不能证明的边界

- `PUBLISHED` 不是用户收到/读到；当前没有完整离线通知、短信/邮件/推送平台或消息中心。
- 当前没有支付、退款、核销、积分商城、通用规则 DSL、审批流、动态人群或定时营销平台。
- 没有新建 RocketMQ 发券 topic/consumer；通知使用 Redis Pub/Sub + WebSocket，不要把它写成 MQ 可靠通知。
- 没有 Redis Cluster/Sentinel、跨实例 Job 全局调度或生产容量证明；worker 默认关闭。
- M6 evidence 是仓库记录的既有隔离 run，不是本次文档会话重新启动服务得到的结果。
- `UNVERIFIED`：静态源码不能证明某个部署实际打开两个 worker gate，也不能证明通知最终到达任何用户。
- `BOUNDARY`：notification attempts 到上限不会进入终止态；当前没有人工处置表、DLQ 或离线补发状态机。
- `BOUNDARY`：batch item 的稳定拒绝不会由 retry-failures 重置；要在规则变化后重新覆盖人群需显式创建新 Job，而非重试旧 SKIPPED。

## 项目特色摘要

面向多入口发券、批量任务重跑和通知依赖故障，以统一幂等发放、有限批处理状态机和事务通知 Outbox 隔离核心发券事实与实时提示。正式简历表述与证据映射见 [07. 简历与面试定稿](07-resume-and-interview.md)。
