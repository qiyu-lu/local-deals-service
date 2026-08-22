# M6A 商户定向发券最小闭环实施契约

> 状态：BLOCKED；命中共享依赖停止线，M6A 未完成
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
