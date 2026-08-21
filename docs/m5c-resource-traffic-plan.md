# M5C 资源级流控与稳定降级实施计划

> 文档状态：任务级实施契约，尚未实施
>
> 规划日期：2026-08-21（Asia/Shanghai）
>
> 仓库：`/home/sd101t/IdeaProjects/hm-dianping`
>
> 预期分支：`codex/platform-hardening`
>
> 规划起点：`4d4c7d5 docs(cache): record M5B evidence and prioritize M5C`
>
> 前置结果：`docs/m5a-observability-results.md`、`docs/m5b-bounded-cache-results.md`

本文是 `docs/modernization-roadmap.md` 中 M5C 的任务级执行手册。下一实施会话只完成资源级
流控和稳定错误语义，不开始 M5D 的完整故障收口，不开始 M6，也不借机升级技术栈。

M5A 中“Broker 不可用时的新秒杀准入”在本计划形成时仍为 `BLOCKED`。M5C 可以在严格隔离
门禁通过后重新取得该项证据，但在真实结果产生前不得把状态改成通过；若隔离门禁不成立，保留
`BLOCKED` 并完成其他互不依赖的 M5C 项。

## 1. 结论先行

M5C 只回答五个问题：

1. 秒杀请求能否在生成订单号、发送 RocketMQ 半消息和执行资格预占之前，按
   `activity + user + client IP` 做跨实例共享的静态准入控制；
2. 验证码、后台登录、秒杀、搜索和受保护读路径能否稳定区分 HTTP 429、HTTP 503 与业务拒绝；
3. Redis 故障与入口过载时，秒杀能否 fail closed，且不产生库存、reservation、PROCESSING、
   MQ 或 MySQL 订单副作用；
4. 搜索和 MySQL 读回退能否以少量 JVM 并发许可限制放大，同时不抢占已接受订单消费、对账、
   状态查询和 Outbox 的执行路径；
5. 两个应用实例下，秒杀限额是否仍是共享上界，缓存回源是否诚实保持“每实例至多一次”，
   而不是伪报成集群级 singleflight。

第一版采用配置文件中的静态规则、一个 Redis Lua 固定窗门禁和两个 JVM `Semaphore`。不建设
规则表、管理 UI、推送控制面、通用 AOP 限流框架或新的线程池。

## 2. 证据起点与源码事实

实施前必须重新核对；以下事实来自规划起点 `4d4c7d5`，不能用旧对话代替现场检查。

### 2.1 已闭合事实

- M5B 已把 Spring Data Redis command timeout 和 Lettuce pool max-wait 固定为 `500ms`；
- 商铺详情和类型字典在 miss、坏值及 Redis 异常时安全回 MySQL，使用单 JVM、per-key
  singleflight；Redis 写回和 commit 后失效均为 best-effort；
- M5B Redis pause/stop 请求 20/20 正确，最大约 `504ms`，恢复转 hit 为 `51ms`；
- M5A 已证明 consumer pause 后既有 PROCESSING 能在恢复后收敛，重复订单和负库存为 0；
- 后台登录已经使用 Redis 原子计数，按 username+IP 和 IP 两个维度返回 429，并在 Redis
  门禁不可用时返回 503；
- 验证码发送已有 per-phone 60 秒 Lua 冷却，验证码消费已有一次性与最多 5 次失败限制；
- 热榜 Redis 状态不安全时已有索引化 MySQL fallback，搜索没有自动切换到 MySQL `%LIKE%`。

### 2.2 当前缺口

- `UserServiceImpl` 的验证码冷却和 Redis 异常仍返回 `Result.fail` 或落入通用异常，HTTP
  语义不稳定；
- `WebExceptionAdvice` 的兜底 `RuntimeException` 当前返回 HTTP 200；M5A 的 ES 故障因此
  出现 60/60 HTTP 200，实际 body 是失败；
- 秒杀入口先调用 `RedisIdWorker`，随后发送 RocketMQ 事务消息，当前没有独立的
  activity/user/IP 流控；Broker 或 Redis 系统错误最终仍可能表现为 HTTP 200 的失败 body；
- Elasticsearch connect/socket timeout 当前分别为 `5000ms/10000ms`，没有入口并发舱；
- M5B singleflight follower 会无界等待 leader；leader 卡在 MySQL 时，同 key follower
  不会继续放大 DB，但会共享等待；
- 不同 key 的 DB fallback、热榜读和搜索没有本地并发上限；两个 JVM 对同一冷 key 仍可能
  各执行一次 DB load；
- Redisson 是独立客户端，Hikari/Connector/J 的首个 DB I/O 等待也没有在 M5B 中收口。

### 2.3 对缺口的最小处理

- 验证码和后台登录只补稳定状态与回归测试，不增加验证码 IP 规则，不改变既有阈值；
- 秒杀新增一个前置 Redis 固定窗 Lua，不改 `seckill_check.lua` 的库存、重复购买和 PROCESSING
  状态机；
- 搜索与 DB 读回退只增加本地并发舱；不把所有 Controller 放进统一拦截器；
- 对 singleflight 只增加 follower 等待上界；leader 继续在原请求线程执行，不取消 JDBC，
  不新建 timeout executor；
- Elasticsearch 只把现有客户端的 connect/socket timeout 配置化并收紧；
- 不调整 Hikari、MySQL 驱动全局 timeout 或 Redisson retry/timeout。首个已获许可的 DB leader
  仍可能受底层 I/O timeout 约束，此事实必须在结果中单列，不得写成“所有 MySQL 故障请求
  都已小于 2 秒”。

## 3. 范围与非目标

### 3.1 本阶段包含

- 可选 `code` 字段的稳定错误响应，保持既有成功 payload 兼容；
- 429、503、500 与秒杀业务拒绝的明确映射；
- 复用现有可信代理边界解析真实 client IP；
- 秒杀 activity/user/IP 的 Redis Lua 固定窗门禁；
- DB 读回退与搜索两个 JVM 本地并发舱；
- M5B singleflight follower 的有限等待；
- Elasticsearch 有界 connect/socket timeout；
- 只服务 M5C 决策的低基数指标；
- 用户前端对非 2xx body 的正确提示；
- 单元、MVC、真实 Redis、真实 MySQL/Redis/ES/RocketMQ 和双实例隔离证据；
- M5C 结果文档与小型 CSV。

### 3.2 明确不做

- 不建设 `traffic_policy` 表、管理端、热更新、配置中心或动态规则发布；
- 不引入 Sentinel、Resilience4j、Bucket4j、网关、服务网格或第二套指标系统；
- 不实现滑动窗口、令牌桶、设备指纹、验证码、人机识别或黑名单平台；
- 不给普通接口增加全局 IP QPS 拦截器；
- 不新增请求线程池，不用 `Future.cancel` 伪装已取消 JDBC；
- 不新增 Caffeine、多级缓存、Bloom filter、逻辑过期或 Redis/Redisson 缓存锁；
- 不修改 M5B key、payload、TTL、坏值规则、after-commit 失效和 leader 执行方式；
- 不承诺跨实例只回源一次，不为此增加 Redis 分布式锁；
- 不调整 Hikari pool/connection timeout、MySQL 全局 socket timeout 或 Redisson timeout；
- 不改变秒杀库存、reservation、状态、补偿、consumer 或 reconciliation 正确性协议；
- 不把搜索降级为无界 MySQL `%LIKE%`；
- 不实现 M5D 的完整告警/故障矩阵，不开始 M6/M7/M8；
- 不升级 Java、Spring Boot、Redis、RocketMQ、Elasticsearch 或依赖版本。

## 4. 固定业务与 HTTP 契约

### 4.1 优先级

从低到高固定为：搜索和热榜推荐读 < 商铺缓存冷回源 < 新秒杀准入 < 已接受订单状态查询、
consumer、reconciliation 和 Outbox。

M5C 的所有 guard 只能由 HTTP 新流量或派生读路径显式调用。以下路径不得调用 guard：

- `SeckillOrderConsumer.onMessage`；
- `SeckillOrderReconciler`；
- `BlogLikeOutboxWorker` 与 batch/cleanup；
- `/voucher-order/status/{orderId}`；
- 已提交事务的 after-commit 缓存失效和派生发布。

### 4.2 响应矩阵

`Result` 增加可空 `code`；Jackson 已忽略 null，因此原成功响应和未迁移的业务失败不新增字段。
新增 `Result.fail(code, message)`，`ApiStatusException` 增加兼容旧构造器的可空 code。

| 入口/场景 | HTTP | `success` | 稳定 code | 副作用 |
| --- | ---: | --- | --- | --- |
| 验证码 per-phone 冷却命中 | 429 | false | `OTP_RATE_LIMITED` | 不覆盖现有验证码 |
| 验证码/用户登录 Redis 门禁不可用 | 503 | false | `AUTH_STATE_UNAVAILABLE` | fail closed |
| 后台 username/IP 或 IP 门禁命中 | 429 | false | `ADMIN_LOGIN_RATE_LIMITED` | 不查/不创建会话 |
| 后台 Redis 门禁不可用 | 503 | false | `ADMIN_LOGIN_UNAVAILABLE` | fail closed |
| 秒杀 activity/user/IP 任一超限 | 429 | false | `SECKILL_RATE_LIMITED` | 不生成订单号、不发 MQ、不预占 |
| 秒杀库存不足 | 200 | false | `SECKILL_OUT_OF_STOCK` | 保持现有业务拒绝 |
| 秒杀重复购买 | 200 | false | `SECKILL_DUPLICATE` | 保持现有业务拒绝 |
| 秒杀未开始 | 200 | false | `SECKILL_NOT_STARTED` | 保持现有业务拒绝 |
| 秒杀结束/暂停 | 200 | false | `SECKILL_ENDED` | 保持现有业务拒绝 |
| 秒杀 metadata 不可确认 | 503 | false | `SECKILL_STATE_UNAVAILABLE` | fail closed |
| 秒杀 Redis/ID/MQ 不可确认且 exact reservation 不存在 | 503 | false | `SECKILL_SUBMIT_UNAVAILABLE` | 不声称接受 |
| producer 报错但 exact reservation 为 PROCESSING/SUCCESS | 200 | true | null | 返回字符串 orderId，可查状态 |
| DB 读并发舱无许可 | 429 | false | `READ_OVERLOADED` | 不再发起 DB 查询 |
| 搜索并发舱无许可 | 429 | false | `SEARCH_OVERLOADED` | 不调用 ES/DB fallback |
| 选定读路径 MySQL 失败 | 503 | false | `DATABASE_UNAVAILABLE` | 不写空缓存 |
| ES timeout/断连/查询失败 | 503 | false | `SEARCH_UNAVAILABLE` | 禁止 MySQL `%LIKE%` fallback |
| 未分类服务端异常 | 500 | false | `INTERNAL_ERROR` | 日志保留异常，响应不泄露细节 |

业务拒绝继续使用 HTTP 200 是本阶段的兼容选择；客户端通过稳定 `code` 区分库存、重复、时间窗
等业务结果。429/503/500 必须使用真实 HTTP 状态，不能只在 body 写数字。

### 4.3 第一版静态配置

新增一个小型、强类型 `local-deals.traffic` 配置。以下是本地默认起点，不是生产容量结论：

```yaml
local-deals:
  client-ip:
    trusted-proxies: "${LOCAL_DEALS_TRUSTED_PROXIES:${LOCAL_DEALS_ADMIN_TRUSTED_PROXIES:}}"
  traffic:
    seckill:
      enabled: "${LOCAL_DEALS_SECKILL_RATE_LIMIT_ENABLED:true}"
      window: "${LOCAL_DEALS_SECKILL_RATE_WINDOW:1s}"
      activity-limit: "${LOCAL_DEALS_SECKILL_ACTIVITY_LIMIT:300}"
      user-limit: "${LOCAL_DEALS_SECKILL_USER_LIMIT:2}"
      ip-limit: "${LOCAL_DEALS_SECKILL_IP_LIMIT:100}"
    read:
      db-max-concurrent: "${LOCAL_DEALS_DB_READ_MAX_CONCURRENT:4}"
      db-max-wait: "${LOCAL_DEALS_DB_READ_MAX_WAIT:20ms}"
      shared-load-wait: "${LOCAL_DEALS_SHARED_LOAD_WAIT:750ms}"
      search-max-concurrent: "${LOCAL_DEALS_SEARCH_MAX_CONCURRENT:4}"
      search-max-wait: "${LOCAL_DEALS_SEARCH_MAX_WAIT:20ms}"
    search:
      connect-timeout: "${LOCAL_DEALS_ES_CONNECT_TIMEOUT:500ms}"
      socket-timeout: "${LOCAL_DEALS_ES_SOCKET_TIMEOUT:1s}"
```

配置启动校验：window 为 1–60 秒；limit 为正且有保守上界；并发为 1–64；permit wait
不超过 100ms；shared wait 为 100ms–2s；ES connect/socket timeout 均为 100ms–2s。

上述 300/2/100 只用于给实现一个可重复默认值。M5A B4 是 210.040 req/s 的单轮 pilot，不能
据此声称生产容量。正式隔离测试必须通过可信代理头生成多个 client IP；若正常 B4 参考流量因
一秒边界出现误拒绝，只允许预先记录的一次 activity-limit 调整，并保留调整前结果。

## 5. 设计细节

### 5.1 稳定错误层

- `Result` 只增加可空 code，不改变字符串 orderId，不把 64 位 ID 转为 JavaScript Number；
- `ApiStatusException` 保留现有 `(HttpStatus, message)` 构造器，并增加带 code 构造器；
- `WebExceptionAdvice` 对 `ApiStatusException`、非法参数和兜底异常都返回 `ResponseEntity<Result>`；
- 仅在明确调用链边界把 Redis、ES、MySQL 异常转换为 503；禁止用异常 message 猜依赖类型；
- catch-all 改成 500/`INTERNAL_ERROR`，但不得把已接受秒杀的歧义结果直接转 500；必须先做
  exact reservation 恢复检查；
- 日志可以带 order/voucher 等排障标识，指标和响应 code 只能使用固定枚举。

### 5.2 client IP 信任边界

把现有 `AdminClientIpResolver` 提升为消费者与后台共用的 `TrustedClientIpResolver`：

- 解析算法和“仅直接 peer 位于显式 trusted proxy CIDR 时才信任代理头”的规则不变；
- `X-Real-IP` 优先，随后只取 `X-Forwarded-For` 第一项；非法值回到 direct peer；
- 新配置 `local-deals.client-ip.trusted-proxies` 兼容旧
  `LOCAL_DEALS_ADMIN_TRUSTED_PROXIES` 环境变量；
- 后台登录和秒杀 Controller 共用同一个 bean，禁止各写一份解析器；
- IP 只在 JVM 内传递给 limiter，并在 Redis key 中使用 SHA-256 十六进制摘要；日志和指标不记录
  原始 IP 或摘要。

### 5.3 秒杀 Redis 固定窗

新增 `SeckillTrafficGuard` 和独立 Lua。调用顺序必须是：

```text
HTTP 身份已恢复
  -> 解析 voucherId/userId/clientIp
  -> traffic Lua
  -> RedisIdWorker.nextId
  -> sendMessageInTransaction
  -> 原 seckill_check.lua 资格预占
```

Lua 使用 Redis `TIME` 计算 bucket，不使用应用时钟；同一 voucher 的三类 key 使用同一
`{voucherId}` hash tag：

```text
traffic:seckill:{voucherId}:activity:{bucket}
traffic:seckill:{voucherId}:user:{userId}:{bucket}
traffic:seckill:{voucherId}:ip:{sha256}:{bucket}
```

脚本先读取三个计数，按 activity、user、IP 顺序返回第一个超限维度；全部允许时才同时 `INCR`
并设置不超过 `2 * window + 1s` 的 TTL。被拒绝的请求不得消耗其他维度配额。固定窗边界理论上
允许相邻窗口短时 2 倍突发，这是第一版明确限制；没有证据前不升级为令牌桶或滑动窗。

返回码固定为：0 allowed、1 activity、2 user、3 IP。null、未知码和 Redis 异常全部 fail closed
为 503。`enabled=false` 只作为紧急回滚开关；它不会绕过原 `seckill_check.lua` 的资格状态机。

### 5.4 JVM 读并发舱与 singleflight 等待

新增小型 `LocalReadBulkhead`，只维护两枚公平性关闭的 `Semaphore`：

- `DB_READ`：M5B CacheClient 的实际 leader DB fallback，以及热榜读取中的 DB 查询/批量 hydrate；
- `SEARCH`：shop/blog ES search，及仍保留的 legacy `/shop/of/name` 查询。

permit 使用 `tryAcquire(maxWait)`；无许可立即映射 429，成功或异常均在 `finally` 精确释放；线程
中断必须恢复 interrupt flag 并返回 503。不得把 permit 放进异步任务，不得以 queue 替代拒绝。

M5B singleflight 保持“leader 在当前请求线程执行”。只对 follower 使用
`FutureTask.get(sharedLoadWait)`：

- follower 超时返回 503，不启动第二次 DB 查询、不取消 leader、不删除 leader entry；
- leader 完成后仍按原逻辑写正值/空值或传播异常并清理 entry；
- follower timeout 增加一个有限指标结果；正常共享值、空值、异常和中断契约继续回归；
- 首个 leader 的底层 JDBC 等待不由该机制伪装为已取消。真实故障结果必须分别报告
  `leader`、`shared_timeout` 和 `bulkhead_rejected`。

两个实例下的明确上界是：同一 key 同时最多每 JVM 一个 leader；不同 key DB_READ 最多每实例
4 个。计划只验证该上界，不声明集群级一次回源。

### 5.5 搜索 timeout 与降级

- `ElasticsearchConfig` 从强类型配置读取 `500ms/1s`，不再硬编码 `5000/10000ms`；
- `/shop/search` 和 `/blog/search` 在 SEARCH permit 内执行；shop 的 ES ID 查询及有界 ID 回表
  属于同一个 permit；
- legacy `/shop/of/name` 也受 SEARCH permit 保护，但不会成为 ES 失败的自动 fallback；
- ES timeout、断连、坏响应返回 503；permit 耗尽返回 429；两者均不得返回空数组冒充“无结果”；
- 热榜 Redis miss 仍按 M4 协议回 DB；DB permit 耗尽返回 429，不返回空榜；
- 不缓存搜索结果，不增加“热门分类旧值”新缓存，因为当前没有其一致性和失效证据。

### 5.6 指标

在 `LocalDealsMetrics` 和 `docs/m5a-metric-catalog.md` 增加 M5C 有限扩展：

| 指标 | 类型 | 固定标签 | 语义 |
| --- | --- | --- | --- |
| `local_deals.traffic.decision` | Counter | `resource=seckill|db_read|search`; `result=allowed|rejected|unavailable`; `reason=none|activity|user|ip|concurrency|redis|interrupted` | 每次 guard 决策一个终态；只注册合法组合 |
| `local_deals.traffic.inflight` | Gauge | `resource=db_read|search` | 当前已持有 permit 数；不是线程池队列 |
| `local_deals.cache.singleflight` | Counter | 既有 resource；新增 `result=shared_timeout` | follower 等待超时一次；不与 leader/shared 相加推导 DB 次数 |
| `local_deals.seckill.requests` | Counter | 既有 result；新增 `rejected_rate` | 保持秒杀总入口终态可见；具体维度看 traffic decision |

HTTP 429/503/500 继续由 `http.server.requests` 的 status 统计。禁止增加 voucherId、userId、IP、
bucket、Redis key、异常类型/message 或 raw URI 标签。埋点失败不得改变业务结果。

### 5.7 RocketMQ 隔离与 timeout

为了安全重测唯一 BLOCKED 项，只把秒杀 topic/consumer group 从硬编码提升为带原默认值的配置，
并把 producer timeout/retry 使用 starter 已支持的属性明确配置：

- 默认 topic/group 保持 `seckill-order-topic` / `seckill-consumer-group`，生产兼容；
- M5C 隔离 run 必须使用带 run-id 的专用 topic、consumer group 和 producer group；
- `rocketmq.producer.send-message-timeout=1000`、`retry-times-when-send-failed=0`；若真实 fault
  仍超过 2 秒，只允许一次预先记录的 timeout 调整，不能循环调参；
- 应用启动必须使用真实 `ROCKETMQ_NAME_SERVER`；禁止使用不存在的
  `LOCAL_DEALS_ROCKETMQ_NAME_SERVER`；
- 在任何 Broker stop 前，用进程 TCP 连接、专用 Broker `topicList/consumerProgress` 和日志三方
  证明应用只连接专用端口，并断言没有到共享 `localhost:9876` 的连接。

该配置化只服务隔离和有界 producer 失败，不修改 consumer retry、DLQ、事务回查或对账算法。

### 5.8 前端最小改动

- `frontend/user/js/common.js` 对所有非 2xx 优先展示后端 `errorMsg`，保留 401 跳转，并安全处理
  没有 `error.response` 的网络错误；
- `login.html` 只有验证码发送成功后才启动 60 秒倒计时；429/503 不伪装成功；
- 秒杀 429/503 不启动 WebSocket/轮询；现有成功 orderId 仍按字符串处理；
- 搜索 429/503 保留当前已展示数据并结束 loading，不把失败当空结果；
- 不实现自动重试，避免 429/503 时客户端放大流量。

## 6. 预计文件边界

| 责任 | 预计文件 |
| --- | --- |
| 配置 | 新增 `TrafficControlProperties`、`ClientIpProperties`；调整 `application.yaml`、`ElasticsearchConfig`、`SeckillProperties` |
| API 语义 | `Result`、`ApiStatusException`、`WebExceptionAdvice`，可新增固定 error code 常量 |
| IP | 将 `AdminClientIpResolver`/测试提升为共享 resolver；调整 Admin/VoucherOrder Controller |
| 秒杀 | 新增 traffic guard 与 Lua；调整 `IVoucherOrderService`、`VoucherOrderServiceImpl`、producer/consumer topic 配置 |
| 读并发 | 新增 `LocalReadBulkhead`；调整 `CacheClient`、`SingleFlightLoader`、`ShopController` 和 Shop/Blog service |
| 指标 | `LocalDealsMetrics`、`docs/m5a-metric-catalog.md` |
| 前端 | `frontend/user/js/common.js`、`login.html`，必要时仅调整实际搜索/秒杀页面 |
| 测试 | properties、resolver、Lua、bulkhead、singleflight、service、MVC、真实 Redis/ES/MySQL/RMQ IT |
| 证据 | `scripts/m5c-isolated-stack.sh`、`scripts/run-m5c-traffic-check.sh`、results/summary、`.gitignore` |

实际实现若发现无需修改某文件，不为凑齐清单制造改动；若需要触碰此表外的业务模块，先证明与
上述五个问题直接相关，否则停止扩张。

## 7. 按提交执行的任务清单

### 任务 0：冻结起点

- [ ] 核对 branch、HEAD、status、`git diff --check` 和未跟踪文件；
- [ ] 确认提交链包含用户报告的五个 M5B 节点，HEAD 为 `4d4c7d5` 或记录合法后继；
- [ ] 阅读本计划全文、M5A/M5B results 的负面证据，以及秒杀 reconciliation 文档；
- [ ] 记录 Java/Maven 和依赖版本；只使用 Java 8；
- [ ] 确认工作区干净；若有用户改动，先划定所有权，不 reset/clean/覆盖；
- [ ] 不读取、输出或提交 `.env` 凭据。

建议提交：

```text
docs(traffic): define M5C resource-control contracts
```

### 任务 1：固定 API、配置与指标契约

- [ ] 先写 `Result`/exception/status 的序列化与 MVC contract test；
- [ ] 验证旧 success JSON 和字符串 orderId 不变；
- [ ] 新增强类型配置及边界校验测试；
- [ ] 预注册合法 traffic metric 组合，测试不存在高基数 tag；
- [ ] 把验证码/后台登录已有分支映射到 429/503 和稳定 code；不改规则阈值；
- [ ] catch-all 改为真实 500，并检查现有 MVC 测试期望。

### 任务 2：共享可信 client IP

- [ ] 重命名/提取现有 resolver，不重写已验证 CIDR 算法；
- [ ] 覆盖 direct peer、受信/不受信 proxy、IPv4/IPv6、非法头和多 hop；
- [ ] 保留旧环境变量 fallback；
- [ ] 后台登录回归现有 IP 语义；
- [ ] 秒杀入口把解析后的 IP 显式传入 service，不使用 ThreadLocal 隐式状态。

建议前两项合并提交：

```text
feat(api): add stable overload and dependency error semantics
```

### 任务 3：秒杀前置门禁

- [ ] 先写 Lua contract test，固定 KEYS/ARGV/返回码/TTL/Redis TIME；
- [ ] 真实 Redis IT 覆盖 activity、user、IP、窗口过期、拒绝不串扰其他计数；
- [ ] guard 必须先于 `RedisIdWorker` 和 MQ；
- [ ] Redis 异常/null/未知码全部 503；
- [ ] 429 时验证 ID worker、producer、stock、reservation、status、processing index、DB 全无副作用；
- [ ] producer 系统错误先做 exact reservation 恢复，再决定 accepted 或 503；
- [ ] 为库存/重复/时间窗业务拒绝补稳定 code，不改原 Lua 状态机。

### 任务 4：本地读并发与有界搜索

- [ ] 用 latch 单测证明 DB/SEARCH 同时进入数不超过配置；
- [ ] 证明 overflow 在 max-wait 后 429，异常/中断/成功都不泄漏 permit；
- [ ] CacheClient 只在 singleflight leader 的实际 DB callback 内持有 DB permit；
- [ ] follower 超时不取消 leader、不移除 entry、不触发第二次 fallback；
- [ ] 热榜命中/回退都保持整页顺序和非空安全语义；
- [ ] shop/blog 搜索与 legacy name search 受 SEARCH permit 保护；
- [ ] ES timeout 配置契约由 full-context 测试读取真实 client 配置或等价可观测构造参数证明；
- [ ] ES 异常 503，无 MySQL `%LIKE%` fallback。

建议任务 3–4 提交：

```text
feat(traffic): add bounded seckill and read admission
```

### 任务 5：前端与回归测试

- [ ] 用户公共 Axios 对 429/503 展示后端 errorMsg；网络异常分支不空指针；
- [ ] 验证码失败不启动倒计时；
- [ ] 秒杀失败不创建 watcher，成功 orderId 仍是字符串；
- [ ] 搜索失败不覆盖现有列表为空；
- [ ] 对改动 HTML 内联脚本做语法检查；
- [ ] 运行 Java 8 compile/testCompile、定向测试和默认全量测试，记录精确 count。

### 任务 6：M5C 隔离栈与真实故障

新增 M5C 专用脚本，不修改 M5A/M5B 历史 runner 的复现行为：

- [ ] 强制 `M5C_ISOLATED=true`、合法 run-id、loopback、互不重复端口；
- [ ] 5 个容器和网络均使用 `com.localdeals.m5c.run-id=<run-id>`；
- [ ] MySQL 使用专用 schema 且在 Flyway 前只做 `SELECT DATABASE()`，不预建 sentinel table；
- [ ] Redis 使用 run-id sentinel；ES 校验专用 cluster name；
- [ ] RocketMQ 使用专用 cluster/topic/consumer group/producer group；
- [ ] 支持两个独立 app/management 端口；PID 和日志均写入本地 artifact；
- [ ] 启动命令显式传真实 `ROCKETMQ_NAME_SERVER`；
- [ ] 故障动作前按 label 再次确认容器所有权；
- [ ] 原始响应、时延、Prometheus、SQL/Redis/MQ delta 放到
  `benchmark/m5c/<run-id>/` 并忽略；
- [ ] cleanup 只删除本 run-id 资源，并报告容器数据不可恢复与残留检查。

### 任务 7：结果与路线图收口

- [ ] 新增 `docs/m5c-resource-traffic-results.md`；
- [ ] 新增 `docs/m5c-resource-traffic-summary.csv`；
- [ ] 保留 pilot、失败、timeout 和不可比较值，不覆盖为 0；
- [ ] 若 Broker 场景取得有效隔离证据，才更新原 `BLOCKED`；否则原样保留并写明原因；
- [ ] 路线图把 M5C 标为完成，下一阶段只进入 M5D；
- [ ] README 只写已验证结论，不写容量提升百分比；
- [ ] 审查意图文件、`git diff --check`、staged diff 和最终 status；不 push。

建议后续提交：

```text
test(traffic): verify M5C isolation and recovery
docs(traffic): record M5C evidence and prioritize M5D
```

## 8. 测试矩阵

### 8.1 单元与契约测试

至少覆盖：

- 配置 null、零、负数、越界和 Duration 边界；
- `Result` 新旧 JSON、error code 和 64 位 orderId 字符串；
- `WebExceptionAdvice` 的 400/429/500/503；
- 验证码 cooldown=429、Redis error=503、登录 Redis error=503；
- 后台既有 401/429/503 回归；
- client IP 信任边界；
- Lua key/ARGV、Redis TIME、固定返回码及拒绝无副作用；
- 秒杀 guard 在 ID worker/producer 之前；
- producer ambiguous error 的 exact reservation 恢复；
- bulkhead 上限、release、interrupt 和 max-wait；
- singleflight follower timeout 后 leader entry/结果仍正确；
- ES/DB 异常映射且不误映业务拒绝；
- 指标合法组合和高基数禁止项。

### 8.2 真实依赖 IT

- 真实 Redis：三维门禁、TTL、跨线程原子性、窗口恢复；
- 真实 MySQL+Redis：cache fallback、热榜读取、失效/空值不回归，Flyway V1–V8 fresh migrate；
- 真实 ES：shop/blog 正常搜索、timeout/stop 后稳定 503、恢复后结果正确；
- 真实 RocketMQ：正常 transaction message 与 consumer 不变量；
- 双 JVM：共享 Redis 秒杀限额和 per-JVM singleflight 上界。

任何 IT 若未显式提供 M5C sentinel/run-id，应主动 skip 或 fail，不得默认连接 localhost 共享依赖。

## 9. 正常与故障证据场景

| ID | 场景 | 最小流量 | 硬门禁 |
| --- | --- | --- | --- |
| C0 | 低于限额的 OTP/admin/search/热榜/秒杀 | 各 20；秒杀复用 B4 模型 | 正常请求 0 个基础设施 429/503；业务不变量通过 |
| C1 | activity 限额 | 单 voucher、跨多个 user/IP，固定窗口内 `limit+20` | guard allowed 恰为 limit，其余 429/activity；429 部分无 ID/MQ/预占 |
| C2 | user 限额 | 单 user、多个请求/IP | allowed 恰为 user limit，其余 429/user |
| C3 | IP 限额 | 单可信 client IP、多个 user | allowed 恰为 IP limit，其余 429/IP；伪造头在非可信 peer 下无效 |
| C4 | DB 读并发 | 多个冷 key + hot rank，阻塞式测试夹具 | 同时 callback 不超过 4；overflow 429 且 `<250ms`；permit 最终为 0 |
| C5 | singleflight follower | 同 key、1 leader + 20 follower | 一次 DB callback；followers 在约 750ms 后 503，不创建第二 leader |
| C6 | 搜索并发 | shop/blog 混合，持有 4 个 permit 后再发 20 | overflow 429 `<250ms`；inflight 不超过 4 |
| X1 | 双实例秒杀 | 两 JVM 同时打一个 voucher | 两实例合计 allowed 不超过共享 limit，不是每实例各一份 |
| X2 | 双实例冷 key | 同一 key 同时访问两 JVM | DB fallback delta `<=2`；明确不声称全局一次 |
| F1 | Redis pause/stop | OTP/admin/seckill 各 20；商铺读作控制 | auth/seckill 100% 503 且 `<2s`、无准入；商铺按 M5B 正确回 DB |
| F2 | MySQL stop + 冷回源 | 多 distinct key 并发 | 无 HTTP 200 假成功/空值写入；最多 4 个 leader 受底层等待，其余 429；follower 有界 503 |
| F3 | ES pause/stop | shop/blog search 各 20 | 仅 429/503、全部 `<2s`、无 DB LIKE；恢复后正确结果 |
| F4 | consumer pause + 入口过载 | 先接受 1 单，再暂停 consumer 并压入口 | 既有 PROCESSING 保留；恢复后收敛；重复订单/负库存 0 |
| F5 | Broker stop 下新准入 | 专用 topic/group，20 请求 | 100% 503 `<2s`；Redis stock/reservation/PROCESSING、DB、Broker visible message 均无增量 |
| R1 | 依赖恢复 | Redis/DB/ES/Broker 各恢复后探测 | 新请求在记录的恢复窗口内正确；inflight 清零；既有 backlog 收敛 |

F2 的硬门禁刻意不承诺首 4 个 DB leader 都在 2 秒内结束。若它们超时，结果文档必须记录
实际最大值和底层 Hikari/driver 边界；不得增加 executor 或私自修改 Hikari 来让表格好看。

C1–C3 必须使用库存高于目标 limit 的专用 voucher，并把非目标维度配置为不会先触发的值，
否则只能证明“某个门禁拒绝”，不能证明目标维度的精确上界。F1/F5 的秒杀请求也使用不同用户，
避免正常 user-limit 把依赖故障误分类为 429。

F5 只有以下预检全部通过才执行：专用容器 label、专用 NameServer/Broker 端口、run-id topic/
group、进程连接无 9876、Redis/MySQL sentinel 均匹配。任一失败立即停止，不发送探针，并继续
保留 M5A `BLOCKED`。

## 10. 性能与正确性门禁

- Java 8 默认全量、所有新增定向和真实依赖 IT 必须 0 failure/error；
- C1–C3 用服务端 Redis TIME 对齐窗口，不能跨窗口后仍声称精确 limit；
- 所有 429/503 body 必须 `success=false` 且 code 稳定；业务拒绝不能误记成限流；
- 正常 B3 搜索参考流量不得出现 limiter rejection；吞吐低于 M5A 单轮值的 80% 或最差 P99
  超过 200ms 时记为灾难性回归，不声称性能提升；
- 正常 B4 参考流量的 MySQL 订单、DB/Redis 库存、reservation、SUCCESS、PROCESSING、重复订单
  和负库存不变量必须全部通过；总吞吐低于 `168.032 req/s` 时记录回归；
- 429 本地拒绝 P99/max 均小于 250ms；Redis/ES/Broker 目标故障返回小于 2 秒；
- consumer pause 恢复后记录真实 Broker lag 和收敛秒数，不用应用 Counter 代替 lag；
- 双实例结论必须用两个不同 app/management 端口和同一专用 Redis/MySQL 证明；
- 基线工具、线程数或主机负载不可比时，性能项标 `NA`，但正确性和拒绝上界仍必须判断。

## 11. 完成条件

M5C 只有同时满足以下条件才可标记完成：

- [ ] 秒杀三维门禁位于 ID/MQ/预占之前，跨实例共享上界通过；
- [ ] 429 请求没有订单 ID、MQ、Redis 资格或 DB 副作用；
- [ ] Redis 不可用时 auth/seckill 有界 503 且 fail closed；
- [ ] 搜索故障不再 HTTP 200，也不回退到 MySQL `%LIKE%`；
- [ ] DB/SEARCH 本地并发上限、permit release 和恢复通过；
- [ ] follower 等待有界且没有第二次 DB load；
- [ ] 已接受订单 consumer/reconciliation/status/Outbox 未被 guard 调用；
- [ ] 业务拒绝 code、字符串 orderId 和前端行为兼容；
- [ ] 指标只含有限标签；
- [ ] Java 8 全量、定向、真实 IT、脚本语法与 `git diff --check` 全绿；
- [ ] 负面结果、首 leader I/O 边界和跨实例 `<=实例数` 边界已记录；
- [ ] 隔离资源精确清理且无本 run-id 残留；
- [ ] M5A Broker 项只有在 F5 有效时才解除 BLOCKED；
- [ ] 未夹带 Hikari/Redisson、M5D/M6 或技术栈升级；
- [ ] 形成可回滚本地提交链且未 push。

Broker F5 若因预检停止，可以将 M5C 其余已满足项收口为完成并继续保留该项 `BLOCKED`；结果
和路线图必须显式写“Broker 新准入仍未验证”，不能用 Redis 或商铺控制路径替代。

## 12. 停止线

出现以下任一情况，立即停止相关实现/故障流量并保存事实：

- branch/HEAD/工作区与交接不符且无法确认改动所有权；
- 应用、容器、schema、Redis sentinel、ES cluster、NameServer、topic 或 group 指向共享环境；
- 进程存在到共享 `localhost:9876` 的连接；
- Redis 异常时秒杀继续生成 ID、发送 MQ 或产生预占；
- rate reject 后 stock/reservation/status/processing/DB 任一变化；
- guard 被 consumer、reconciler、status 或 Outbox 调用；
- permit 泄漏、并发超过配置，或 follower timeout 启动第二次 DB load；
- 需要引入线程池取消 JDBC、Redis 锁、通用流控框架或动态规则平台才能继续；
- 需要修改 M5B cache key/payload/TTL 或 M4/M3 正确性状态机；
- 需要调整 Hikari/Redisson 才能让首 leader 延迟达标；保留结果并转后续决策；
- 两次受控 ES/RocketMQ timeout 配置尝试后仍无法在 2 秒内返回；标记具体项 BLOCKED，不反复调参；
- 正常流量出现无法解释的 429、accepted 订单丢失、重复订单或负库存；
- 故障后需要删除共享 Broker 消息、consumer offset 或 Redis key 才能恢复。

停止不等于删除负面证据。结果文档必须保留命令、时间、环境、失败点和未做的清理。

## 13. 实施会话交接要求

最终报告至少包含：

1. branch、起始/最终 HEAD、工作区状态和本地提交链；
2. Java/Maven/依赖版本、run-id、schema、端口、topic/group 和隔离证明；
3. 默认、定向、真实依赖测试的精确 test count；
4. C0–C6、X1/X2、F1–F5、R1 的逐项状态、P95/P99/max 和拒绝计数；
5. stock/reservation/PROCESSING/MySQL order、重复订单、负库存和 Broker lag 不变量；
6. 429/503/业务 code 的实际响应样例与前端验证；
7. 所有 pilot、失败、调整和不可比数据；
8. 首个 DB leader、跨实例 cache fallback 和 Redisson/Hikari 的明确遗留边界；
9. Broker BLOCKED 是否被有效证据解除；
10. 精确 cleanup 范围、不可恢复容器数据和残留检查；
11. 未 push 声明和下一阶段只进入 M5D 的边界。

### 可复制到实施会话的提示词

```text
请先阅读 /home/sd101t/IdeaProjects/hm-dianping/docs/modernization-roadmap.md、
docs/m5c-resource-traffic-plan.md、docs/m5a-observability-results.md 和
docs/m5b-bounded-cache-results.md，只实施 M5C。

当前预期分支 codex/platform-hardening，起点 HEAD 4d4c7d5。先核对 branch/log/status/diff，
保护用户改动，不 reset/clean，不 push。严格按 M5C 计划任务 0→7 执行。

主线只有：稳定 429/503/业务 code；秒杀在 ID 和 MQ 前的 Redis activity+user+IP 固定窗；
DB_READ/SEARCH 两个 JVM semaphore；singleflight follower 有界等待；ES 有界 timeout；对应前端、
指标和隔离证据。不得引入动态规则平台、限流框架、线程池 timeout、Redis 锁、额外缓存层，
不得调整 Hikari/Redisson，不开始 M5D/M6。

Broker 故障探针只有在 run-id 容器、专用 schema/sentinel/cluster/topic/group、真实
ROCKETMQ_NAME_SERVER 和进程无 localhost:9876 连接全部证明后才可执行；否则保留 BLOCKED，
不要发送请求或清理共享 Broker。Java 8，真实依赖只用隔离环境；保留所有负面结果，分小提交，
最终报告精确测试计数、状态/时延、不变量、cleanup、提交链和 git status。
```
