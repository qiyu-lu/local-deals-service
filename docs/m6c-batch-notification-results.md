# M6C 批量发券与通知 Outbox 实施结果

> 状态：M6C COMPLETED；M6 COMPLETED；M7 not started；未 push
>
> 最终隔离 run-id：m6c_20260823h
>
> 机器可读摘要：docs/m6c-batch-notification-summary.csv

## 1. 结论

M6C 已在 Java 8、既有 Spring Boot/MySQL/Flyway/MyBatis/Redis/WebSocket 技术栈内完成。
HTTP 创建入口只创建 Job；后台 Job 服务在 MySQL 行锁下完成手工标签快照和小批次处理；每个
item 通过统一 VoucherGrantService，真正新增 grant、活动 granted_count 和通知 Outbox
在一个数据库事务内提交。幂等结果不增加额度，也不生成第二条 Outbox。

通知只使用现有 Redis Pub/Sub 与 WebSocket 独立用户频道。Redis 停止时 grant 事实和 Job
仍完成，Outbox 保持 PENDING 并重试；恢复后以 eventId 投递并收敛为 PUBLISHED。PUBLISHED
只表示 Redis 已接受发布，不表示用户在线、已读或实际收到；用户端以
/voucher-grants/mine 为持久事实。

未实施核销、支付、退款、任意规则表达式、文件上传、全库扫描、动态人群、审批、定时营销、
通知中心、短信/邮件/推送平台、新 RocketMQ topic/consumer 或技术栈升级。

## 2. 最终隔离证据

- MySQL：m6c-m6c_20260823h-mysql，mysql:8.0，宿主 127.0.0.1:24330->3306。
- Redis：m6c-m6c_20260823h-redis，redis:7.2-alpine，宿主 127.0.0.1:27330->6379。
- 网络：m6c-m6c_20260823h-net；容器 label 的 run-id/role 与上述名称精确匹配。
- 应用 schema：m6c_20260823h_app；sentinel schema：m6c_20260823h_sentinel；Redis
  sentinel 为 m6c:sentinel:m6c_20260823h=m6c_20260823h。
- 本轮只启动 MySQL 与 Redis，没有启动 RocketMQ 或 Elasticsearch。
- 最终 M6C Java 证据：
  /tmp/m6c-m6c_20260823h/m6c-tests.log、
  /tmp/m6c-m6c_20260823h/m6c-java-network.strace。
- M6A/M6B 定向回归证据：
  /tmp/m6c-m6c_20260823h/m6a-m6b-regression.log、
  /tmp/m6c-m6c_20260823h/m6a-m6b-regression.strace。

M6C 隔离脚本最终报告只允许 MySQL 24330、Redis 27330 和本机 Unix socket。Java 连接器出现的
::ffff:127.0.0.1 映射 loopback 已纳入同样的 loopback 规则；没有观察到 3306、6379、
9876、10911、9200 或未知 AF_INET/AF_INET6 目标。

## 3. 迁移与业务测试

| 场景 | 结果 | 关键证据 |
| --- | --- | --- |
| V1 -> V11 fresh | 1/1 PASS | Flyway history=11；三张 M6C 表、索引、外键和 BATCH_GRANT 存在 |
| V10 -> V11 upgrade | 1/1 PASS | 历史 ONCE grant 保留；Outbox 行数为 0；无历史通知回填 |
| 批量业务 IT | 6/6 PASS | requestId 并发、快照、资格复核、100 item、ONCE 竞争、pause/resume、retry、跨商户和事务回滚 |
| Redis 恢复 IT | 1/1 PASS | Redis 停止期间 Job/grant 完成；PENDING 重试；恢复后 PUBLISHED |
| M6A/M6B 定向回归 | 5/5 PASS | M6A USER_CLAIM/ADMIN_GRANT 1/1；M6B TASK_REWARD 4/4 |
| Java 8 compile/test-compile | PASS | Maven offline，Dragonwell Java 8 |
| 安全非外部 Java 单元/MVC/指标/WebSocket | 24/24 PASS | 无 RocketMQ/ES/共享 MySQL/Redis 依赖 |
| Node 前端契约 | PASS | scripts/check-m6c-frontend.js |
| admin Vite build | PASS | 构建成功；仅保留既有 Vite/CJS/chunk 警告 |

M6A/M6B 回归使用最终 M6C 专用 MySQL 端口但分别设置
M6A_RUN_ID=m6a_m6c_regression、M6B_RUN_ID=m6b_m6c_regression，两个阶段 guard
均通过；回归 strace 的 TCP 目标只为 24330，未连接 Redis、RocketMQ 或 ES。

## 4. 批量结果与恢复指标

以下数值来自测试执行中的真实数据库状态或明确的直接批次计时：

- 100 item 场景：target=100，granted=100，idempotent=0，skipped=0，failed=0。
- 活动剩余额度：0；campaign.granted_count=100，与新增 grant 数一致；Outbox=100。
- 小批次：batch size=17，worker-equivalent 批次数=6（首批 17 + 恢复后 5 批），总收敛时间
  722ms。
- item 处理延迟 P99：NA；本阶段没有建立逐 item latency histogram，不用批次耗时冒充 P99。
- 入口限流数：NA；M6C 没有新增入口限流，不用 0 冒充测量。
- Redis 停止后：真实 Outbox pending=1；oldest age=0ms，来源为真实 pending 行的
  create_time；该数值很小是因为测试在写入后立即采样，不是用瞬时零替代。
- Redis 恢复发布：recovery time=25ms；恢复后 pending=0、状态=PUBLISHED。
- 失败明细：稳定规则/资格/额度/标签结果进入 SKIPPED 并保存稳定码；技术错误进入 FAILED，
  retry-failures 只重置 FAILED。

## 5. 数据与权限边界

- Job 以 merchant_id、request_id 唯一；并发相同 requestId 返回同一个 durable Job。
- 快照使用一次 INSERT ... SELECT；快照后加入/移除成员不改变 item 集合，处理时仍重新
  锁定并复核当前标签资格。
- USER_CLAIM、ADMIN_GRANT、其他 BATCH_GRANT 与同活动用户均收敛到 ONCE；提前领取时 item
  为 IDEMPOTENT，额度和 Outbox 不重复增加。
- BATCH_GRANT 需要 ADMIN/BOTH、MANUAL_TAG、merchant-scoped campaign lock 和 operatorId；
  跨商户 campaign、tag、operator 不能越权。
- 统一 grant 指标只使用有限枚举，批量 source 映射为 source=batch_grant，没有 jobId、
  campaignId、merchantId 或 userId 标签。
- 管理 API 统一使用 marketing:read/write 和既有 merchant scope；MVC/DTO 回归通过。
- 前端后台终态为 COMPLETED/PARTIAL_FAILED 时停止轮询；用户 WebSocket 按 eventId 去重并
  刷新持久券包，不建设消息中心。

## 6. 保留的失败尝试与边界

失败尝试没有被改写为 PASS，保留在独立 evidence 目录：

- m6c_20260823b：初轮暴露复合索引断言、MyBatis mapper proxy 不可 SpyBean、Redis 重启后
  临时 sentinel 丢失；修正为真实 trigger 回滚测试和显式恢复 marker。
- m6c_20260823c：requestId 查询的 gap lock 与活动锁顺序造成真实 MySQL deadlock；改为
  普通预读、活动行锁和插入后的锁定 durable read。
- m6c_20260823d：MySQL REPEATABLE READ 下插入后普通一致性读看不到并发事务刚提交的 Job；
  改为带计数的 FOR UPDATE 当前读。
- m6c_20260823e：全部 8 个 Java M6C 测试已通过，但 strace 解析器未接受合法的 IPv4-mapped
  IPv6 loopback；扩展 parser 后以新 run-id 重跑。
- m6c_20260823f、m6c_20260823g：修正后的隔离审计/指标验证证据保留；最终带完整指标的
  m6c_20260823h 为收口证据。

未运行会连接共享 RocketMQ、ES 或默认 3306/6379 的旧外部 IT；这不被表述为 MQ/ES 通知链路
已验证。M6C 只验证了 Redis Pub/Sub + WebSocket 投递边界和持久查询回退。

## 7. 收口

- M6C：COMPLETED。
- M6：COMPLETED。
- M7：not started。
- 本地提交已按 schema/业务/outbox/frontend/test/docs 意图分段；未 push。
- 证据完成后只按精确 run-id 清理专用容器和网络，未执行 broad prune。
