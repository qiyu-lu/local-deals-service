# M6A 商户定向发券最小闭环实施契约

> 状态：IN PROGRESS；M6A-R0 隔离阻塞已解除，M6A 尚未完成；历史 BLOCKED 记录保留
>
> 起点：`2f83b6b docs(observability): record M5D recovery evidence`
>
> 范围：只实施 M6A；完成后 M6 仍为 in progress，M6B/M6C 未开始且未经授权。

## 1. 闭环与真相边界

M6A 只交付“商户人工标签 -> 普通券定向活动 -> 用户领取或单用户管理员发放 -> 统一事务 ->
发放流水 -> 我的优惠券”。`tb_voucher_grant` 是本阶段唯一用户持券事实，不复用
`tb_voucher_order`，也不创建第二套持券表。MySQL 自增 ID、唯一约束和事务决定正确性；Redis
只用于既有登录会话，RocketMQ/ES 不参与发券事务。

不实现签到/任务、批量导入或发券、调度、通知/outbox、Redis 标签缓存/Bitmap、规则 DSL、
核销/支付/退款、推荐、M6B/M6C、技术栈升级或 push。

## 2. 数据与商户隔离

- V9 新建 `tb_marketing_tag`、`tb_marketing_tag_member`、`tb_voucher_campaign`、
  `tb_voucher_grant`，不修改 V1--V8。
- 标签 code 在 merchant 内唯一；成员在 `(tag_id,user_id)` 唯一并以 ACTIVE/REMOVED 保留审计。
- 活动只允许 CLAIM/ADMIN/BOTH、ALL/MANUAL_TAG、DRAFT/ACTIVE/PAUSED/CLOSED；只能绑定
  `tb_voucher.type=0` 且 voucher -> shop -> merchant 与目标商户一致。
- grant 在 `(campaign_id,user_id)` 唯一，保存 source、rule_version、operator、granted_at。
- 商户只能给存在本商户历史 `tb_voucher_order` 或 `tb_voucher_grant` 的用户打标签。平台跨商户
  操作必须显式提交 merchantId；所有后台查询、锁定和更新 SQL 都携带 merchant scope。
- 新增 API 返回中的所有 Long ID 均序列化为字符串。

## 3. 统一 grant 事务

两个入口仅构造 `GrantCommand`，随后调用同一个非事务 facade。facade 先做快速幂等查询，调用
独立 Spring 事务 Bean，并在唯一键异常导致事务完整回滚后再次查询真实 grant。

事务 Bean 的固定顺序：

1. 查询 `(campaign_id,user_id)`，已有即返回相同 grant。
2. `FOR UPDATE` 锁定 merchant-scoped campaign，并校验状态、时间、mode、ruleVersion、普通券
   和 voucher 商户归属。
3. MANUAL_TAG 再锁定 `(merchant_id,required_tag_id,user_id)` 成员，要求 tag/member ACTIVE 且
   expire_time 为空或晚于 DB 当前时间。成员移除也锁同一成员行，因此竞态由取得成员锁的顺序决定。
4. 用单条条件 UPDATE 增加 `granted_count`；条件同时包含 merchant、ACTIVE、ruleVersion、
   DB 时间窗和 `granted_count < quota_total`。
5. 插入 grant。唯一键冲突不在事务内捕获，确保计数更新一并回滚。
6. UPDATE=0 时重新读取并精确归类 inactive/not-started/ended/rule-changed/quota-exhausted，
   不返回模糊 500。

必须恒成立：`granted_count = COUNT(grant)`、每活动用户最多一行、计数不超过 quota；claim 与
admin grant 并发仍只有一行；失败、503、无资格和旧规则版本既不插入也不消耗额度。

## 4. 接口、权限与错误语义

- 权限：平台管理员 read/write，merchant owner read/write，merchant staff read；代码为
  `marketing:read`、`marketing:write`。
- 后台接口严格为 tags/members、campaign CRUD/status、单用户 grant 和 grant list；grant body
  不接受数组/CSV。
- 用户接口为 shop campaign list、claim 和 `/voucher-grants/mine`，全部保留登录要求且不加入
  WebConfig public paths。claim 必须提交 ruleVersion。
- 稳定码：`CAMPAIGN_NOT_ACTIVE`、`CAMPAIGN_NOT_STARTED`、`CAMPAIGN_ENDED`、
  `CAMPAIGN_INELIGIBLE`、`CAMPAIGN_RULE_CHANGED`、`CAMPAIGN_QUOTA_EXHAUSTED`、
  `CAMPAIGN_GRANT_MODE_UNSUPPORTED`。参数 400，认证/授权 401/403，MySQL 不可用 503，未知
  错误 500；幂等重试为 200 并返回原 grant。

## 5. 指标

预注册 `local_deals.marketing.grant` Counter 和 `local_deals.marketing.grant.duration` Timer。
标签只有 `source=user_claim|admin_grant` 与
`result=granted|idempotent|ineligible|quota_exhausted|rule_changed|inactive|unavailable|failure`，
禁止 merchant/campaign/voucher/user ID、异常或 URI 标签。每次 facade 调用只记录一个终态。

## 6. 验收门禁

1. fresh V1->V9 与独立 V8->V9 均成功，核对表、约束、权限角色矩阵。
2. 标签/活动跨商户、陌生用户、平台未显式 merchantId 均拒绝且无副作用。
3. 专用真实 MySQL：同用户 100 并发=1 grant/计数+1；100 用户竞争 quota 10=恰好 10；claim 与
   admin 并发=1；旧 ruleVersion=0 副作用；成员移除竞态满足锁顺序；所有轮次核对恒等式、重复和
   P95/P99。任一重复、超发或计数不一致立即停止。
4. 上述并发门通过后才能增加 Controller/前端；MVC 覆盖匿名、消费者 token、read-only、写权限、
   跨商户和非公开用户接口。
5. `m6a-<run-id>` 专用 MySQL/Redis 验证 MySQL stop=503/0 副作用、Redis 登录失败=401/0 副作用，
   恢复后重试成功；身份不明确的共享依赖禁止使用。
6. Java 8 默认全量、风险定向、真实依赖 IT、`compile test-compile`、前端 build、Flyway、CSV、
   Shell/Python 语法和 `git diff --check` 全绿。只报告同轮请求分类与延迟，不写性能提升百分比。

## 7. 提交与停止线

按契约、schema/domain、grant、API/frontend、隔离测试、结果文档拆为可回滚本地提交；每次显式
暂存并检查 staged diff，不提交生成物或凭据，不 push。命中用户定义的任一停止线时保留原始证据、
写明 BLOCKED 和副作用并停止，不放宽不变量或扩展范围。

## 8. 阻塞记录（2026-08-22）

首次 `MarketingAdminIsolationIT` 使用了 run-id 专用 MySQL `127.0.0.1:24316` 和 Redis
`127.0.0.1:27389`，但 Spring RocketMQ 自动配置仍连接默认共享
`127.0.0.1:9876/10911`。该测试没有调用 MQ、没有发送消息，随后 JVM 已退出；测试事务回滚后
专用 fresh schema 中 fixture merchant/tag/member/campaign/grant 均为 0，专用 Redis `DBSIZE=0`。
尽管无已知业务副作用，“连接了无法确认身份的共享端口”仍命中本阶段停止线，因此不通过改配置后
反复重跑改写结论。

已完成但不构成 M6A 完成：契约提交；V9 草案及 Entity/Mapper/标签活动 service WIP；fresh
V1->V9 和 V8->V9 各 9 条 migration 通过；真实 MySQL 商户隔离测试 1/1 通过。统一 grant、并发
不变量、Controller、前端、故障验证、全量测试和结果收口均未执行。专用容器暂不删除，以保留
迁移现场；当前状态只能是 `M6A BLOCKED / M6 in progress / M6B/M6C not started and not authorized`。

## 9. R0 恢复尝试记录（2026-08-22）

本轮新 run-id 为 `m6a_20260822b`。旧 `m6a-20260822a` 容器虽然名称、label、镜像和端口
分别指向 `127.0.0.1:24316`、`127.0.0.1:27389`，但没有可验证的 MySQL run-id
sentinel，Redis run-id sentinel 也未通过，因此没有使用、修改或清理旧容器。

新建且仅用于本轮 R0 的容器为 `m6a-m6a_20260822b-mysql`（`mysql:8.0`，
`127.0.0.1:24317->3306`）和 `m6a-m6a_20260822b-redis`（`redis:7.2-alpine`，
`127.0.0.1:27390->6379`），网络和两个容器均带
`com.localdeals.m6a.run-id=m6a_20260822b`。MySQL sentinel 与 Redis sentinel 均返回
`m6a_20260822b`；Redis 只有 sentinel key，业务 key 为 0。未启动 RocketMQ，也未触碰
共享 `9876/10911`。

R0 正式命令使用 Java 8、Maven offline，并由 `strace -f -e trace=connect` 记录 Maven 和
Surefire 子进程。连接日志共 269 行，其中 13 次 TCP connect 全部为专用 MySQL
`127.0.0.1:24317` 的 IPv4-mapped IPv6 表示；其余为本机 nscd Unix socket。没有
`9876`、`10911`、`9200`、`3306`、`6379` 或其他无法解释的 AF_INET/AF_INET6 目标。
原始证据保留在 `/tmp/m6a-r0-20260822b.txNjKg/`。

本次正式 R0 仍为 `BLOCKED`，原因是测试 guard 的 target marker 在 Flyway 迁移前写入，
使 fresh schema 非空；独立 Flyway 因此没有执行 V1，V2 在找不到 `tb_voucher_order` 时
失败。`M6aFlywayIT` 和 `MarketingAdminIsolationIT` 共运行 2 个测试、均为 error；
后者的 Spring 上下文在 Flyway initializer 阶段失败，RocketMQ bean 断言尚未执行，
不能将其记为 PASS。失败后仅保留了 sentinel/marker 和部分 Flyway history，没有产生
merchant、tag、member、campaign、grant fixture，Redis 仍只有 sentinel。

target marker 已改为只有在 fresh/upgrade migration 和 schema assert 成功后才写入；失败或
部分迁移的 schema 没有 marker 时，下一次会在 DROP 前停止。该修正已包含在当前 HEAD 的
`1351a9b test(marketing): isolate M6A integration context`，不再是未提交 WIP。`m6a_20260822b`
的上述失败仍是历史负面证据，不能改判为 PASS；按本轮执行规则，普通测试代码/fixture/业务
缺陷可以保留证据后修复并使用新的 run-id 重跑。契约偏差、统一 grant、并发不变量、Controller、
前端、故障验证、全量测试均未开始；当前仍为
`M6A BLOCKED / M6 in progress / M6B/M6C not started and not authorized`。

## 10. M6A-R0 隔离恢复通过（2026-08-22）

新的 run-id 为 `m6a_20260822c`。在使用前已删除经过精确名称、镜像、label 和端口核对的旧
`m6a-20260822a` 与 `m6a_20260822b` 专用容器，以及 b 的专用网络；未执行 broad prune，
共享 RocketMQ 容器和 `9876/10911` 未修改。c 只建立了 `mysql:8.0` 容器
`m6a-m6a_20260822c-mysql`，label 为 `com.localdeals.m6a.run-id=m6a_20260822c`，通过
`127.0.0.1:24318->3306` 提供服务；没有启动 Redis、RocketMQ 或 ES。MySQL sentinel 为
`m6a_20260822c_sentinel.m6a_run_sentinel`，run-id 和 purpose 均精确匹配。

正式运行使用 Java 8、Maven offline 和 `strace -f -tt -yy -e trace=connect`，命令只执行
`M6aFlywayIT,MarketingAdminIsolationIT`。完整证据保存在
`/tmp/m6a-r0-20260822c.MOFKLl/`：25 次 connect 中 19 次 TCP 全部是
`::ffff:127.0.0.1:24318`，6 次是本机 nscd Unix socket；没有 `9876`、`10911`、`9200`、
`3306`、`6379` 或其他 AF_INET/AF_INET6 目标。Maven/Surefire 日志没有 producer、consumer
启动、client register 或 NameServer 连接标记。

`MarketingAdminIsolationIT` 的实际 Spring bean absence 断言通过：不存在
`DefaultMQProducer`、`DefaultLitePullConsumer`、`DefaultMQPushConsumer`、
`DefaultRocketMQListenerContainer`、`RocketMQTemplate`、`RedisConnectionFactory` 和
`RestHighLevelClient`。测试结果为 `M6aFlywayIT 1/1`、`MarketingAdminIsolationIT 1/1`，
事务回滚后 M6A 专用 merchant、admin、shop、voucher、voucher order、tag、member、campaign、
grant fixture 均为 0；V1 基线数据不计为 M6A fixture。fresh V1→V9 和 upgrade V8→V9 均为
成功 history=9，且 target marker=1。

因此追加结论：`m6a_20260822c R0 PASS / historical blockers retained`。`d026db3` 的原始
BLOCKED 结论和 `m6a_20260822b` 的失败均保留，不改判为 PASS；当前阶段恢复为
`M6A IN PROGRESS / M6B/M6C not started and not authorized`。

## 11. M6A 继续实施与验证证据（2026-08-22）

本节记录 R0 通过后继续实施的当前状态；第 8、9 节的历史 BLOCKED 记录不改写。

### 11.1 契约修正与提交链

以下偏差已在当前提交链中修正：

- `VoucherCampaignRequest` 和 `VoucherCampaignStatusRequest` 携带 expected status/ruleVersion；
- 活动更新只允许 `DRAFT/PAUSED`，SQL 同时限制 `merchant_id`、expected status/version、
  `granted_count <= quota_total`；状态流转成功后递增版本；
- 已产生 grant 后禁止修改 voucher；后台 scoped 查询强制 merchantId，用户领取使用独立查询；
- V9 通过 `(campaign_id, merchant_id, voucher_id)` 约束 grant 必须来自同一条锁定 campaign；
- Flyway IT 读取并校验 `M6A_RUN_ID`，由 run-id 派生 fresh/upgrade schema，并在专用 MySQL
  sentinel、目标 schema 和迁移/schema assert 均通过后才写 marker。

当前本地提交链为：`a4f9326`（标签/活动）、`5d5b22c`（统一 grant ledger）、`daaa2f6`
（隔离与并发不变量）、`c387116`（Controller/前端/指标）和 `b9f6a0c`（故障路径）。
历史 `d026db3`、`446455d`、`ea9245e`、`9e98ce3` 均保留，未 amend、squash 或删除。

### 11.2 真实 MySQL/Redis 证据

- `M6aFlywayIT`：fresh V1→V9、upgrade V8→V9 均为 history=9，schema assert 和权限矩阵通过；
  c 的 target marker=1。`MarketingAdminIsolationIT` 商户隔离 1/1，事务回滚后 M6A
  merchant/admin/shop/voucher/order/tag/member/campaign/grant fixture 均为 0。
- `MarketingGrantConcurrencyIT` 5/5：同用户 100 并发只有 1 grant 且计数 +1；100 用户竞争
  quota=10 恰好 10；USER_CLAIM/ADMIN_GRANT 同用户只有 1；旧 ruleVersion 零副作用；标签
  移除竞态不破坏不变量。最终证据为 `/tmp/m6a-final-20260822c.ZZd31C/`，TCP connect
  全部只到 `127.0.0.1:24318`，另有本机 nscd Unix socket。
- 实际 bean absence 断言通过：不存在 `DefaultMQProducer`、`DefaultLitePullConsumer`、
  `DefaultMQPushConsumer`、`DefaultRocketMQListenerContainer`、真实 `RocketMQTemplate`、
  `RedisConnectionFactory` 和 `RestHighLevelClient`；日志无 producer/consumer 启动、client
  register 或 NameServer 连接。
- Redis 故障测试使用 c 的专用 `127.0.0.1:27391`，`M6aRedisAuthFailureIT 1/1`，错误 token
  返回 401、UserHolder 为空、DBSIZE 未增加；Redis 仅有 run-id sentinel。MySQL 停止故障测试
  `M6aMysqlStopFailureIT 1/1`，精确停止/恢复 c 容器，发放返回 503/DATABASE_UNAVAILABLE，
  grant 计数无副作用。证据分别为 `/tmp/m6a-redis-20260822c.kBxLQ1/` 和
  `/tmp/m6a-mysql-fault-20260822c.HD9KJK/`。

### 11.3 API、前端、指标和回归边界

Controller 安全测试 3/3，低基数 grant source/result 指标测试通过；管理端最小前端 build
通过。Java 8 下安全的非外部单元回归为 307/307（`/tmp/m6a-java8-unit-20260822c.ck4N7F/`）。
未执行未加隔离门禁的旧 RocketMQ/Redis/ES/WebSocket/搜索 IT；其中部分会访问共享
`9876/10911` 或默认 `3306/6379/9200`，继续执行会违反本阶段硬停止线，因此不能把本轮称为
“无条件全量外部回归”。不实施 M6B/M6C、批量发券、MQ/Outbox/通知或技术栈升级。

### 11.4 清理与当前阶段

已按精确名称、label、镜像、网络和端口核对并删除 `m6a_20260822a`、`m6a_20260822b` 的
专用容器/网络；随后删除 c 的 `m6a-m6a_20260822c-mysql`（`24318->3306`）、
`m6a-m6a_20260822c-redis`（`27391->6379`）及 `m6a-m6a_20260822c-net`。未执行 broad
prune，未触碰共享 RocketMQ；c 的 schema、sentinel、marker、connect、日志和测试结果已
保留在本节及 `/tmp` 证据目录。当前阶段仍为 `M6A IN PROGRESS`；未 push。
