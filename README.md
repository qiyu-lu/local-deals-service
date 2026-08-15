# 优惠券秒杀系统

> GitHub repo: `local-deals-service` | 基于黑马点评教程改造，涵盖秒杀可靠性增强、Elasticsearch 搜索、RocketMQ 事务消息、Canal 数据同步、WebSocket 实时推送

基于黑马点评教程原型改造的高并发优惠券秒杀后端系统。在教程原型基础上分两阶段演进：第一阶段对 Redis Stream 异步下单链路做可靠性增强，补齐 DB 层一人一单兜底、pending 消息重试上限和 dead-letter Stream，并搭建 JMeter 自动化压测与故障注入验证体系；第二阶段引入 ES + RocketMQ + Canal + WebSocket，升级搜索能力、消息中间件和实时推送。

## 相比教程原型的核心优势

本项目不是把秒杀链路包装成“吞吐性能大幅提升”，而是将原始 Redis Stream 异步下单链路改造成更接近生产场景的可靠消费链路。当前证据显示：正常压测下业务正确性不退化，异步落库追平耗时保持同量级；异常消息场景下，原始版本会留下 pending 残留，当前版本可以有限重试后进入 dead-letter Stream 并清空 pending。

| 对比项 | 教程原型 / baseline | 当前版本 `reliable-stream-v1` | 优势结论 |
| --- | --- | --- | --- |
| 正常秒杀压测 | 1000/5000 并发请求下订单正确、pending 清空 | 同样订单正确、pending 清空，`drain_ms` 与 baseline 同量级 | 增加可靠性机制后，正常链路正确性不退化 |
| 异常 Stream 消息 | 缺少 `orderId` 的消息残留 pending，DLQ 为空 | 重试 3 次后写入 `stream.orders.dlq`，pending 清空 | 补齐异常消费闭环，问题可追踪、可恢复 |
| 一人一单兜底 | 主要依赖 Redis Lua 与业务层判断 | 增加 `tb_voucher_order(user_id, voucher_id)` 唯一索引 | DB 层提供最终一致性兜底 |
| 可观测性 | 缺少秒杀链路指标入口 | 暴露请求、消费、重试、死信、pending、DB 幂等等 Prometheus 指标 | 面向排障和运维，而不只是功能演示 |
| 验证方式 | 容易只看 HTTP Error% | JMeter 后自动校验 MySQL、Redis、Stream、DLQ | 结果可复现，可证明业务正确性 |

## 项目亮点

| 教程原型中的边界 | 当前改造 | 证据 |
| --- | --- | --- |
| 搜索只有 MySQL LIKE%，不支持分词和地理位置组合查询 | Elasticsearch 7.17.18 + IK 分词器；`GET /shop/search?keyword=火锅&x=120.15&y=30.33&radius=5000` 单次请求同时做 IK 分词、geo 过滤、相关性排序 | `ShopSearchBeforeIT`（基线）vs `ShopSearchAfterIT`（ES 验证）；`docs/improvement-comparison.md` |
| 秒杀异步消息用 Redis Stream，Lua 操作与 XADD 不是原子的，丢消息无法保证 | RocketMQ 事务消息：半消息 → executeLocalTransaction 运行 Lua → COMMIT/ROLLBACK，Lua 操作与消息发送原子绑定；磁盘持久化，Broker 重启不丢 | `SeckillWithRocketMQIT`（500 并发 / 100 库存 / 0 超卖验证） |
| MySQL 和 ES 之间无数据同步机制，双写侵入业务代码 | Canal 伪装 MySQL 从节点监听 binlog → RocketMQ `mysql-sync-topic` → `EsSyncConsumer` → ES；业务代码零感知 | `CanalSyncIT`（直接调用 `EsSyncConsumer.onMessage` 验证 INSERT/UPDATE/DELETE 三种路径） |
| 秒杀结果无实时通知，用户只能轮询 | WebSocket + Redis pub/sub：落库后毫秒级推送，多实例部署下 Redis 广播保证消息路由到持有连接的实例 | `SeckillWebSocketIT`（Awaitility 3s 内断言 WebSocket sendMessage 被调用） |
| 商铺、优惠券写接口匿名可调用，管理广播对普通用户可见 | 写接口改为“登录 + 临时管理员白名单”双重校验；用户回执与管理广播使用独立 WebSocket 端点和会话池；发送前复核 token，退出或过期立即失去推送权限 | `LoginInterceptorTest`、`AdminAccessInterceptorTest`、`WebSocketSessionIsolationTest` |
| 验证码可重复使用、可在有效期内无限猜测 | Redis Lua 原子完成 60 秒发送冷却、一次性消费和每个验证码最多 5 次失败尝试；日志默认不输出验证码 | `UserServiceImplTest`、`UserServiceIT` |
| 上传目录硬编码，删除接口可路径穿越或跨用户删除 | 上传根目录与 5 MB 上限配置化，校验扩展名/MIME/文件头；`tb_upload_file` 记录归属及 TEMP/DELETING/PUBLISHED 状态，只允许上传者删除未发布图片 | `UploadControllerTest`、`UploadFileServiceIT`、Flyway V3/V4 |
| 秒杀链路主要依赖 Redis Lua 和业务层判断，DB 层缺少最终兜底 | 增加 `tb_voucher_order(user_id, voucher_id)` 唯一索引，并在落库时处理 `DuplicateKeyException` | Flyway 迁移：`src/main/resources/db/migration/`；核心实现：`SeckillOrderConsumer#onMessage` |
| Redis Stream 消费失败后主要依赖 pending-list 重试，失败消息缺少明确归宿 | 增加 pending 重试计数、最大重试次数和 dead-letter Stream（第一阶段可靠性增强，已由 RocketMQ 内置 DLQ 取代） | `stream.orders.dlq`、`docs/reliability-results.md` |
| 压测容易只看 HTTP Error%，无法证明业务正确性 | 自动化脚本同时校验 MySQL 订单数、重复下单、DB/Redis 库存、Stream pending 和 dead-letter | `scripts/run-seckill-benchmark.sh`、`docs/benchmark-results.md` |
| 异步下单链路缺少运行时观测入口 | 接入 Micrometer / Prometheus，暴露请求、消费、重试、死信、pending、DB 幂等等指标 | `/actuator/prometheus` |


## 前端

> 前端部分非本项目重点，由 AI 辅助生成，主要作为后端功能的可视化验证入口。

### 用户端（手机 H5）

基于教程原型改进，运行在 `http://localhost:8088/`：

- 修复笔记详情页硬编码"叶小乙"重复评论，改为从 `GET /blog/of/shop` 动态拉取真实评论
- 修复搜索结果页二次搜索无结果（移除关键词搜索时的地理坐标过滤，测试数据集中在杭州，真实坐标会导致 0 结果）
- 修复商户搜索结果图片不显示（ES 文档 `ShopDoc` 无 `images` 字段，改为 ES 返回 ID 后再查 MySQL 获取完整字段）
- nginx 开启 gzip 压缩 + 7 天静态资源缓存（`vue.js` / `element.js` / `element.css` 合计 1.15 MB → gzip 后约 350 KB，消除每次跳页重复下载）

### 管理端（Vue 3 SPA，AI 辅助生成）

新增管理端前端，运行在 `http://localhost:8088/admin/`，基于 Vue 3 + Vite + Element Plus 构建，通过 nginx 反向代理与后端通信：

| 页面 | 功能 |
| --- | --- |
| 登录 | 手机号 + 验证码，token 存入 localStorage；本地调试可显式开启验证码日志 |
| 仪表盘 | 商铺总数、今日秒杀订单、ES 同步状态、WebSocket 在线用户数 |
| 实时订单 | WebSocket 长连接，秒杀落库后毫秒级推送订单卡片（成功/失败），支持手动断开重连 |
| 秒杀券管理 | 创建秒杀券（标题、时间、库存、商铺 ID），按商铺 ID 查询已有券列表及实时状态 |
| 商铺管理 | 按类型/关键词搜索商铺，查看详情，更新商铺信息（触发 Canal → ES 同步） |

**登录**

![登录管理端](figure/登录管理端.png)

**仪表盘**

![仪表盘](figure/仪表盘.png)

**实时秒杀订单流**（运行 `scripts/run-seckill-benchmark.sh --threads 100 --loops 1 --stock 100 --user-count 1000` 后）

![WebSocket 实时订单](figure/websocket.png)

![秒杀压测结果](figure/秒杀测试.png)

## 测试策略

### 测试理念

集成测试直连真实 MySQL 和 Redis，不使用 Mock，确保测试行为与生产路径完全一致。所有 Bug 修复均遵循 TDD 顺序：先写能复现问题的失败测试，确认失败后再修复，修复后测试变绿。测试本身即是对修复正确性的活文档。

### Bug → 测试 → 修复 对照表

| Bug | 测试 | 关键结论 |
| --- | --- | --- |
| `BlogServiceImpl.queryBlogUser` 在用户被删除时抛 NPE | `BlogServiceIT` · `queryBlogUser_deletedUser_doesNotThrowNPE` | 先写测试复现 NPE，加 null guard 后通过 |
| `CacheClient.queryWithLogicalExpire` 缓存缺失时抛 NPE | `CacheClientIT` · `queryWithLogicalExpire_returnsNull_whenCacheIsEmpty` | 防御性 null 检查，避免 JSON 反序列化崩溃 |
| `CacheClient` 锁 key 硬编码 `LOCK_SHOP_KEY`（通用方法用了专属常量） | `CacheClientIT` · `queryWithLogicalExpire_lockKey_usesKeyPrefix` | 任何非 Shop 实体使用逻辑过期时会争抢同一把锁，修复为 `"lock:" + keyPrefix + id` |
| `UserServiceImpl` 新用户 icon 为 null 时 Hutool `fieldValueEditor` 抛 NPE | `UserServiceIT` · `login_newUserWithNullIcon_doesNotThrowNPE` | `fieldValueEditor` 需要显式判 null |
| `RedisIdWorker.nextId` 高并发下是否产生重复 ID | `RedisIdWorkerIT` · `nextId_30kConcurrentCalls_allUnique` | 300 线程 × 100 次 = 30,000 个 ID 全部唯一 |

### 集成测试覆盖速览

| 测试类 | 层次 | 测试内容 |
| --- | --- | --- |
| `RedisIdWorkerIT` | 工具层 | 高并发下 ID 无重复 |
| `CacheClientIT` | 工具层 | 逻辑过期空缓存处理；锁 key 前缀正确性 |
| `UserServiceIT` | Service 层 | 新用户登录（icon=null）不崩溃，返回 token |
| `BlogServiceIT` | Service 层 | 查询已删除用户的博客不抛 NPE，gracefully 返回空字段 |
| `ShopServiceIT` | Service 层 | 缓存缺失→查 DB→写缓存；布隆过滤器拦截无效 ID；updateShop 清除缓存 key |
| `ShopSearchBeforeIT` | 搜索基线 | MySQL LIKE% 搜索结果数和耗时（before 对比数据） |
| `ShopSearchAfterIT` | ES 搜索 | IK 分词 + geo-distance 组合查询；结果与 before 对比 |
| `SeckillWithRocketMQIT` | MQ 秒杀 | 500 并发 / 100 库存：RocketMQ 事务消息无超卖，恰好 100 单 |
| `CanalSyncIT` | Canal 同步 | 直接调用 `EsSyncConsumer.onMessage(json)`；验证 INSERT/UPDATE/DELETE 三种操作同步到 ES |
| `SeckillWebSocketIT` | WebSocket | Awaitility 3s 内断言 mock session.sendMessage() 被调用，消息含 `"success":true` |

运行所有集成测试（需要 MySQL、Redis、Elasticsearch 在本地运行）：

```bash
set -a && source .env && set +a
~/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn -Dtest="*IT" test
```

## 对比验证摘要

2026-05-20 对比验证覆盖 baseline 与 `reliable-stream-v1` 的正常压测和异常消息注入。正常链路用于证明“可靠性增强后不破坏正确性”，故障注入用于证明“原始 pending 残留问题被闭环处理”。

| 验证场景 | baseline 结果 | `reliable-stream-v1` 结果 | 结论 |
| --- | --- | --- | --- |
| 1000 线程 / 1 次循环 | 订单 1000/1000，pending 0，DLQ 0，`drain_ms=73` | 订单 1000/1000，pending 0，DLQ 0，`drain_ms=72` | 正常链路正确性不退化 |
| 5000 线程 / 1 次循环 | 订单 1000/1000，pending 0，DLQ 0，`drain_ms=71` | 订单 1000/1000，pending 0，DLQ 0，`drain_ms=81` | 高并发尖峰下仍保持一致性 |
| 缺少 `orderId` 的异常 Stream 消息 | pending 1，DLQ 0 | pending 0，DLQ 1，`retries=3` | 当前版本具备异常消费闭环 |

完整结果见 [压测结果记录](docs/benchmark-results.md)、[故障注入结果](docs/reliability-results.md) 和 `docs/JmeterTestSummary/`。

## Quick Start

**前置要求**：JDK 8、Maven、MySQL 8、Redis 6+（本地安装或 Docker 均可）

```bash
# 1. 准备环境变量（填写 MySQL / Redis 密码）
cp .env.example .env
# 编辑 .env，填入真实密码
# 将 LOCAL_DEALS_ADMIN_USER_IDS 设置为允许进入管理端的用户 ID；为空时管理写操作和管理 WebSocket 默认全部拒绝
# 项目尚未接入短信供应商。本地调试登录时可临时设置 LOCAL_DEALS_LOG_VERIFICATION_CODE=true，禁止用于共享或生产环境
set -a && source .env && set +a
```

当前管理员 ID 白名单是数据库 RBAC 上线前的过渡边界：它同时保护商铺/优惠券管理写接口和管理端 WebSocket，并在配置为空时默认拒绝。图片上传记录由 Flyway 创建的 `tb_upload_file` 管理；笔记发布会在同一数据库事务中把图片从 `TEMP` 转为 `PUBLISHED`，已发布图片不能再通过临时删除接口移除。

**启动 MySQL 和 Redis**（二选一）：

```bash
# 方式 A：Docker Compose（推荐，开箱即用）
docker compose up -d

# 方式 B：使用已有的本地 MySQL / Redis 服务
# 确保 MySQL 已创建数据库，Redis 已启动，并在 .env 中配置好连接信息
# application.yaml 中的 spring.datasource / spring.redis 会从 .env 读取
```

```bash
# 2. 启动服务（Flyway 自动初始化表结构，无需手动建表）
mvn spring-boot:run
# 服务启动后默认监听 http://localhost:8083

# 3. （可选）运行秒杀压测
# 额外需要：jmeter、mysql client、redis-cli 在 PATH 中
scripts/run-seckill-benchmark.sh \
  --threads 100 \
  --loops 1 \
  --stock 100 \
  --user-count 1000
# MySQL/Redis 连接参数从 .env 中的 LOCAL_DEALS_* 自动读取，无需额外指定容器名
# 压测结束后自动校验 MySQL 订单数、Redis 库存、Stream pending，并输出 P95/P99

# 4. （可选）故障注入验证——向 Redis Stream 注入缺少 orderId 的畸形消息
# 额外需要：redis-cli 在 PATH 中
scripts/run-seckill-reliability-check.sh --expect current
# 预期结果：消息经 3 次重试后写入死信 Stream，pending 清空
```

## 技术栈

**后端**
- Java 8 / Spring Boot 2.3.12 / MyBatis-Plus
- MySQL 8 / Flyway
- Redis 6 / Redis Stream / Redis GEO / Bitmap / Redis pub/sub
- Redisson（分布式锁）
- Elasticsearch 7.17.18 + IK 分词器（`ik_max_word` 索引 / `ik_smart` 搜索）+ geo_point
- RocketMQ 4.x client（事务消息、`@RocketMQTransactionListener`）
- Canal Server 1.1.7（binlog 解析，FlatMessage → RocketMQ）
- WebSocket（`TextWebSocketHandler`，Redis pub/sub 多实例路由）
- Actuator / Micrometer / Prometheus
- JMeter（自动化压测与故障注入）

**前端**
- 用户端：Vue 2 CDN + Element UI（H5 多页应用，无构建步骤）
- 管理端：Vue 3 + Vite + Element Plus（SPA，`npm run build` 输出到 `dist/`）
- nginx（反向代理 + 静态资源托管 + gzip 压缩 + 7 天缓存）

## 当前重点

- 登录态：验证码登录后将用户信息写入 Redis Hash，拦截器从 `authorization` 请求头恢复 `UserHolder`。
- 商铺缓存：商铺详情查询结合 Redis 缓存、空值缓存和布隆过滤器，降低无效请求对数据库的压力。
- 优惠券秒杀：Lua 脚本在 Redis 中原子完成库存判断、一人一单判断和订单消息入队，后台消费者批量消费 Redis Stream 后落库。
- 可靠性增强：Flyway 管理表结构迁移，`tb_voucher_order(user_id, voucher_id)` 唯一索引作为一人一单最终兜底；pending 消息有重试上限和死信 Stream。
- 可观测性：暴露秒杀请求、Stream 消费、pending、死信、落库幂等等 Prometheus 指标。
- 附近商铺：使用 Redis GEO 按距离检索商铺，并将距离写回响应对象。

## 文档

- [改进前后对比](docs/improvement-comparison.md)
- [本地环境与常见问题](docs/environment-setup.md)
- [JMeter 使用说明](docs/jmeter-usage.md)
- [秒杀对比验证手册](docs/seckill-comparison-test-runbook.md)
- [压测结果记录](docs/benchmark-results.md)
- [故障注入结果](docs/reliability-results.md)

## 压测准备

压测不绕过正式登录逻辑，也不删除验证码校验。秒杀压测使用测试侧工具预生成测试用户和 Redis token，JMeter 从 CSV 中读取 token 后请求秒杀接口。

推荐使用脚本自动完成测试用户/token 准备、库存重置、JMeter 压测、MySQL/Redis 校验和报告输出：

```bash
set -a && source .env && set +a

scripts/run-seckill-benchmark.sh \
  --threads 100 \
  --loops 1 \
  --stock 100 \
  --user-count 1000
```

不传 `--voucher-id` 时，压测工具会自动创建或复用一张本地压测秒杀券。MySQL/Redis 连接参数从 `.env` 中的 `LOCAL_DEALS_*` 自动读取。

更多参数和清理规则见 [JMeter 使用说明](docs/jmeter-usage.md)。
