# 设计文档：ES + RocketMQ + WebSocket 升级方案

**日期**：2026-06-30  
**项目**：优惠券秒杀系统（local-deals-service）  
**目标**：在现有单体项目基础上引入 Elasticsearch、RocketMQ、Canal、WebSocket，丰富技术栈广度，形成可量化的 before/after 对比证据，用于秋招简历与面试。

**版本约束**：Spring Boot 2.3.12 内置 Spring Data Elasticsearch 4.0.x，只兼容 ES 7.x（不兼容 8.x）。所有 ES 相关版本统一使用 **7.17.x**，IK 分析器版本与之匹配。

---

## 背景与动机

当前项目基于黑马点评改造，已完成秒杀可靠性增强（Redis Stream + dead-letter + Prometheus）。但存在三个明显短板：

| 短板 | 当前状态 | 目标状态 |
|---|---|---|
| 无搜索能力 | 只有 MySQL `LIKE %关键词%`，不支持分词和相关性排序 | ES + IK 分词，支持关键词 + 地理位置组合查询 |
| 消息中间件弱 | Redis Stream 无持久化保证，无事务消息 | RocketMQ 磁盘持久化 + 事务消息 |
| 无实时通知 | 用户秒杀后需轮询或手动刷新 | WebSocket 落库后毫秒级推送 |

---

## 整体架构

```
用户浏览器
  ├─ HTTP 请求 ──→ Spring Boot (localhost:8083)
  └─ WebSocket ──→ /ws/connect?token=xxx

Spring Boot 内部：
  ├─ ShopController / BlogController  →  Elasticsearch (localhost:9200)
  ├─ VoucherOrderController           →  RocketMQ (localhost:9876/10911)
  ├─ SeckillOrderConsumer             →  MySQL + Redis pub/sub
  ├─ EsSyncConsumer                   →  Elasticsearch
  └─ SeckillWebSocketHandler          →  WebSocket session 管理

外部基础设施（Docker）：
  MySQL (已有)  →  Canal Server (localhost:11111)  →  RocketMQ
  RocketMQ NameServer: localhost:9876
  RocketMQ Broker:     localhost:10911
  Elasticsearch:       localhost:9200
```

---

## 模块一：Elasticsearch

### 索引设计

**`shop_index`**

```json
{
  "mappings": {
    "properties": {
      "id":       { "type": "keyword" },
      "name":     { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "address":  { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "typeId":   { "type": "keyword" },
      "avgPrice": { "type": "integer" },
      "score":    { "type": "double" },
      "sold":     { "type": "integer" },
      "location": { "type": "geo_point" }
    }
  }
}
```

**`blog_index`**

```json
{
  "mappings": {
    "properties": {
      "id":      { "type": "keyword" },
      "title":   { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "content": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "userId":  { "type": "keyword" },
      "liked":   { "type": "integer" }
    }
  }
}
```

**分词器选择原则**：
- 索引时 `ik_max_word`（切得更细，最大召回）
- 搜索时 `ik_smart`（智能合并，精准匹配）

### 数据同步

不采用双写，使用 **Canal → RocketMQ → EsSyncConsumer** 管道：

```
MySQL (tb_shop / tb_blog 变更)
  → Canal Server 捕获 binlog (ROW 格式，已确认开启)
  → 发送 FlatMessage 到 RocketMQ `mysql-sync-topic`
  → EsSyncConsumer 按 tableName 路由，更新对应 ES 索引
```

优势：服务层代码无需写任何同步逻辑；即使直接 SQL 修改数据库，ES 也能自动追上。

### 新增 API

```
GET /shop/search?keyword=火锅&x=120.15&y=30.33&radius=5000&typeId=1&current=1
GET /blog/search?keyword=美食&current=1
```

### 启动初始化

应用启动时 `ShopIndexInitializer`（`@PostConstruct`）将 `tb_shop` 现有数据批量导入 ES，之后由 Canal 管道保持增量同步。

### 新增文件

```
com.localdeals.dto
  ├── ShopDoc.java
  └── BlogDoc.java
com.localdeals.config
  └── ElasticsearchConfig.java
com.localdeals.init
  └── ShopIndexInitializer.java
com.localdeals.service.impl
  └── ShopServiceImpl.java      # 新增 search() 方法
  └── BlogServiceImpl.java      # 新增 search() 方法
```

---

## 模块二：RocketMQ

### Topic 规划

| Topic | 消息类型 | 生产者 | 消费者组 | 职责 |
|---|---|---|---|---|
| `seckill-order-topic` | 事务消息 | HTTP 线程 | `seckill-consumer-group` | 秒杀下单异步落库 |
| `mysql-sync-topic` | 普通消息 | Canal Server | `es-sync-consumer-group` | MySQL → ES 数据同步 |

### 秒杀流程（替换 Redis Stream）

```
① HTTP 线程发送半消息到 seckill-order-topic（Consumer 不可见）
② 执行本地事务：Lua 脚本原子检查库存 + 一人一单 + 扣减库存
③ Lua 成功 → Commit 半消息（Consumer 可见）
   Lua 失败 → Rollback（消息取消）
④ SeckillOrderConsumer 消费消息 → 写入 tb_voucher_order
   唯一索引 (user_id, voucher_id) 保证幂等
⑤ 落库成功 → 触发 WebSocket 推送
```

**事务消息价值**：保证"Lua 操作成功"与"消息发送"的原子性，消除当前 Redis Stream 方案中 XADD 可能失败的隐患。

### Canal → RocketMQ 数据同步

Canal Server 配置：
- 监听库：`local_deals`
- 监听表：`tb_shop`、`tb_blog`
- 输出目标：RocketMQ `mysql-sync-topic`
- 消息格式：FlatMessage JSON

```json
{
  "table": "tb_shop",
  "type": "UPDATE",
  "data": [{ "id": "1", "name": "新白鹿", "x": "120.15", "y": "30.33" }]
}
```

`EsSyncConsumer` 按 `table` 字段路由，处理 INSERT / UPDATE / DELETE 三种事件。

### 新增文件

```
com.localdeals.mq
  ├── SeckillOrderProducer.java    # 发送秒杀事务消息 + 本地事务监听器
  ├── SeckillOrderConsumer.java    # 消费秒杀消息，写DB + 触发WebSocket
  └── EsSyncConsumer.java          # 消费Canal消息，路由更新ES
com.localdeals.config
  └── RocketMQConfig.java
```

`VoucherOrderServiceImpl` 移除 Redis Stream 相关代码，调用 `SeckillOrderProducer`。

### 基础设施（新增到 docker-compose.yml）

```yaml
rocketmq-namesrv:
  image: apache/rocketmq:4.9.4
  ports: ["9876:9876"]

rocketmq-broker:
  image: apache/rocketmq:4.9.4
  ports: ["10911:10911"]
  depends_on: [rocketmq-namesrv]

canal-server:
  image: canal/canal-server:v1.1.7
  ports: ["11111:11111"]
  environment:
    - canal.instance.master.address=hmdp-mysql:3306
    - canal.mq.servers=rocketmq-namesrv:9876
    - canal.mq.topic=mysql-sync-topic
```

---

## 模块三：WebSocket

### 连接端点

```
ws://localhost:8083/ws/connect?token=<用户登录token>
```

握手拦截器验证 token，解析 userId，存入 session 属性。

### 推送链路

```
SeckillOrderConsumer 落库成功
  → Redis PUBLISH "ws:seckill:{userId}" "{result JSON}"
  → WebSocket 服务订阅该 channel
  → 找到 userId 对应的 WebSocketSession
  → session.sendMessage(result)
```

**为什么用 Redis pub/sub 而非直接访问 session**：支持多实例部署——Consumer 和 WebSocket 连接可能在不同实例上，Redis 广播确保消息能路由到持有连接的实例。

### 推送消息格式

```json
{
  "type": "SECKILL_RESULT",
  "success": true,
  "orderId": 1234567890,
  "voucherId": 10,
  "message": "秒杀成功，订单已生成"
}
```

失败情况（库存不足/重复购买）：
```json
{
  "type": "SECKILL_RESULT",
  "success": false,
  "message": "库存不足"
}
```

### 新增文件

```
com.localdeals.websocket
  ├── SeckillWebSocketHandler.java    # TextWebSocketHandler，管理 session
  ├── WebSocketAuthInterceptor.java   # HandshakeInterceptor，token 认证
  └── WebSocketNotifier.java          # Redis PUBLISH + session 发送封装
com.localdeals.config
  └── WebSocketConfig.java            # 注册 /ws/connect 端点
```

---

## 测试策略（Before/After 对比）

### 改进前测试（先写，记录基线）

| 测试类 | 验证内容 | 记录指标 |
|---|---|---|
| `ShopSearchBeforeIT` | MySQL LIKE% 搜索"火锅"的结果数和耗时 | 结果数、查询耗时 |
| `SeckillStreamBeforeIT` | Redis Stream 秒杀正确性（已有基线） | 订单数、pending、drain_ms |

### 改进后测试

| 测试类 | 验证内容 |
|---|---|
| `ShopSearchAfterIT` | ES IK 分词搜索结果质量（同关键词，结果数对比） |
| `CanalSyncIT` | MySQL 变更后等待管道，断言 ES 已更新 |
| `SeckillWithRocketMQIT` | RocketMQ 版本秒杀正确性（30,000 ID 唯一性 + 无重复订单）|
| `RocketMQRestartIT` | 发消息 → 停 Broker → 重启 → 消息不丢失 |
| `SeckillWebSocketIT` | 秒杀后 2 秒内 WebSocket 收到推送 |

### 对比文档

`docs/improvement-comparison.md`：记录每项改进的 before/after 指标，格式参考 `docs/benchmark-results.md`。

---

## 实施阶段规划

| 阶段 | 时间 | 内容 |
|---|---|---|
| Phase 1 | 7 月第 1-2 周 | 基础设施搭建（ES + RocketMQ + Canal Docker 环境）；先写 Before 测试 |
| Phase 2 | 7 月第 3-4 周 | ES 索引设计 + 搜索 API + ShopIndexInitializer |
| Phase 3 | 8 月第 1-2 周 | RocketMQ 秒杀事务消息 + Canal EsSyncConsumer |
| Phase 4 | 8 月第 3-4 周 | WebSocket 通知 + 全链路联调 + After 测试 + 对比文档 |
| Phase 5 | 9 月前两周 | 代码整理 + 面试话术准备 + 算法冲刺 |

**每日节奏**：算法 3h + 项目理解/学习 1.5h + 复习总结 0.5h

---

## 面试叙述要点

实现后能清晰回答的问题：

- "为什么用 ES 而不是 MySQL LIKE%？" → 分词召回、geo 组合查询、相关性排序
- "IK 分词器 ik_smart 和 ik_max_word 区别？" → 索引粗细粒度，召回与精准的权衡
- "MySQL 和 ES 怎么保持同步？如果同步延迟怎么办？" → Canal binlog，最终一致性，补偿查询
- "为什么用 RocketMQ 事务消息？" → Lua 操作和消息发送的原子性
- "多实例下 WebSocket 怎么处理？" → Redis pub/sub 广播
- "Canal 是什么原理？" → 伪装 MySQL 从节点，监听 binlog ROW 事件
