# 普通活动券怎样形成用户权益，多入口和批量怎样共用规则

商户想给用户一张普通券时，代码里有三层不同的业务对象。**券定义**是 `tb_voucher` 中的使用模板，说明它属于哪家店、标题、支付金额、抵扣金额和使用规则；这里使用的是 `type=0`、`status=1` 的上架普通券。**发券活动**是一次营销安排，代码名是 Campaign，对应 `tb_voucher_campaign`：它把某张普通券和商户、领取方式、资格条件、时间窗、总额度、已发数量及规则版本放在一起。**用户已经领到的一份券权益**是 Grant，对应 `tb_voucher_grant`：它明确记录哪位用户通过哪个活动取得哪张券、来源是什么、按哪个版本发放。用户请求领取，希望得到的就是这条可持久查询的 Grant，而不是一张券模板或一笔订单。

这里的 `quota_total/granted_count` 是营销活动允许形成多少份 Grant，**不是**秒杀券的 Redis/MySQL 库存；Grant 也**不是** `tb_voucher_order` 中的秒杀订单。当前源码能确认的是 Grant 的发放与券包查询，没有把它继续接成支付、核销、撤券或退款流程，因此不能用秒杀的扣库存、异步下单和补偿逻辑解释本流程。

这条普通发券链没有 Lua，也没有 RocketMQ 消费者。并发判断发生在 MySQL 的查询、行锁、条件更新、唯一约束和本地事务中；通知提交后才另外经过 Redis Pub/Sub 与 WebSocket。

## 一、先准备一场可以发放的普通券活动

商户要先准备券和活动，后面的用户领取、管理员单发与批量任务才能引用同一套规则。这部分只交代发券前必须形成哪些数据，具体状态竞争放到后文。

- 商户先调用普通券创建接口，路径给出 shopId，请求带标题、副标题、使用规则、支付金额和抵扣金额。`AdminCatalogService#createVoucher` 先锁定店铺并确认它属于当前后台商户，再检查标题和金额；普通券分支把 `type` 设为 0、`status` 设为 1，写入 `tb_voucher`。
  - 这里不会写秒杀库存和秒杀起止时间。后面的营销活动只会绑定这种上架普通券。
- 如果只允许某些用户参加，管理员接着创建人工标签。`MarketingAdminService#createTag` 从后台身份确定 merchantId，把 code 去空格并转成大写，要求它是 2～64 位大写字母、数字、下划线或短横线；名称去空格后不能为空且不超过 128 个字符。
  - 通过后向 `tb_marketing_tag` 写入 `ACTIVE` 标签和创建人。`(merchant_id,code)` 唯一键冲突时返回“当前商户已存在相同标签编码”。
- 管理员给标签添加用户时，请求带 tagId、userId、merchantId 和可选过期时间。服务按商户范围锁住标签，要求标签仍为 `ACTIVE`，并要求过期时间晚于应用当前时间。
  - 接着执行 `countBusinessRelationship`：目标用户必须存在，并且在本商户至少有一笔券订单或一条既有 Grant；否则以 `CAMPAIGN_INELIGIBLE` 拒绝。
  - 检查通过后执行 `INSERT ... ON DUPLICATE KEY UPDATE`，把 `(tag_id,user_id)` 对应成员写成或恢复成 `ACTIVE`，同时保存 merchantId、过期时间、分配人和分配时间。后续发券会重新读取这条成员关系，而不是只相信建活动时的结果。
- 管理员再创建发券活动，请求带 voucherId、名称、发放模式、资格类型、标签、开始与结束时间、总额度和商户范围。服务先把发放模式与资格类型转成大写，再依次检查：
  - 发放模式只能是用户领取 `CLAIM`、管理员发放 `ADMIN`、两者都支持 `BOTH` 或每日任务 `TASK`；资格只能是全体 `ALL` 或人工标签 `MANUAL_TAG`。
  - 开始时间必须早于结束时间，总额度必须大于 0；选择 `MANUAL_TAG` 时必须绑定一个标签，选择 `ALL` 时不能携带标签。
  - SQL 联结 `tb_voucher` 与 `tb_shop`，要求 voucherId 对应 `type=0`、`status=1` 且店铺 merchantId 与当前范围一致；需要标签时，还要按 tagId 和 merchantId 查到 `ACTIVE` 标签。
- 全部通过后，`createCampaign` 在一笔 MySQL 事务里向 `tb_voucher_campaign` 写入活动，把状态设为 `DRAFT`、`granted_count` 设为 0、`rule_version` 设为 1，并记录后台创建人；随后按新 ID 和 merchantId 回读并返回活动。
  - 任一校验或插入失败都会结束这次创建事务，还没有用户权益产生。
- 准备完成后，管理员调用状态变更接口启用活动，请求带目标 `ACTIVE`、自己看到的 expectedStatus、expectedRuleVersion 和商户范围。服务按 campaignId 与 merchantId 锁住活动，比较当前状态和版本，再重新检查普通券与标签。
  - `DRAFT → ACTIVE`、`PAUSED → ACTIVE` 是允许的流转；来源状态或版本已变化时返回 `CAMPAIGN_RULE_CHANGED`。
  - 最后的条件 `UPDATE` 仍要求 ID、商户、旧状态和旧版本匹配，成功时把状态改成 `ACTIVE` 并令版本加一。到这里才形成一场可以被后续入口引用的活动，还没有写入 Grant。

## 二、用户主动领取一次活动券

### 1. 请求先变成一条服务端可信的发券命令

用户请求领取一张活动券，带来活动 ID 和自己看到的规则版本，希望最终得到一份可以在券包中查询的 Grant。

- 用户调用 `POST /voucher-campaigns/{campaignId}/claim`，路径带来活动 ID，请求体只带 `expectedRuleVersion`。当前用户由 `VoucherCampaignController` 从 `UserHolder` 取得，不接受客户端填写 userId。
  - 没有当前用户就按登录失效处理；活动 ID、用户 ID 或规则版本不合法，也不会进入发券事务。
- `VoucherCampaignUserService#claim` 把活动 ID、当前用户 ID 和期望版本组成 `VoucherGrantCommand`，再把来源设成用户主动领取 `USER_CLAIM`，交给 `VoucherGrantService#grant`。
- `VoucherGrantService` 先补齐只能由服务端决定的字段。用户领取的 `idempotencyKey` 被覆盖为 `ONCE`，taskDate 被清空，然后才校验 campaignId 与 userId 不为空、expectedRuleVersion 为正数，并检查来源组合。
  - `ONCE` 表示同一活动、同一用户在普通发券语义下只有一份长期权益，客户端即使构造了别的值也不会被采用。
  - 用户来源不能带管理员 operatorId；管理员与批量来源必须同时有 merchantId 和 operatorId；不符合这些组合就直接抛参数错误。
- 命令合法后，门面在事务外做第一次查重。用户来源执行的 SQL 条件是 `campaign_id + user_id + idempotency_key='ONCE'`，查到就把结果标为 `IDEMPOTENT` 并返回已有 Grant。
  - 这条快速路径不锁活动、不占额度，也不会为已有 Grant 补写通知待办。查询数据库失败时，门面把 `DataAccessException` 映射为 HTTP 503 的 `DATABASE_UNAVAILABLE`，不会把“查不到结果”与“查询失败”混在一起。
  - 没有查到才进入下面的单人发券事务；三次查重在并发下的完整交错仍在第七节进一步推演。

### 2. 单人发券事务按真实顺序复核并写入权益

第一次没有查到时，门面调用 `VoucherGrantTransactionService#grantWithResult`，为这一位用户新开一笔 `REQUIRES_NEW` MySQL 事务。这个事务只负责决定并提交一份权益，不把批量进度、Redis 或 WebSocket 包进来。

- `VoucherGrantTransactionService#grantWithResult` 进入 `REQUIRES_NEW` 事务后，先用和门面相同的身份做第二次查重。看到已有 Grant 就返回 `created=false`，调用方将它解释成 `IDEMPOTENT`；仍为空才继续。
  - 任务奖励来源在这次查询之前还会先查当天签到，普通用户领取没有这一步。
- 接着执行活动锁定查询。SQL 把 `tb_voucher_campaign`、`tb_voucher` 和 `tb_shop` 联结起来，除活动字段外还读出券类型、券状态和券所属 merchantId，并对活动行加 `FOR UPDATE` 锁。
  - 用户领取只按 campaignId 查找；管理员和批量发放使用 `campaign_id + merchant_id`。查不到活动时抛 404，不继续占额。
  - 取得锁后，代码用同一身份做第三次查重。看到已有 Grant 仍返回 `created=false`；看不到才继续检查规则。第三次查询的隔离级别可见性边界在第七节说明，不能替代最终唯一键。
- 服务拿着活动和联表得到的券信息，依次检查本次是否还能发：
  - 先要求命令的 `expectedRuleVersion` 等于活动当前 `ruleVersion`，否则抛 `CAMPAIGN_RULE_CHANGED`。
  - 再根据来源检查发放模式：用户领取只接受 `CLAIM/BOTH`，管理员与批量只接受 `ADMIN/BOTH`，任务奖励只接受 `TASK`；不匹配时抛 `CAMPAIGN_GRANT_MODE_UNSUPPORTED`。
  - 接着调用 `currentDatabaseTime` 读取数据库 `CURRENT_TIMESTAMP`。活动状态不是 `ACTIVE`、当前时间早于 beginTime、当前时间达到或超过 endTime 时，分别按未启用、未开始或已结束拒绝。
  - 最后检查联表字段：券必须仍为 `type=0`、`status=1`，并且券所在店铺的 merchantId 必须等于活动 merchantId；否则按规则变化拒绝。
- 如果 `eligibilityType` 是 `MANUAL_TAG`，服务按固定顺序继续锁定标签和成员。
  - 先按 `requiredTagId + merchantId` 对标签 `FOR UPDATE`，再按 `merchantId + tagId + userId` 对成员 `FOR UPDATE`。成员查询会读到 ACTIVE 或 REMOVED 原记录，而不是只返回合格成员，使移除与发券竞争同一行锁。
  - 标签不存在或不是 `ACTIVE`、成员不存在或不是 `ACTIVE`、`expire_time` 不为空且不晚于刚才的数据库时间，都会抛 `CAMPAIGN_INELIGIBLE`。`ALL` 活动跳过这两次查询。
- 资格通过后，Java 先比较锁定行里的 `granted_count` 与 `quota_total`，已经用尽就抛 `CAMPAIGN_QUOTA_EXHAUSTED`。仍有额度时执行条件占额 SQL：
  - `UPDATE tb_voucher_campaign SET granted_count=granted_count+1`，WHERE 同时要求 campaignId、merchantId、voucherId、ruleVersion 未变，状态仍为 `ACTIVE`，`begin_time <= CURRENT_TIMESTAMP < end_time`，并且 `granted_count < quota_total`。
  - 影响 1 行才表示取得一份额度。影响 0 行时，服务拿已锁住的活动和前面取得的数据库时间重新分类：版本不同、状态或时间窗不再满足、额度已满时抛对应冲突；无法归入这些情况时按规则变化拒绝。
- 占额成功后，服务组装 `VoucherGrant` 并写入 `tb_voucher_grant`。记录包含 campaignId、活动 merchantId、voucherId、userId、来源、幂等 key、活动当前版本和 operatorId；数据库生成 grantId，`granted_at` 使用表的当前时间默认值。
  - 表上的 `(campaign_id,user_id,idempotency_key)` 唯一键是最后的防重边界；活动、商户和券的复合外键还要求 Grant 与 Campaign 的归属一致。插入影响行数不是 1 就抛异常。
- 取得 grantId 后，服务立即向 `tb_voucher_grant_notification_outbox` 插入通知待办，输入 grantId、merchantId 和 userId；SQL 固定写入事件 `VOUCHER_GRANTED`、状态 `PENDING`、attempts=0、nextAttemptTime=数据库当前时间。
  - 这条记录叫 notification Outbox，意思是“权益提交后还有一条在线提示需要处理”，不是消息已经送达。`grant_id` 唯一键使一份新 Grant 最多对应一条待办；插入失败就抛异常。
- 到这里 `grantWithResult` 返回 `created=true + Grant`，Spring 才提交这笔单人事务。`granted_count + 1`、Grant 和通知待办要么一起提交，要么在上述任一步抛错时一起回滚。
  - 提交成功只证明额度、权益和待办已经落入 MySQL；Redis、WebSocket 以及批量 Item 都没有参加这次提交。
- 控制回到不带事务的 `VoucherGrantService`。新建结果被标为 `GRANTED`；事务内任意插入若抛 `DuplicateKeyException`，必须先让这笔 `REQUIRES_NEW` 事务完整回滚，再由门面在事务外按同一身份重查。
  - 重查到竞争请求已经提交的 Grant，就按 `IDEMPOTENT` 返回，因此刚才失败事务中的额度增量和通知插入不会留下；仍读不到则继续抛异常，不猜测成功。其他数据库异常统一映射为 503，普通运行时异常继续上抛。
- 最后控制回到 `VoucherCampaignUserService`，它把 Grant 转成用户视图，本次 HTTP 请求结束。
  - 视图定义了 grantId（JSON 字段名为 `id`）、campaignId、voucherId、活动名、券标题、金额、规则版本和发放时间，但当前领取路径不会联表补齐展示字段。新建时返回刚插入的实体，活动名、券标题、金额和数据库默认生成的发放时间仍为 null；重复领取查回 Grant 表后能取得发放时间，但仍没有活动名、券标题和金额。这些信息在后面的券包联表查询中才会补齐。
  - 无论结果是新建还是重复，接口返回的都是持久 Grant；在线通知由另一个 worker 从数据库待办重新开始，不延长本次请求。

## 三、管理员单发和签到奖励从哪里接入

核心事务只需要理解上面一次，其他入口的区别在于：由谁生成可信身份、先做哪些入口检查，以及使用哪一种防重身份。

### 管理员给一个用户单发

管理员请求给指定用户发券，带来活动 ID、目标用户和期望版本，希望得到的仍是同一类 Grant。后台登录与权限已经在前面完成，这里只继续说明发券自身怎样确定商户和操作人。

- 管理员调用 `POST /admin/marketing/campaigns/{campaignId}/grants`，请求带目标用户 ID、期望规则版本，以及可选的 merchantId。
  - `MarketingAdminService#resolveMerchant` 读取 principal，也就是已经解析好的后台身份。平台账号必须显式指定 merchantId；商户账号缺少自身 merchantId 或请求了别的商户时返回 403。
  - `operatorId` 取 principal 中的 accountId，不接受请求填写；请求体为空则直接报参数错误。
- Controller 用 campaignId、解析后的 merchantId、目标 userId、expectedRuleVersion 和 operatorId 生成 `ADMIN_GRANT` 命令，交给统一门面。
  - 门面把新权益的 idempotencyKey 固定为 `ONCE`，校验管理员来源必须同时带 merchantId 与 operatorId，再按 `campaign_id + merchant_id + user_id + idempotency_key='ONCE'` 做事务外查重。查到才直接返回；每日任务 key 对应的 Grant 不会被这条查询当作普通发放的重复权益。
- 没有已有 Grant 时，命令进入第二节同一个 `REQUIRES_NEW` 单人事务。活动锁查询额外要求 campaignId 与 merchantId 同时匹配，发放模式要求 `ADMIN/BOTH`；后面的版本、时间、普通券归属、标签资格、条件占额、Grant 与通知待办完全共用。
  - 事务内锁前和锁后的两次查重也都限定活动、商户、用户和 `ONCE`；确实需要新建时，写入相同的 idempotencyKey。
  - 活动不在当前商户范围内时按不存在结束；目标用户不存在会触发 Grant 的用户外键错误，使整笔单人事务回滚，随后由门面按数据库不可用分支返回 503。
- 单人事务提交后，接口返回新 Grant 或已经存在的 Grant，本次后台请求结束。
  - 同一个 `BOTH` 活动里，用户端与后台同时给一名用户发券时仍只有一份 `ONCE` 权益；Grant 的来源和 operatorId 以真正提交成功的一方为准。

### 签到与领取每日奖励是两个请求

用户先完成签到，再另外请求领取每日奖励；这不是一次请求里的自动连续动作。

- 用户调用 `/user/sign`，`UserServiceImpl#sign` 从当前用户上下文取得 userId，再按 `BusinessDateProvider` 给出的业务日期向 `tb_sign` 插入一条签到事实。
  - `BusinessDateProvider` 用注入的 Clock 按配置时区计算 `LocalDate`，默认是 `Asia/Shanghai`。签到记录同时保存这个日期对应的 year、month 和 date。
  - `(user_id,date)` 唯一约束冲突被方法捕获并忽略，所以同一天重复签到仍返回成功。签到方法到这里结束，它没有调用发券服务，也不会自动生成 Grant。
- 用户随后单独调用 `POST /voucher-campaigns/{campaignId}/task-reward`，请求带活动 ID 和期望规则版本；当前 userId 仍从 `UserHolder` 取得。
- `VoucherCampaignUserService#taskReward` 先检查活动、用户和正数版本，再生成 `TASK_REWARD` 命令。统一门面覆盖 taskDate 为服务端当天，并拼成 `TASK_REWARD:DAILY_SIGN_IN:yyyy-MM-dd` 作为 idempotencyKey。
  - 客户端没有字段可以指定任务日期或幂等 key。门面随后按 `campaign_id + user_id + 当日key` 做第一次查重；查到就直接返回当天已有 Grant。
- 当天没有 Grant 时，命令进入单人事务。它在第二次查重之前先执行 `SELECT COUNT(*) FROM tb_sign WHERE user_id=? AND date=?`，输入正是命令里的 userId 与服务端 taskDate。
  - 计数为 0 就抛 `TASK_NOT_COMPLETED`，事务不锁活动也不占额；大于 0 才按当日 key 进行第二次查重，再进入活动加锁流程。
- 后续仍沿用第二节的规则检查、资格锁定、条件占额、Grant 和通知待办写入，只把来源模式要求改为 `TASK`，并把 Grant 的 idempotencyKey 保存为当天 key、operatorId 留空。
  - 没有当天签到、版本变化、资格变化或额度耗尽都会在写入前结束；新 Grant 和通知待办仍与额度增量一起提交。
- 事务提交后返回当天的 Grant，本次领取奖励请求结束。一日内重试返回同一份权益；业务日期切换后，防重 key 随日期改变，完成新一天签到并满足当日规则时才能形成新 Grant。

这里最终有两种防重身份：用户主动领取、管理员单发和后面的批量发放都使用永久 `ONCE`；任务奖励使用服务端生成的业务日 key。数据库统一用 `(campaign_id,user_id,idempotency_key)` 唯一约束表达“同活动永久一次”或“同活动每日一次”。

## 四、管理员创建批量任务后，worker 才分批发券

批量发放不是用户领取请求的后续步骤，而是管理员重新发起的一类请求。它希望先留下可恢复的总体任务，再让后台 worker 分批调用同一套单人发券规则。`VoucherBatchJobWorker` 当前配置默认关闭，只有显式打开 `local-deals.voucher-batch.job-worker-enabled`，已创建任务才会自动推进；本次没有检查某个运行环境是否覆盖了默认值。

### 1. 创建请求只保存一项任务，不在 HTTP 线程遍历用户

- 管理员调用 `POST /admin/marketing/campaigns/{campaignId}/batch-jobs`，路径带 campaignId，请求体带 merchantId、为本次批任务生成的 requestId 和 expectedRuleVersion。`VoucherBatchJobService#create` 先复用后台商户解析，取得可信 merchantId。
- 服务把 requestId 去掉首尾空格，要求它是 1～96 位字母、数字、点、下划线、冒号或短横线，然后按 `merchant_id + request_id` 查询已有 Job。
  - 查到就按 Job ID 与 merchantId 回读带各类 Item 数量的原任务并返回，不再比较这次请求中的 campaignId 或 expectedRuleVersion。requestId 的唯一范围是整个商户，因此调用方必须让它真正代表同一项创建请求，不能拿旧值创建另一项任务。
- 没有已有 Job 时，服务要求 expectedRuleVersion 为正数，再用 `campaign_id + merchant_id FOR UPDATE` 锁住活动并检查：
  - 当前 `ruleVersion` 必须等于请求版本；发放模式必须是 `ADMIN/BOTH`；资格类型必须是带 requiredTagId 的 `MANUAL_TAG`；状态必须为 `ACTIVE`。
  - 接着按 requiredTagId 与 merchantId 查标签，要求仍为 `ACTIVE`。这里不检查活动是否已经进入时间窗，也不提前判断剩余额度；这些条件会在每个用户实际发券时重新判断。
- 检查通过后，服务从 principal 取得 operatorId，组装中文意义上的“本次批量发放总体任务”，代码名是 Job。写入字段包括 merchantId、campaignId、operatorId、requestId、当前 ruleVersion、目标 tagId，状态固定为 `SNAPSHOTTING`、targetCount 初始为 0。
  - 插入 SQL 使用 `ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)`。如果并发请求先写入同一 `(merchant_id,request_id)`，当前调用不会再造第二个 Job，而会得到胜出记录的 ID。
- 插入后，服务按 merchantId 与 requestId 对持久 Job 再做一次 `FOR UPDATE` 读取，然后按 Job ID 与 merchantId 联合统计 `GRANTED/IDEMPOTENT/SKIPPED/FAILED/PENDING` Item 数量并返回。
  - 创建、并发收敛和回读都在同一 MySQL 事务中；任一步失败就回滚。本次 HTTP 到这里结束，不会在请求线程里遍历成员、生成 Item 或发券。

### 2. worker 先固化目标快照，再取有限一批执行

- `VoucherBatchJobWorker` 按 initialDelay 和 fixedDelay 定时进入 `scheduledRun`，默认分别是 5 秒和 1 秒。每次先检查 jobWorkerEnabled；关闭就直接返回，开启才调用 `runOnce`。
  - `runOnce` 固定先尝试快照一个 Job，再尝试执行一个有限批次。某一步抛运行时异常时，外层定时方法只记录错误日志；相应数据库事务回滚，等下一次调度再从持久状态继续。
- 快照阶段调用 `snapshotOne`。它按 Job ID 升序查询最早的 `SNAPSHOTTING` Job 并 `FOR UPDATE`；没有这样的任务就返回 0，随后 `runOnce` 仍会继续尝试处理已经 `READY/RUNNING` 的 Job。
- 找到 Job 后，`snapshotActiveMembers` 接收 jobId、merchantId 和 targetTagId，执行一条 `INSERT ... SELECT`：
  - SQL 从该 Job 联结它捕获的标签与标签成员，要求 Job 仍为 `SNAPSHOTTING`、商户和标签 ID 相同、标签为 `ACTIVE`、成员为 `ACTIVE`，并且成员没有过期或 `expire_time > CURRENT_TIMESTAMP`。
  - 每个符合条件的 userId 被写成一条 `PENDING` Item，attempts 初始为 0。中文上 Item 表示“这个 Job 快照中的一位目标用户及其处理结果”；`(job_id,user_id)` 唯一键阻止同一用户重复进入同一快照。
- 插入完成后，服务按 jobId 统计 Item 总数，再执行条件更新：只有 Job 仍为 `SNAPSHOTTING` 才把 targetCount 写成统计值、状态改为 `READY`。
  - 快照插入、数量统计和状态更新属于 `snapshotOne` 的同一事务。状态更新影响行数不是 1 就抛异常并整体回滚，下一轮仍可重新选择；成功则向 worker 返回本次快照人数。
- `runOnce` 接着调用 `processNextBatch(batchSize)`。服务要求 batchSize 大于 0，然后按 Job ID 升序锁定最早的 `READY/RUNNING` Job；`PAUSED/COMPLETED/PARTIAL_FAILED` 不会被选中。
  - 没有可运行 Job 就返回“未处理”。Job 是 `READY` 时，条件更新将它改成 `RUNNING`，并只在第一次设置 startTime；影响行数不是 1 就让本批事务失败。
- 服务再按 Item ID 升序读取这个 Job 最多 batchSize 条 `PENDING` Item 并 `FOR UPDATE`。默认 batchSize 为 50，配置校验允许 1～500。
  - 一条也没有时，服务立即统计 FAILED 和 PENDING：PENDING 仍不为 0 就保持原状；PENDING 为 0 时，FAILED 为 0 就把 `RUNNING` Job 改成 `COMPLETED`，否则改成 `PARTIAL_FAILED`，然后返回本轮处理 0 条且已经排空。
- 取到 Item 后，服务按顺序逐个构造 `BATCH_GRANT` 命令。campaignId、merchantId、capturedRuleVersion 和 operatorId 来自持久 Job，userId 来自当前 Item；门面会把它的幂等 key 覆盖成 `ONCE`。
  - 每个命令都进入第二节的单人发券流程，所以快照只回答“快照时选中了谁”。查到已有 `ONCE` Grant 时直接按幂等返回；需要新建时，才继续检查当前版本、状态、时间窗、普通券、标签资格和额度。
  - 每份 Grant 使用自己的 `REQUIRES_NEW` 事务。它提交后不会等待整批 Item 一起提交，也不会因后面某个 Item 失败而回滚。
- 单人调用返回 `GrantAttempt` 后，外层批事务用 `markOutcome` 更新当前 Item；SQL 只更新仍为 `PENDING` 的记录，并把 attempts 加一、写入更新时间：
  - 单人事务返回 `created=true`、门面据此给出 `GRANTED` 时，Item 写 `GRANTED` 和新 grantId；按 `campaign_id + merchant_id + user_id + idempotency_key='ONCE'` 查到已有 Grant 时，Item 写 `IDEMPOTENT` 和已有 grantId。管理员、用户主动领取和批量发放都会把新 Grant 保存为 `ONCE`。
  - 活动版本、发放模式、状态、时间窗、标签资格、额度等已知业务冲突，以及其他 4xx 拒绝，被分类成 `SKIPPED`，保存稳定错误码和对用户可解释的原因，不自动重试。
  - 其他运行时异常被分类成 `FAILED/TECHNICAL_ERROR`，只保存统一的“等待 retry-failures”说明，避免把内部异常细节直接写入 Item。前提是这笔外层事务后续能够提交；若外层数据库操作本身失败，结果标记也会回滚。
  - `markOutcome` 返回影响行数，但当前调用处没有检查它。正常情况下 Item 已被本事务锁住且仍为 `PENDING`，应更新一行；源码没有为影响 0 行单独抛错或补偿。
- 一批 Item 处理完后，服务统计这个 Job 是否还有 `PENDING`。还有就让 Job 保持 `RUNNING`，提交本批 Item 结果，等待下一轮；没有则再次按 FAILED 数量改成 `COMPLETED` 或 `PARTIAL_FAILED`。
  - `RUNNING` 变化、这一批 Item 的行锁与结果、结束判断都属于 `processNextBatch` 的一笔外层事务。某个 Grant 已经独立提交而外层进度失败时，Grant 不回滚，下一轮怎样按已有权益收敛见第八节。

### 3. 暂停、恢复和失败重试是另外三类管理员请求

这些请求只调整批任务进度，不直接创建或撤销 Grant。它们仍先从后台 principal 确定 merchantId，再按 jobId 与 merchantId 查找当前商户范围内的 Job。

- 管理员调用 pause 时，服务先回读 Job。已经是 `PAUSED` 就直接返回；否则执行条件更新，只允许 `READY/RUNNING → PAUSED`。
  - 更新不到一行就返回 `BATCH_JOB_NOT_PAUSABLE`。worker 选择 runnable Job 时不包含 `PAUSED`，所以后续批次不会继续；已经取出的当前批次与暂停请求怎样竞争，见第八节。
- 管理员调用 resume 时，Job 已经是 `READY/RUNNING` 就原样返回；只有 `PAUSED` 才通过条件更新改成 `READY`，同时清空 finishTime。
  - 其他状态更新不到一行时返回 `BATCH_JOB_NOT_RESUMABLE`。恢复后不是在这个 HTTP 请求里发券，而是等开启的 worker 下一轮重新选择。
- 管理员调用 retry-failures 时，服务开启一笔事务并锁住当前商户的 Job，再把该 Job 下所有 `FAILED` Item 更新为 `PENDING`，清空错误码和错误消息，但不清零 attempts。
  - 没有 `FAILED` 可重置时返回 `BATCH_JOB_NO_RETRYABLE_FAILURE`；`SKIPPED` 不会被改动。
  - Job 当前为 `PARTIAL_FAILED` 时再把它改回 `READY` 并清空 finishTime；其他状态即使重置了 FAILED，也不改变 Job 状态。事务提交后由后续 worker 重新执行这些 PENDING Item，并再次走完整单人规则。

## 五、通知待办何时接手，用户怎样确认权益

通知从单人事务已经提交的数据库待办重新开始，不属于用户领取、后台单发或批量发放的同步请求。`VoucherGrantNotificationOutboxWorker` 默认也关闭，只有显式启用 `local-deals.voucher-batch.notification-worker-enabled` 才会定时处理；这个开关和批量 Job worker 相互独立。

- `VoucherGrantNotificationOutboxWorker` 使用和批量 worker 相同的 initialDelay、fixedDelay 进入调度。每轮先检查 notificationWorkerEnabled；关闭就返回，开启才把配置的 notificationBatchSize 传给 `processNextBatch`，默认是 50。
  - 整轮抛运行时异常时，worker 只记日志。已经存在的数据库待办不会因为 worker 调用失败而删除，后续调度还能再次选择。
- `VoucherGrantNotificationOutboxService#processNextBatch` 先要求 batchSize 为正数，再在一笔 MySQL 事务中查询到期的待办并加锁。SQL 的输入是本轮 limit，查询过程是：
  - 从通知表联结 Grant，补出待办本身没有保存的 campaignId 和 voucherId；只选择 `status='PENDING'` 且 `next_attempt_time <= CURRENT_TIMESTAMP` 的记录。
  - 按 nextAttemptTime、ID 升序取有限前缀并 `FOR UPDATE`。没有记录时返回 selected=0、published=0、retried=0，本轮结束。
- 服务逐条检查 eventType 必须为 `VOUCHER_GRANTED`，然后把这条完整待办交给 `WebSocketNotifier#notifyVoucherGranted`。
  - eventType 不符合时会在进入单条 publish 的 try/catch 之前抛异常，本批数据库事务整体回滚；worker 只记日志，问题行仍为 `PENDING`，下一轮还会再次遇到它，当前没有隔离状态。
  - 通知方法先要求 eventId、grantId、campaignId、voucherId 和 userId 都存在；缺字段会抛异常，并被当前调用处当作发布失败记录重试。
  - 字段完整时组装 JSON：type 固定为 `VOUCHER_GRANTED`，四个 ID 转成字符串，并附上“优惠券已发放，已加入我的券包”。随后调用 `convertAndSend`，向 `ws:voucher-grant:{userId}` 发布。
  - 代码没有读取或判断 Redis 返回的订阅者数量。因此只要调用没有抛异常，就继续走发布成功分支，即使当时没有在线订阅者。
- `notifyVoucherGranted` 抛运行时异常时，控制回到 Outbox 服务的 catch 分支。服务计算新的尝试次数和下次时间，再更新仍为 `PENDING` 的待办：
  - `attempts = min(notificationMaxAttempts, 当前attempts + 1)`；退避从 1 秒开始，指数使用 `min(20, attempts-1)`，再按 `2^指数` 秒增长，并受 notificationMaxBackoff 限制，默认最大 5 分钟。
  - nextAttemptTime 使用应用当前 `LocalDateTime.now()` 加上退避时长计算，不使用前面发券的数据库时间或业务日期。
  - SQL 保存 attempts、nextAttemptTime 和固定错误 `REDIS_PUBLISH_FAILED`。更新影响行数不是 1 就抛异常，使本批数据库事务回滚；成功则 retried 加一并继续处理下一条。
  - notificationMaxAttempts 默认是 10，但它只封顶计数，查询 SQL 不排除达到上限的记录，所以到期后仍会继续重试，并不存在通知 `FAILED` 或 DLQ 终态。
- Redis 调用没有抛异常时，服务执行条件更新：只有该 ID 仍为 `PENDING`，才把状态改成 `PUBLISHED`、写 publishedAt 并清空 lastError。影响行数必须是 1，否则抛 marker 更新异常。
  - 本轮所有待办锁、retry marker 和 published marker 共用 `processNextBatch` 这一笔数据库事务。事务提交后，`PUBLISHED` 才表示“Redis 发布调用未报错，并且数据库完成标记已保存”；Redis 发布本身不能随 MySQL 回滚，二者之间的故障窗口在第八节展开。
  - 全部行处理完后，方法返回本轮 selected、published 和 retried 数量；定时 worker 不据此启动别的流程，本轮到这里结束。
- Redis 订阅容器收到符合 `ws:voucher-grant:[0-9]*` 模式的消息后，从频道后缀解析 userId，再调用 `webSocketHandler.sendToUser(userId,body)`，由持有该用户本地连接的应用实例尝试发送。
  - userId 后缀不是数字时只记警告；用户不在线、本地没有连接或下游发送失败，都不会改回通知状态，更不会撤销 Grant。`PUBLISHED` 不等于 WebSocket 送达、浏览器展示或用户已读。
- 在线提示只让用户刷新券包。用户另行调用 `GET /voucher-grants/mine`，Controller 从 `UserHolder` 取得当前 userId，再调用 `VoucherGrantMapper#selectMine`。
  - SQL 以 `g.user_id=?` 查询 Grant，联结 Campaign、Voucher 和 Shop，并再次要求三者的 campaignId、merchantId、voucherId 归属相符；结果按 grantedAt、grantId 倒序排列。
  - Mapper 虽然联表读出了 shopId 和 shopName，当前 `VoucherGrantUserView` 并没有这两个字段。Service 实际返回的是 grantId、campaignId、voucherId、活动名、券标题、支付/抵扣金额、规则版本和发放时间。
  - 这个 MySQL 查询才是当前实现中确认权益的持久入口，通知是否收到不改变 Grant 是否存在。

## 六、几个容易混在一起的记录

前面的对象各自回答不同问题，不能把它们画成一条从 Campaign 一直走到通知的连续状态机。

| 中文含义 | 代码名与表 | 它回答的问题 |
| --- | --- | --- |
| 一次营销活动 | Campaign / `tb_voucher_campaign` | 哪张普通券、谁能领、何时领、从哪个入口领、总共发多少、当前规则版本是什么 |
| 一份已经成立的用户权益 | Grant / `tb_voucher_grant` | 哪个用户已经通过哪个活动得到哪张券；这是本流程的最终权益事实 |
| 一项批量发放工作 | Job / `tb_voucher_batch_job` | 哪个商户以哪个 requestId 对哪个活动和标签发起了一次批处理，目前总体走到哪里 |
| 批任务中的一个目标 | Item / `tb_voucher_batch_item` | 快照里的某个用户本轮是新发、已有、跳过、技术失败还是尚未处理 |
| 一次待发布的权益提示 | notification Outbox / `tb_voucher_grant_notification_outbox` | 新 Grant 提交后，这条在线提示是否还待尝试发布 |

Campaign 可以独立暂停、恢复或关闭；Job 可以暂停、恢复或完成；Item 记录某个目标的结果；通知只有 `PENDING/PUBLISHED`。这些状态互相有关联，但不存在“Campaign 完成后自动变成 Grant，再变成 Job、Item 和通知”的统一流转。

## 七、为什么三次查询之后仍要唯一约束

第二节已经按调用顺序写出了三次查重。这里不重复方法步骤，只进一步推演第二次与第三次查询之间的并发可见性。

- 第一次事务外查询处理请求到来前已经存在的 Grant；第二次查询处理第一次查询结束到独立事务开始前已经提交的 Grant；第三次查询的意图是处理当前事务等待活动锁期间提交的 Grant。
- 但 `grantWithResult` 没有显式声明 `READ_COMMITTED`，应用配置也没有固定事务隔离级别。如果实际数据库采用 MySQL 常见的 `REPEATABLE READ`，第二次普通查询可能已经建立一致性快照，锁后的第三次普通查询不一定看见等待期间刚提交的记录。
- 假设用户请求 A 与管理员请求 B 都先查到空：A 取得活动锁并提交 Grant，B 等到锁后仍可能因旧快照看不到它。如果后面的规则、资格和额度检查仍通过，B 会继续占额，但插入 Grant 时撞上 `(campaign_id,user_id,idempotency_key)` 唯一键；B 的独立事务连同自己的额度增量一起回滚，随后门面在事务外读回 A 的 Grant。如果 A 已用完最后一份额度，B 也可能先在额度检查处被拒绝，来不及撞唯一键；不能保证每个并发重复请求当场都返回已有 Grant。
- 因此三次查询用于尽早结束重复请求、减少不必要竞争；活动锁用于稳定同一 Campaign 的规则和额度顺序；条件 UPDATE 用于写入瞬间再次判断活动与剩余额度；唯一约束和独立事务回滚保证不重复形成权益，重读成功时才返回幂等结果。
  - 这里的“事务外重读”是相对单人 `REQUIRES_NEW` 事务而言。用户领取和管理员单发没有外层事务；批量 worker 则仍处在 `processNextBatch` 的外层事务中。门面没有事务注解，也没有把已有事务挂起，所以单人事务结束后，门面的查重会继续使用批量外层事务。如果外层采用 `REPEATABLE READ` 且已有旧快照，重读仍可能看不到竞争方的 Grant，随后按数据库错误记录为 `FAILED`，需要后续 retry-failures；不能把“门面没有事务注解”理解成“查询总能获得全新快照”。

## 八、事务中断后由谁继续

### Grant 已提交，但批量 Item 进度没有提交

- 假设 worker 给用户 2001 发券时，Grant 5001、额度增量和通知待办已经在独立事务提交；随后进程退出，外层批事务来不及把 Item 从 `PENDING` 改成 `GRANTED`。
- 数据库此时已经存在合法权益，不能为了让进度对齐而撤券。下一轮 worker 再锁到这个 `PENDING` Item，仍用同一活动、用户和 `ONCE` 调用单人流程。
- 门面第一次查重就读到 Grant 5001，返回 `IDEMPOTENT`；外层再把 Item 标为 `IDEMPOTENT` 并关联同一 grantId。额度和通知待办都不会增加第二次。
- 如果一个批次后面的数据库错误让外层事务整体回滚，前面分别提交的多个 Grant 仍然存在，对应 Item 则可能一起回到原来的 `PENDING`，下一轮会逐个按上述方式收敛。

### 暂停请求遇到正在执行的批次

- `processNextBatch` 从选择 Job 到提交 Item 结果一直持有 Job 行锁。管理员此时调用 pause，条件更新会等待当前外层事务释放锁，不能中断已经取出的 Item。
- 当前批次提交后，pause 才有机会把仍为 `READY/RUNNING` 的 Job 改成 `PAUSED`，后续 runnable 查询不再选它。如果本批已经把 Job 改成 `COMPLETED/PARTIAL_FAILED`，暂停条件就不再匹配，请求返回冲突；若下一批先取得锁，暂停还要继续等待。暂停只能在批次之间生效，不能中断正在执行的方法。
- 若规则后来改变，管理员也不能用 retry-failures 重跑 `SKIPPED`。当前实现需要使用新的 requestId 创建 Job，重新捕获版本和成员快照。

### 通知发布失败和数据库完成标记失败不是一回事

- Redis 发布调用抛异常时，服务会尝试把待办保留为 `PENDING` 并更新 nextAttemptTime，只有本批事务提交后才保存这次重试安排。调用方没有取得成功确认，但仍可能是 Redis 已发布、响应在返回途中丢失，因此重新发布也可能产生重复提示。
- 如果 Redis `convertAndSend` 已经返回，随后 `markPublished` 影响 0 行或数据库提交失败，外部发布可能已经发生，但通知行仍可能回滚成 `PENDING`。下一轮只能再次发布，用户可能收到重复提示。
- 通知服务一次事务处理一批行，因此后面某条 marker 异常还可能回滚前面已经更新的数据库 marker；之前已经发到 Redis 的消息不能一起回滚。客户端应按 eventId 或 grantId 去重，再刷新券包，不能把重复提示解释成重复发券。

### 已有 Grant 是否会自动补建通知

不会。通知表由 V11 创建，迁移没有为 V9/V10 的历史 Grant 回填记录；当前单人门面和事务只要查到已有 Grant，就在创建通知待办之前返回。因此历史 Grant，或其他原因已经存在但没有 Outbox 的 Grant，不会因用户重试、批量重跑或通知 worker 扫描而自动补建通知。通知 worker 只能处理已经存在的 `PENDING` 记录。

## 九、放到具体业务情形里理解

### 同一用户从用户端和后台同时领同一活动券

假设活动 101 是 `BOTH`，用户 2001 点击领取的同时，管理员也给 2001 单发。两个入口生成的来源和操作人不同，但防重身份都是 `ONCE`。两边可能都在事务外查到空，随后竞争同一活动行；一边提交 Grant 后，另一边可能在后续查询看到已有权益，或在规则仍允许时撞唯一约束、回滚自己的额度增量，再在事务外读回胜者。若先遇到额度已满或规则变化，也可能返回业务拒绝，但不会再发一份。结果只有一份 Grant 和一条与新 Grant 同时写入的通知待办，记录的来源以真正提交的一方为准。

### 两个人争最后一个名额

假设活动总额度 100，当前 `granted_count=99`，用户 2001 和 2002 同时领取。简单地各自先查“还有一份”会导致两人都继续；当前实现让两人竞争活动行锁。先取得锁的一方条件更新到 100 并写 Grant，后取得锁的一方看到或被条件更新判定额度已满，返回额度耗尽。这里唯一约束不是主要工具，因为两位用户的 Grant 身份不同；真正裁定最后名额的是活动锁和条件占额。

### 批任务创建后，成员资格改变

假设 worker 固化快照时标签有用户 A、B，快照后 B 被移除，又新增用户 C。Item 快照仍只有 A、B：C 不会临时加入这次任务；B 尚无本活动的 `ONCE` Grant 时，执行到当前资格检查会被记为 `SKIPPED`，已有 Grant 则先按 `IDEMPOTENT` 返回，不撤销既有权益。这样既能回答“本批最初选中了谁”，又不会拿旧名单新发给已失去资格的用户，代价是想给 C 或重新评估 B 必须再创建新 Job。创建 Job 与 worker 固化快照之间的成员变化，则会影响这次实际选中的名单。

### 发到一半进程退出

如果退出发生在一个 Grant 提交后、Item 外层事务提交前，券已经到账，Item 可能仍是 `PENDING`；下次按已有 Grant 收敛为 `IDEMPOTENT`。如果退出发生在整个单人事务提交前，占额、Grant 和通知待办一起回滚，下次可以重新尝试。单凭 Item 状态不能断言用户有没有券，排查时应先查 Grant。

### 券已到账，但用户没有收到提示

先查 `/voucher-grants/mine` 对应的 MySQL Grant：有 Grant 就说明权益已成立。再看通知表是 `PENDING` 还是 `PUBLISHED`。`PENDING` 可能是通知 worker 默认未启用、尚未到重试时间或 Redis 发布失败；`PUBLISHED` 也只表示 Redis 发布和数据库 marker 完成，用户可能不在线、订阅实例或 WebSocket 发送失败。当前没有离线消息补发和用户 ACK，处理方式是保留 Grant，并让用户重新查询券包，不是撤销后重发券。

## 十、源码反查与必要修正

- 用户领取、任务奖励和券包查询：[VoucherCampaignController](../../../src/main/java/com/localdeals/controller/VoucherCampaignController.java)、[VoucherGrantController](../../../src/main/java/com/localdeals/controller/VoucherGrantController.java)、[VoucherCampaignUserService](../../../src/main/java/com/localdeals/service/VoucherCampaignUserService.java)。
- 后台准备、商户归属、单发和批任务入口：[AdminCatalogService](../../../src/main/java/com/localdeals/service/AdminCatalogService.java)、[MarketingAdminController](../../../src/main/java/com/localdeals/controller/MarketingAdminController.java)、[MarketingAdminService](../../../src/main/java/com/localdeals/service/MarketingAdminService.java)。
- 统一查重和单人权益事务：[VoucherGrantService](../../../src/main/java/com/localdeals/service/VoucherGrantService.java)、[VoucherGrantTransactionService](../../../src/main/java/com/localdeals/service/VoucherGrantTransactionService.java)、[VoucherCampaignMapper](../../../src/main/java/com/localdeals/mapper/VoucherCampaignMapper.java)、[VoucherGrantMapper](../../../src/main/java/com/localdeals/mapper/VoucherGrantMapper.java)。
- 签到与业务日期：[UserServiceImpl](../../../src/main/java/com/localdeals/service/impl/UserServiceImpl.java)、[SignMapper](../../../src/main/java/com/localdeals/mapper/SignMapper.java)、[BusinessDateProvider](../../../src/main/java/com/localdeals/service/BusinessDateProvider.java)。
- 批量任务与明细：[VoucherBatchJobService](../../../src/main/java/com/localdeals/service/VoucherBatchJobService.java)、[VoucherBatchJobWorker](../../../src/main/java/com/localdeals/service/VoucherBatchJobWorker.java)、[VoucherBatchJobMapper](../../../src/main/java/com/localdeals/mapper/VoucherBatchJobMapper.java)、[VoucherBatchItemMapper](../../../src/main/java/com/localdeals/mapper/VoucherBatchItemMapper.java)。
- 通知待办与在线提示：[VoucherGrantNotificationOutboxService](../../../src/main/java/com/localdeals/service/VoucherGrantNotificationOutboxService.java)、[VoucherGrantNotificationOutboxWorker](../../../src/main/java/com/localdeals/service/VoucherGrantNotificationOutboxWorker.java)、[VoucherGrantNotificationOutboxMapper](../../../src/main/java/com/localdeals/mapper/VoucherGrantNotificationOutboxMapper.java)、[WebSocketNotifier](../../../src/main/java/com/localdeals/websocket/WebSocketNotifier.java)、[WebSocketConfig](../../../src/main/java/com/localdeals/config/WebSocketConfig.java)。
- 表结构、唯一约束与默认开关：[V9](../../../src/main/resources/db/migration/V9__targeted_voucher_campaign.sql)、[V10](../../../src/main/resources/db/migration/V10__daily_sign_task_rewards.sql)、[V11](../../../src/main/resources/db/migration/V11__batch_grant_notification_outbox.sql)、[VoucherBatchProperties](../../../src/main/java/com/localdeals/config/VoucherBatchProperties.java)、[application.yaml](../../../src/main/resources/application.yaml)。
- 并发可见性与事务边界的依据：[MySQL 一致性读](https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html)、[Spring 事务传播](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)。第七节对当前调用链的影响是结合这些语义与源码作出的推演，没有运行并发复现。

相对旧笔记需要保留三项修正。第一，签到只写每日签到事实，任务奖励必须由另一个请求领取，不能说成“签到成功后自动发券”。第二，`PUBLISHED` 不是用户收到或已读，通知次数达到上限也不会停止，当前没有通知 `FAILED/DLQ`。第三，“锁后第三次查重一定看见等待期间的新 Grant”不能从当前源码直接推出；Grant 唯一约束和单人事务回滚保证不重复形成权益，但能否当场返回已有 Grant，还受中途业务检查及门面重读时是否沿用外层事务快照影响。

本文依据当前源码、Mapper、V9～V11 迁移和配置做静态阅读，没有启动服务、执行迁移或运行测试；因此能说明当前实现和默认值，不能证明某个实际部署已打开 worker、历史库已经完成迁移或通知已经送达用户。
