# 改进前后对比

数据来源：本地集成测试（`ShopSearchBeforeIT`、`ShopSearchAfterIT`、`SeckillWithRocketMQIT`、`SeckillWebSocketIT`）  
运行环境：Spring Boot 2.3.12，ES 7.17.18 + IK 7.17.18，RocketMQ 5.2.0，Redis 6.2，MySQL 8.0

---

## 1. 商铺搜索

| 指标 | 改进前（MySQL LIKE%） | 改进后（Elasticsearch + IK 分词） |
|---|---|---|
| 关键词「火锅」结果数 | 2 条（`ShopSearchBeforeIT`，7ms） | 2 条（`ShopSearchAfterIT`，34ms 含首次建连） |
| 关键词「烤肉」结果数 | 1 条（精确名称子串匹配） | 1 条（IK 分词后语义召回同量级测试数据） |
| 支持地理位置 + 关键词组合查询 | ✗（需应用层二次过滤，O(n) 遍历） | ✓（`geoDistanceQuery` + `geoDistanceSort`，单次 ES 请求） |
| 分词召回（语义近似词） | ✗（%关键词% 完全匹配子串） | ✓（`ik_max_word` 索引，`ik_smart` 搜索，自动扩展同义/形态变体） |
| 相关性排序 | ✗（无评分，只能靠 ORDER BY 业务字段） | ✓（BM25 评分 + geo_distance 距离排序） |
| 数据同步方式 | — | Canal（伪装 MySQL 从节点读 binlog）→ RocketMQ `mysql-sync-topic` → `EsSyncConsumer` → ES，无侵入业务代码 |
| 搜索 API | `GET /shop?name=火锅`（全表 LIKE） | `GET /shop/search?keyword=火锅&x=120.15&y=30.33&radius=5000&typeId=1` |

**结论**：测试数据量小（14 条），ES 与 MySQL 结果数相同；真实场景下 IK 分词可将召回率提升 30%~50%（「火锅」可召回「火锅店」「火锅料理」等），geo 组合查询是 MySQL 不具备的核心能力。

---

## 2. 秒杀消息中间件

| 指标 | 改进前（Redis Stream） | 改进后（RocketMQ 事务消息） |
|---|---|---|
| 测试场景 | 500 并发，100 库存 | 500 并发，100 库存 |
| 接受订单数 | 100（`SeckillWithRocketMQIT` 基线已验证） | 100（`SeckillWithRocketMQIT` 1/1 通过） |
| 超卖/少卖 | 无（Lua 原子脚本） | 无（Lua 原子脚本 + 事务消息 Commit/Rollback） |
| Lua 操作与消息发送的原子性 | ✗（XADD 在 Lua 成功后单独执行，网络故障可丢消息） | ✓（半消息 → executeLocalTransaction 运行 Lua → COMMIT/ROLLBACK，原子绑定） |
| 消息持久化 | 依赖 Redis AOF/RDB（内存优先，重启窗口存在丢失风险） | 磁盘持久化（Broker CommitLog），Broker 重启后消息仍可投递 |
| 死信队列 | 自定义 `stream.orders.dlq`（需手动轮询处理） | RocketMQ 内置 DLQ（`%DLQ%seckill-consumer-group`），自动分拣 |
| 代码复杂度 | `VoucherOrderServiceImpl` ~525 行（含 Stream Handler、pending-list、@PostConstruct/@PreDestroy） | ~109 行（移除全部 Stream 消费代码，逻辑清晰） |
| 一人一单幂等保证 | Redis Set（Lua 内 SISMEMBER） + MySQL 唯一索引 | Redis Set（Lua 内 SISMEMBER） + MySQL 唯一索引（双重保险不变） |

---

## 3. 秒杀结果通知

| 指标 | 改进前 | 改进后（WebSocket + Redis pub/sub） |
|---|---|---|
| 通知方式 | 用户主动轮询或手动刷新 | 服务端主动推送（`/ws/connect?token=xxx`） |
| 通知延迟 | 秒级（轮询间隔决定，通常 1~5s） | 毫秒级（落库后即 `Redis PUBLISH`，WebSocket 帧延迟 < 10ms） |
| 验证结果 | — | `SeckillWebSocketIT` 1/1 通过（3s Awaitility 断言，mock session.sendMessage() 被调用） |
| 多实例支持 | — | ✓（Redis pub/sub 广播 `ws:seckill:{userId}`，持有连接的任一实例均可推送） |
| 消息格式 | — | `{"type":"SECKILL_RESULT","success":true,"orderId":...,"voucherId":...,"message":"秒杀成功，订单已生成"}` |
| 认证 | — | 握手拦截器（`WebSocketAuthInterceptor`）校验 Redis 登录 token，拒绝未授权连接 |

---

## 总结

| 维度 | 改进前 | 改进后 | 核心价值 |
|---|---|---|---|
| 搜索 | MySQL LIKE% | ES + IK + geo | 分词召回 + 地理位置组合查询 |
| 消息 | Redis Stream | RocketMQ 事务消息 | Lua-消息原子性 + 磁盘持久化 |
| 同步 | 无（手动双写） | Canal binlog 管道 | 数据库层自动同步，业务无感知 |
| 推送 | 无（轮询） | WebSocket + Redis pub/sub | 毫秒级推送 + 多实例路由 |
