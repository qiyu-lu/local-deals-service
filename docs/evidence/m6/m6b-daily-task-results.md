# M6B 每日签到任务与每日任务奖励结果

## 结论

- `M6B COMPLETED`：只实现固定任务 `DAILY_SIGN_IN`、MySQL 签到事实和 TASK 每日奖励。
- `M6 IN PROGRESS`。
- `M6C`（批量发放与通知 Outbox）未开始且未经授权。
- 未升级技术栈，未 push；M5/M6A 历史 BLOCKED 证据未改写。

## 实施闭环

- 新增 V10：`tb_sign(user_id,date)` 唯一约束；`TASK`、`TASK_REWARD` 枚举；grant 非空
  `idempotency_key`；V9 历史 grant 回填 `ONCE`；唯一边界调整为
  `(campaign_id,user_id,idempotency_key)`。
- `/user/sign` 和 `/user/sign/count` 只以 MySQL 日期记录为事实；Redis Bitmap 不再参与签到正确性。
- 默认业务时区为 `Asia/Shanghai`，日期由服务端 `Clock`/`BusinessDateProvider` 生成；客户端不传
  `bizDate` 或 `idempotencyKey`。
- 新增 `POST /voucher-campaigns/{campaignId}/task-reward`，请求只接收 `expectedRuleVersion`。
  TASK 领奖在统一 grant 事务内校验当天签到、TASK 模式、版本、时间、额度和标签资格。
- 普通 `CLAIM/BOTH` 仍使用 `ONCE`；TASK 查询只按当天 key 判断已领取；指标 source 仅增加低基数
  `task_reward`。
- 管理端支持 TASK 模式并隐藏手工发放；用户详情页按“签到→领奖”调用两个接口，发券失败可重试。

## 真实隔离证据

最终授权 run 为 `m6b_20260822e`：专用 MySQL 容器
`m6b-m6b_20260822e-mysql`、专用网络 `m6b-m6b_20260822e-net`、宿主端口
`127.0.0.1:24325`。guard 拒绝 3306、非 loopback 和缺少 sentinel 的配置；网络核对时只包含该
MySQL 容器。

- Flyway fresh V1→V10：`1/1 PASS`，history `10`。
- Flyway upgrade V9→V10：`1/1 PASS`，history `10`；历史 grant 为 `ONCE`，TASK 枚举和两组新唯一边界存在。
- 业务 IT：`4/4 PASS`。100 并发签到最终一条事实；100 并发 TASK 领奖最终一条当天 grant、
  `granted_count` 只加 1；同日重试返回同一 grant；固定 Clock 跨 Asia/Shanghai 午夜后生成第二条
  签到和第二份每日 grant，计数加 1。
- 未签到稳定返回 `TASK_NOT_COMPLETED`；旧 ruleVersion、非 TASK、额度耗尽和跨商户操作均无 grant/
  quota 副作用；M6A 普通 `ONCE` 的 USER_CLAIM/ADMIN_GRANT 交叉并发和历史 `ONCE` 查询回归通过。
- 业务 IT 后专用 fresh schema 中 `tb_voucher_grant`、M6B campaign、签到和 M6B merchant fixture
  均为 `0`。
- Flyway strace 只出现 `AF_INET/AF_INET6 ::ffff:127.0.0.1:24325`（12 次）和本机 nscd Unix
  socket；业务 strace 只出现专用端口 `24325`（22 次）和本机 nscd Unix socket。未连接共享
  MySQL、Redis、RocketMQ 或 ES；未做 Redis/MQ/ES 故障实验。
- 完成证据后按精确名称移除了 a–e 五次尝试的容器和网络，未执行 broad prune；最终匹配资源为 0。

## 失败尝试保留

- `m6b_20260822a`：sentinel schema 缺失，fail-closed guard 在迁移前停止；没有放宽 guard。
- `m6b_20260822b`：迁移已执行，但测试把复合索引的列行数当成索引数，断言失败；修正为按
  `DISTINCT index_name` 检查后用新 run 重跑。
- `m6b_20260822c`：Flyway 通过，业务测试因固定 Clock 测试 bean 同名冲突未触达业务；改为独立
  `@Primary` 测试 Clock 后用新 run 重跑。

这些失败保留为命令/测试配置证据，不改判为业务 PASS，也未通过降低并发或放宽业务断言收口。

## 其他验证

- Java 8 `compile`、`test-compile`：PASS。
- Java 8 默认安全单元集：`312/312 PASS`；包含新日期、签到、TASK grant、指标和 MVC 契约。
- Node `scripts/check-m6b-frontend.js`：PASS，覆盖签到顺序、请求体字段、重复点击和失败重试。
- admin `npm run build`：PASS；仅保留既有 Vite/CJS、chunk size 和依赖注释警告。
- `git diff --check`：PASS。

## 未实施

未实施任务定义 CRUD、任务 DSL、积分商城、连续签到阶梯、补签、定时任务、批量 job、通知
Outbox、MQ、核销、支付或退款。
