# M6A 商户定向发券实施结果

> 状态：M6A IN PROGRESS；R0 已通过；历史 BLOCKED 记录保留
>
> 正式隔离 run-id：`m6a_20260822c`
>
> 机器可读摘要：`docs/m6a-targeted-grant-summary.csv`

## 1. 结论与范围

`m6a_20260822c` 的 R0 已解除当前隔离阻塞。持久层上下文不加载 RocketMQ、Redis 或 ES
runtime bean；真实 MySQL fresh/upgrade、商户隔离、事务回滚、grant 并发不变量、Controller、
前端和依赖故障路径均形成了可追踪证据。原始 `d026db3` BLOCKED 和
`m6a_20260822b` 失败没有改判为 PASS。

本结果不声称旧的、未加隔离门禁的 RocketMQ/Redis/ES/WebSocket/搜索 IT 全量通过。那些测试
会访问共享 `9876/10911` 或默认 `3306/6379/9200`，本轮按硬停止线未执行。

## 2. 隔离身份与迁移

- MySQL：`m6a-m6a_20260822c-mysql`，`mysql:8.0`，label
  `com.localdeals.m6a.run-id=m6a_20260822c`，`127.0.0.1:24318->3306`。
- R0 只启动上述 MySQL；Redis 只为故障测试临时建立
  `m6a-m6a_20260822c-redis`，`redis:7.2-alpine`，`127.0.0.1:27391->6379`；没有启动
  RocketMQ 或 ES。
- 网络为 `m6a-m6a_20260822c-net`，label 与 run-id 精确匹配。MySQL sentinel 为
  `m6a_20260822c_sentinel.m6a_run_sentinel`，Redis 仅有
  `m6a:sentinel:m6a_20260822c=m6a_20260822c`。
- fresh `m6a_20260822c_fresh` 和 upgrade `m6a_20260822c_upgrade` 均为 Flyway history=9，
  schema assert、四张 M6A 表和 5 行权限角色矩阵通过；target marker 只在这些检查成功后写入。
- c 的两个容器和网络已在证据保留后按精确名称删除；旧 a/b 资源也已按精确 label/名称清理。
  未执行 broad prune，未触碰共享 RocketMQ。

## 3. 测试结果

| 门禁/场景 | 结果 | 证据 |
| --- | --- | --- |
| `M6aFlywayIT` fresh/upgrade | 1/1 PASS | `/tmp/m6a-r0-20260822c.MOFKLl/` |
| `MarketingAdminIsolationIT` 商户隔离、bean absence、事务回滚 | 1/1 PASS | `/tmp/m6a-r0-20260822c.MOFKLl/` |
| `MarketingGrantConcurrencyIT` 五个真实 MySQL 不变量 | 5/5 PASS | `/tmp/m6a-final-20260822c.ZZd31C/` |
| Controller MVC 安全 | 3/3 PASS | `/tmp/m6a-api-20260822c.c7bglp/` |
| grant failure/metrics 定向测试 | PASS | `/tmp/m6a-java8-unit-20260822c.ck4N7F/` |
| `M6aRedisAuthFailureIT` | 1/1 PASS | `/tmp/m6a-redis-20260822c.kBxLQ1/` |
| `M6aMysqlStopFailureIT` | 1/1 PASS | `/tmp/m6a-mysql-fault-20260822c.HD9KJK/` |
| Java 8 安全非外部单元回归 | 307/307 PASS | `/tmp/m6a-java8-unit-20260822c.ck4N7F/` |
| admin 前端 build | PASS | Vite build，无错误 |

并发不变量为：同用户 100 并发只有 1 grant 且计数 +1；100 用户竞争 quota=10 恰好 10；
USER_CLAIM 与 ADMIN_GRANT 同用户只有 1；旧 ruleVersion 零副作用；标签移除竞态不破坏锁定
顺序和额度不变量。测试结束后 M6A merchant/admin/shop/voucher/order/tag/member/campaign/
grant fixture 均为 0；V1 基线数据不计为 M6A fixture。Redis 故障测试结束时仅有 run-id
sentinel，DBSIZE=1，未产生业务 key。

## 4. RocketMQ/Redis/ES 上下文断言

实际执行的 `MarketingAdminIsolationIT` 断言不存在：
`DefaultMQProducer`、`DefaultLitePullConsumer`、`DefaultMQPushConsumer`、
`DefaultRocketMQListenerContainer`、真实 `RocketMQTemplate`、`RedisConnectionFactory` 和
`RestHighLevelClient`。日志没有 producer/consumer 启动、client register 或 NameServer 连接。
完整上下文若未来需要 `rocketMQTemplate`，仍只允许 M6A 测试类注册同名 Mockito bean；本轮未
修改生产配置或全局 test profile。

## 5. strace connect 汇总

正式命令使用 Java 8、Maven offline 和 `strace -f -tt -yy -e trace=connect`。R0 日志共 25
次 connect：19 次 TCP 全部为 `::ffff:127.0.0.1:24318`，6 次为本机 nscd Unix socket。
最终 M6A 回归为 96 次 TCP，全部为 `127.0.0.1:24318`，另有 6 次 nscd Unix socket；Redis
故障为 1 次 `127.0.0.1:27391` TCP 和 6 次 nscd；MySQL stop 故障为 31 次
`127.0.0.1:24318` TCP、10 次本机 Docker Unix socket 和 6 次 nscd。所有这些轮次均没有
`9876/10911/9200/3306/6379` 或其他未知 AF_INET/AF_INET6 目标。

## 6. 保留的负面证据

`m6a_20260822b` 的 marker 顺序错误导致的 Flyway error 保留在
`/tmp/m6a-r0-20260822b.txNjKg/`，未改判为 PASS。后续普通代码缺陷也保留了首轮证据：契约
断言失败、grant 事务配置缺失、Redis 测试 compile 缺少 `throws Exception`；首次 Redis
strace 受 ptrace 权限拒绝而未启动 JVM。这些问题均在新 run-id 下修复并验证，不是环境隔离
成功证据的替代品。

## 7. 提交链与边界

M6A 本地提交链为：

1. `1351a9b test(marketing): isolate M6A integration context`
2. `9e98ce3 docs(marketing): record M6A isolation recovery evidence`
3. `a4f9326 feat(marketing): add merchant-scoped tags and campaigns`
4. `5d5b22c feat(coupon): add idempotent campaign grant ledger`
5. `daaa2f6 test(marketing): verify isolated M6A invariants`
6. `c387116 feat(frontend): expose targeted campaign grants`
7. `b9f6a0c test(marketing): verify M6A failure paths`
8. `1e5debe docs(marketing): record M6A evidence`

`d026db3`、`446455d`、`ea9245e` 和历史负面证据均保留。未实施 M6B/M6C、批量发券、MQ、
Outbox、通知、规则 DSL 或技术栈升级；未 push。
