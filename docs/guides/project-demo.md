# M7 15 分钟项目演示手册

> 目的：展示边界、事实来源和恢复路径。演示不启动共享依赖、不删除共享数据、不执行 broad prune，
> 也不把人工步骤或 consumer-level 结果说成完整 E2E。

## 0. 演示前置

推荐使用已经准备好的专用环境或录屏/结果文档。M7 最小检查本身不启动 MySQL、Redis、
RocketMQ 或 Elasticsearch。

如果确实要现场展示外部依赖，必须使用新的专用 run-id、专用端口、sentinel/label guard 和
strace 目标白名单；不能复用共享 `3306`、`6379`、`9876`、`10911`、`9200`。外部运行应先
取得对应阶段的隔离环境证据，再按本手册只做小范围人工展示，不重复完整 M5D/M6C 矩阵。

| 内容 | 当前证据层级 | 演示方式 |
| --- | --- | --- |
| 商户登录与跨商户拒绝 | 集成测试 + 人工演示 | 现场浏览器/HTTP；结论以 `AdminRbacIT` 和权限文档为准 |
| 秒杀 `PROCESSING` → `SUCCESS` | 专用真实 IT + consumer-level | 现场状态查询或播放 M5D F3 结果；不要只展示 HTTP 200 |
| Redis/DB/Broker/ES 故障 | 专用真实 IT | 播放已有 F1–F5 结果，必要时只读查看专用容器证据 |
| Redis Pub/Sub 通知恢复 | 专用真实 IT | 播放 M6C Redis recovery 证据，Outbox 以 MySQL 行为事实 |
| WebSocket 断线兜底 | 前端 contract-level/manual-demo-ready | 人工断开浏览器连接，展示有限轮询；没有浏览器 E2E 就明确说明 |
| Canal → ES | consumer-level 边界 | 展示边界说明，不称为 Canal E2E |

## 1. 15 分钟顺序

### 0:00–2:00：系统边界与数据真相

打开 [M7 证据索引](../evidence/m7/m7-evidence-index.md)，先讲系统图：

- 用户端和管理端经 nginx 进入单体应用；身份、目录、秒杀、内容和营销仍在同一应用边界内；
- MySQL 是长期业务事实，Redis 保存会话、预约和可重建读模型；
- RocketMQ 只承担当前秒杀异步订单边界；ES 是搜索读模型；Canal 的完整上游链路仍是边界；
- M6 发券通知走 Redis Pub/Sub + WebSocket，最终以 MySQL grant/Outbox 和持久券包查询为准。

这是人工讲解步骤，不是新的自动化验证。

### 2:00–5:00：商户账号与跨商户拒绝

1. 使用独立后台账号登录 `/admin/`，展示角色、merchant scope 和可见商铺。
2. 尝试把另一个 merchant 的 shop/campaign/tag ID 放入查询或写请求，展示后端拒绝/不可见结果。
3. 指出权限不是由前端菜单决定，后端 MVC、DTO 和范围 SQL 都会再次复核。

证据来源：M5/M6 的安全测试和 [商户后台与 RBAC](../design/admin-rbac.md)。这是“集成测试证据 +
人工演示”，不应把一次浏览器 404 当成全部权限矩阵覆盖。

### 5:00–8:00：秒杀请求、消息与状态查询

1. 展示一次新秒杀请求经过 activity/user/IP 准入后返回订单 ID 字符串。
2. 在消息尚未消费时查询 `/voucher-order/status/{orderId}`，展示 `PROCESSING`。
3. 恢复 consumer 后再次查询 `SUCCESS`，同时从 MySQL/Redis 只读校验订单、reservation、
   库存和 processing index。
4. 说明永久冲突不会被“猜成无单”：活动进入 `SUSPENDED`，不安全项进入 `QUARANTINE`，
   reconciler 只按 exact ownership 分类。

这是 M5D F3 的专用真实 IT/consumer-level 结果加人工状态查询。不要用历史 Redis Stream
结果替代当前 RocketMQ 证据，也不要宣称消息绝不丢失。

### 8:00–10:00：断开 WebSocket 与持久兜底

1. 打开用户端“券包”，在 DevTools 手动断开 WebSocket 或模拟网络错误。
2. 展示 `onclose/onerror` 进入 `/voucher-grants/mine`，轮询最多 10 次并受总 deadline 限制。
3. 恢复连接或收到 `VOUCHER_GRANTED` 后展示列表刷新和 `eventId` 去重；终态 `SUCCESS/FAILED`
   后停止轮询。
4. 明确这是前端契约/人工演示就绪，不是已验证的真实浏览器 E2E；离线用户仍以持久券包为最终事实。

```bash
node scripts/check-m7-frontend.js
```

### 10:00–13:00：标签 → 签到/TASK → 批量 Job → grant/outbox

1. 管理端选择 `MANUAL_TAG` 且 `ADMIN/BOTH` 的活动，创建批量 Job；请求只创建 Job，不在 HTTP
   线程扫描或发券。
2. 展示 Job 的目标快照、`GRANTED/IDEMPOTENT/SKIPPED/FAILED` 计数和失败明细；现场点击
   pause/resume/retry-failures，说明 retry 只重置技术 `FAILED`。
3. 用户端先完成 `DAILY_SIGN_IN`，再展示 TASK reward；这条路径与批量 Job 共用统一 grant
   ledger，但不是新的规则 DSL。
4. 从 MySQL 只读查看 grant、`granted_count` 和 Outbox；Redis 可用时展示通知接收，不可用时
   展示 Outbox `PENDING`，恢复后成为 `PUBLISHED`。

证据来源：M6A/M6B/M6C 结果文档和 M6C 的 `m6c_20260823h` 真实 IT。M6C 只验证 Redis
Pub/Sub + WebSocket，不增加 RocketMQ 通知 topic/consumer。

### 13:00–15:00：故障矩阵、负面结果与升级边界

打开 [M7 故障矩阵](../evidence/m7/m7-failure-matrix.csv) 和 [M7 结果](../evidence/m7/m7-results.md)，
快速说明：

- M5D F1–F5 已有专用 run-id 证据；F4 是 consumer-level，不是 Canal E2E；
- Redis 全量数据丢失是 `NOT_TESTED`，没有 RPO；
- 本地恢复时间不是生产 SLA；
- 当前不支持 Redis Cluster、新旧秒杀协议滚动混跑、核销/支付/退款和 Spring Boot 3/Java 17；
- 只有当真实瓶颈或 RPO 需求被新的专用证据证明后，才另立升级阶段。

## 2. 只读证据查看命令

### 文档、提交和最终检查

```bash
git status --short
git log -8 --oneline
git show --stat --oneline 054b113
sed -n '1,220p' docs/evidence/m7/m7-evidence-index.md
column -s, -t < docs/evidence/m7/m7-failure-matrix.csv | sed -n '1,18p'
```

### 专用 M5D MySQL/Redis/RocketMQ

以下命令只适用于仍在运行且环境变量指向 `m5d-20260822a` 专用栈的现场；先执行
`docker inspect` 校验 label，不要把容器名改成共享实例。命令均为只读：

```bash
export M5D_RUN_ID=m5d-20260822a
export M5D_MYSQL_PASSWORD='从专用运行记录读取'
export M5D_REDIS_PASSWORD='从专用运行记录读取'
export M5D_SCHEMA=m5d_m5d_20260822a

docker inspect --format '{{index .Config.Labels "com.localdeals.m5d.run-id"}}' \
  "m5d-${M5D_RUN_ID}-mysql"
docker exec "m5d-${M5D_RUN_ID}-mysql" mysql -uroot "-p${M5D_MYSQL_PASSWORD}" \
  "${M5D_SCHEMA}" -N -e \
  'SELECT voucher_id,stock FROM tb_seckill_voucher WHERE voucher_id BETWEEN 9011 AND 9019 ORDER BY voucher_id;'
docker exec "m5d-${M5D_RUN_ID}-redis" redis-cli -a "${M5D_REDIS_PASSWORD}" --no-auth-warning \
  --raw ZCARD seckill:order:processing
docker exec "m5d-${M5D_RUN_ID}-broker" sh mqadmin consumerProgress \
  -n "m5d-${M5D_RUN_ID}-namesrv:9876" \
  -g "m5d-${M5D_RUN_ID}-seckill-consumer"
```

M5D F4 的 ES consumer-level 证据还可只读查看固定文档 ID；这不能改变其 Canal E2E 边界：

```bash
curl --fail "http://127.0.0.1:${M5D_ES_PORT}/shop/_doc/990001"
```

### 专用 M6C MySQL/Redis Outbox

```bash
export M6C_RUN_ID=m6c_20260823h
export M6C_MYSQL_PASSWORD='从专用运行记录读取'
export M6C_MYSQL_SCHEMA=m6c_20260823h_app

docker inspect --format '{{index .Config.Labels "com.localdeals.m6c.run-id"}}' \
  "m6c-${M6C_RUN_ID}-mysql"
docker exec "m6c-${M6C_RUN_ID}-mysql" mysql -uroot "-p${M6C_MYSQL_PASSWORD}" \
  "${M6C_MYSQL_SCHEMA}" -N -e \
  'SELECT status,COUNT(*),MIN(create_time),MIN(next_attempt_time) FROM tb_voucher_grant_notification_outbox GROUP BY status;'
docker exec "m6c-${M6C_RUN_ID}-redis" redis-cli --raw \
  GET "m6c:sentinel:${M6C_RUN_ID}"
```

Pub/Sub 本身是瞬时发布，不用 `PUBLISHED` 推断用户在线、已读或实际收到；以 Outbox 行和
`/voucher-grants/mine` 为最终展示依据。

## 3. 精确清理

优先用产生该 run-id 的隔离脚本 `down`，它会先校验 label/run-id，再删除本 run 的容器和网络：

```bash
# 使用创建该 M5D 栈时保存的全部 M5D_* 环境变量；不要省略端口/主题/密码门禁
M5D_ISOLATED=true M5D_RUN_ID=m5d-20260822a scripts/m5d-isolated-stack.sh down

# M6C 只需保存以下专用变量
M6C_ISOLATED=true M6C_RUN_ID=m6c_20260823h \
  M6C_MYSQL_PORT=24330 M6C_REDIS_PORT=27330 \
  M6C_MYSQL_PASSWORD='从专用运行记录读取' \
  scripts/m6c-isolated-stack.sh down
```

实际 M5D 还需要脚本要求的完整 `M5D_*` 变量；缺失时应让脚本 fail closed。清理后只检查
精确名称/label 的资源是否为 0，不执行 `docker system prune`、`docker volume prune`、
数据库全库删除或 Redis `FLUSHALL`。M6C 容器卷随精确 run-id 删除且不可恢复，必须先保留结果
文档和原始证据目录。
