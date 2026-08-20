# M5A 低基数指标目录与管理面契约

> 状态：M5A 实施契约；商铺缓存条目含 M5B 有限扩展
>
> 基线提交：`a36e379`
>
> 约束：本目录定义指标语义，不表示 M5B 缓存优化或 M5C 限流已经实现。

## 1. 全局规则

- 应用内统一使用 Micrometer 名称；Prometheus 将 `.` 转为 `_`，Counter 增加 `_total`，
  Timer 导出 `_seconds_count/_seconds_sum`，DistributionSummary 导出 `_count/_sum`。
- 指标标签只能取本文列出的固定值。禁止 userId、orderId、voucherId、blogId、shopId、
  merchantId、手机号、用户名、IP、token、SQL、Redis key、异常文本和原始动态 URI。
- Counter/Timer 必须用时间窗增量计算 rate；进程启动以来的累计值不能直接跨轮比较。
- Gauge 的 `NaN` 表示本轮采集不可用，`0` 才表示采集成功且事实为零；collector Counter
  同时记录 `success|failure`，便于区分数据缺失和真实零值。
- 埋点失败不得改变业务结果；所有业务异常继续按原契约传播或返回。
- M5A 不配置告警阈值。是否告警和目标水位由三轮基线与故障盘点决定。

## 2. 已有兼容指标

以下名称保持兼容，不在 M5A 更名。

| Micrometer / Prometheus | 类型 | 标签和值域 | 记录点与解释 |
| --- | --- | --- | --- |
| `local_deals.seckill.requests` / `local_deals_seckill_requests_total` | Counter | `result=accepted|rejected_stock|rejected_duplicate|rejected_activity|unavailable` | 秒杀准入返回分支，每个请求一次；`accepted` 包含发送异常后通过 exact reservation 恢复确认的请求。 |
| `local_deals.seckill.mq.consume` / `local_deals_seckill_mq_consume_total` | Counter | `result=success|failure` | 兼容旧指标。`success` 包含新落库和 already-success ACK；`failure` 混合暂态重试、已补偿 ACK、永久补偿与隔离，不能单独推断 DLQ 或 Broker lag。M5A 在目录中保留这一局限，不用动态异常标签拆分。 |
| `local_deals.seckill.mq.consume.outcome` / `local_deals_seckill_mq_consume_outcome_total` | Counter | `result=persisted|already_success|already_failed|malformed|lock_busy|reservation_mismatch|state_missing|compensated|quarantined|transient_error|compensation_error|quarantine_error` | M5A 新增的有限详细结果，每次 delivery attempt 只递增一个；与兼容 aggregate 分开查询，不能和旧 `success|failure` 相加。WebSocket 通知是持久终态后的 best-effort，不改变本指标 outcome。 |
| `local_deals.seckill.db.orders` / `local_deals_seckill_db_orders_total` | Counter | `result=duplicate|stock_rollback` | DB 幂等命中和库存条件更新失败；不是全部订单写入计数。 |
| `local_deals.seckill.reconciliation` / `local_deals_seckill_reconciliation_total` | Counter | `result=success_repaired|timeout_compensated|pair_conflict_compensated|pair_conflict_blocked|order_id_quarantined|invalid_state_quarantined|compensation_disabled|deferred|claim_skipped|scheduler_busy|lock_busy|scan_error|database_error|state_error|invalid_state` | 每个对账分类分支一次。有限枚举保留现有语义；Broker lag 不能从该指标推算。 |

## 3. M5A 新增指标

### 3.1 点赞命令与 Outbox

| Micrometer / Prometheus | 类型/单位 | 标签和值域 | 记录点、重试和失败语义 |
| --- | --- | --- | --- |
| `local_deals.blog.like.command` / `local_deals_blog_like_command_total` | Counter | `operation=like|unlike`; `result=changed|unchanged|not_found|failure` | `setLiked` 的事务方法退出点。只有关系真实变化并成功提交才是 `changed`；NOOP 为 `unchanged`；不存在为 `not_found`；异常为 `failure`。事务提交失败不得留下 success。 |
| `local_deals.blog.like.outbox.batch` / `local_deals_blog_like_outbox_batch_total` | Counter | `result=success|empty|lock_busy|db_error|redis_lock_error` | 一次 worker flush。每次调用有一个终态结果；Redis 锁异常后 DB fallback 另记一次 `redis_lock_error`，随后仍记录实际 DB 终态，因此该辅助值不参与“总批次数”求和。 |
| `local_deals.blog.like.outbox.duration` / `local_deals_blog_like_outbox_duration_seconds_*` | Timer/seconds | 无 | 只包实际 `processNextBatch`；提交完成后记录成功时间，异常记录耗时但不产生 batch success。发布百分位 histogram。 |
| `local_deals.blog.like.outbox.events` / `local_deals_blog_like_outbox_events_*` | DistributionSummary/events | 无 | 成功提交后记录本批事件数，empty 记录 0；回滚批次不记录。 |
| `local_deals.blog.like.outbox.pending` / `local_deals_blog_like_outbox_pending` | Gauge/events | 无 | 低频采样成功时的 pending 数；失败为 `NaN`。 |
| `local_deals.blog.like.outbox.oldest_age` / `local_deals_blog_like_outbox_oldest_age_seconds` | Gauge/seconds | 无 | 最早 pending 的年龄；无 pending 为 0，采集失败为 `NaN`。 |
| `local_deals.blog.like.outbox.collector` / `local_deals_blog_like_outbox_collector_total` | Counter | `result=success|failure` | 每轮 DB backlog 采样一次。 |

Outbox SQL 先用 `(processed_time,id)` 找 count 与最早 id，再按主键读取 `create_time`；不对
`create_time` 执行无索引 `MIN`。采样独立于 worker 开关，默认 30 秒，可单独关闭。

### 3.2 博客热榜

| Micrometer / Prometheus | 类型/单位 | 标签和值域 | 记录点与解释 |
| --- | --- | --- | --- |
| `local_deals.blog.hot_rank.read` / `local_deals_blog_hot_rank_read_total` | Counter | `result=hit|read_disabled|invalid_page|outside_top_k|not_ready|redis_unavailable|bad_metadata|bad_member|stale|inconsistent_snapshot` | `BlogHotRankService.readPage` 每次只记录一个返回结果。 |
| `local_deals.blog.hot_rank.rebuild` / `local_deals_blog_hot_rank_rebuild_total` | Counter | `result=published|lock_busy|stale_generation|failed` | `rebuild` 每次只记录一个 outcome；stale generation 是正常 fencing，不算 failed。 |
| `local_deals.blog.hot_rank.rebuild.duration` / `local_deals_blog_hot_rank_rebuild_duration_seconds_*` | Timer/seconds | 无 | 从获取锁句柄到 outcome；发布 histogram。 |
| `local_deals.blog.hot_rank.db_fallback` / `local_deals_blog_hot_rank_db_fallback_seconds_*` | Timer/seconds | 无 | 只包 `liked DESC,id DESC` 的 fallback SQL；不包含作者/点赞 hydrate 或序列化。 |
| `local_deals.blog.hot_rank.age` / `local_deals_blog_hot_rank_age_seconds` | Gauge/seconds | 无 | metadata 合法时的榜单年龄；缺失、损坏或 Redis 不可用为 `NaN`。 |
| `local_deals.blog.hot_rank.collector` / `local_deals_blog_hot_rank_collector_total` | Counter | `result=success|failure` | 每轮 metadata 年龄采样一次。 |

### 3.3 商铺缓存

| Micrometer / Prometheus | 类型/单位 | 标签和值域 | 记录点与解释 |
| --- | --- | --- | --- |
| `local_deals.cache.access` / `local_deals_cache_access_total` | Counter | `resource=shop_detail|shop_type`; `result=hit|empty_hit|miss|bad_value|redis_error|db_success|db_empty|db_error` | Redis 阶段记录 hit/empty_hit/miss/bad_value/redis_error；发生 fallback 时再记录一个 DB outcome。因此冷请求会有一个 read outcome 和一个 DB outcome，不能把所有 result 相加当请求总数。`bad_value` 是 M5B 新增的坏 payload 分类。 |
| `local_deals.cache.singleflight` / `local_deals_cache_singleflight_total` | Counter | `resource=shop_detail|shop_type`; `result=leader|shared` | M5B 中每个进入进程内 DB load 合并边界的请求一次；只说明当前 JVM、当前 key 的角色，不能推导跨实例全局调用数。 |
| `local_deals.cache.maintenance` / `local_deals_cache_maintenance_total` | Counter | `resource=shop_detail|shop_type`; `operation=write|evict`; `result=success|failure|skipped` | M5B DB fallback 后的 best-effort 写入与事务提交后的精确失效。Redis read 已失败时本次 write 为 `skipped`；写入/失效失败不能改变 DB 结果。 |
| `local_deals.cache.db_fallback` / `local_deals_cache_db_fallback_seconds_*` | Timer/seconds | `resource=shop_detail|shop_type` | M5B 只包 miss、bad_value 或 redis_error 后实际发生的 DB 查询；followers 不重复记录；发布 histogram。 |

M5A 基线期 Redis 异常仍抛出；M5B 实现将其改为有界 DB fallback，并增加上述有限指标。
指标本身不额外读取缓存，也不携带 cache key、shop ID、异常文本等动态标签。

### 3.4 认证入口

| Micrometer / Prometheus | 类型 | 标签和值域 | 记录点与解释 |
| --- | --- | --- | --- |
| `local_deals.auth.request` / `local_deals_auth_request_total` | Counter | `flow=otp_send|user_login|admin_login`; `result=success|invalid_input|rejected|locked|unavailable|failure` | 每次入口调用一个终态结果。OTP 冷却/验证码错误为 rejected；后台账号/IP 锁为 locked；Redis 门禁不可用为 unavailable；内部未分类异常为 failure。统一登录错误文案不改变。 |

不注册手机号、用户名、IP、验证码或 token 标签；后台不存在账号与密码错误仍使用同一
`rejected` 结果，避免通过指标泄露账号存在性。本阶段不调整任何认证阈值。

### 3.5 Elasticsearch 同步

| Micrometer / Prometheus | 类型/单位 | 标签和值域 | 记录点与解释 |
| --- | --- | --- | --- |
| `local_deals.es.sync.messages` / `local_deals_es_sync_messages_total` | Counter | `table=shop|blog|ignored`; `operation=insert|update|delete|other`; `result=success|partial_failure|failure|ignored` | 每条 Canal 消息一个终态；解析失败为 ignored/other/failure，DDL 或非目标表为 ignored。 |
| `local_deals.es.sync.rows` / `local_deals_es_sync_rows_total` | Counter | 同上 table/operation；`result=success|failure|ignored` | 每行一个终态；缺 id、转换失败和 ES 写失败均为 failure。 |
| `local_deals.es.sync.apply.duration` / `local_deals_es_sync_apply_duration_seconds_*` | Timer/seconds | `table=shop|blog`; `operation=insert|update|delete|other` | 包住一条目标消息的逐行应用，发布 histogram。 |

当前 DTO 没有经验证的事件时间，目录中没有 ES lag 指标。RocketMQ/Canal backlog 必须来自
Broker 管理面，不能由 message Counter 相减推算。consumer 当前逐行异常后 ACK 的事实由
`partial_failure` 暴露，M5A 不改变 ACK/重试语义。

### 3.6 秒杀 PROCESSING 采样

| Micrometer / Prometheus | 类型/单位 | 标签和值域 | 记录点与解释 |
| --- | --- | --- | --- |
| `local_deals.seckill.processing.due` / `local_deals_seckill_processing_due` | Gauge/orders | 无 | Redis TIME 时刻已到期的 processing ZSET 数；失败为 `NaN`。 |
| `local_deals.seckill.processing.oldest_overdue` / `local_deals_seckill_processing_oldest_overdue_seconds` | Gauge/seconds | 无 | 最老已到期 score 的超时秒数；无到期项为 0，失败为 `NaN`。 |
| `local_deals.seckill.processing.quarantine` / `local_deals_seckill_processing_quarantine` | Gauge/orders | 无 | quarantine ZSET 基数；失败为 `NaN`。 |
| `local_deals.seckill.processing.collector` / `local_deals_seckill_processing_collector_total` | Counter | `result=success|failure` | 每轮只读 Lua 采样一次。 |

采样 Lua 只使用 `TIME`、`ZCOUNT`、`ZRANGE ... WITHSCORES` 和 `ZCARD`，不扫描 status Hash，
不执行 ZPOP/ZREM/ZADD/PERSIST/EXPIRE 或补偿；wrong type、timeout、null 或畸形返回均为失败。

## 4. Spring/进程自动指标

隔离实例的 Prometheus 文本必须确认至少存在以下系列；最终结果文档记录实际名称：

- `http_server_requests_seconds_*`，`uri` 必须是路由模板或有限的
  `UNKNOWN|NOT_FOUND|root`，不得含具体数字 ID 或查询参数；
- `hikaricp_connections_active|idle|pending|max|min`；
- `jvm_memory_used_bytes`、`jvm_gc_pause_seconds_*`、`jvm_threads_live_threads`；
- `process_cpu_usage`、`process_resident_memory_bytes`、`process_uptime_seconds`。

若当前 Spring Boot/Micrometer 导出名不同，以实际抓取为准更新本节，不伪造缺失值。

## 5. 管理面威胁边界

- 应用业务端口仍为 8083；management 使用独立可配置端口，默认 18084，并默认绑定
  `127.0.0.1`。本地 Prometheus 只能从 loopback 抓取。
- 容器化生产若需跨容器抓取，显式覆盖 address 为容器接口，并且只加入内部监控网络；
  management port 不映射到公网，也不复用消费者或后台 token 认证。
- 仅暴露 `health,prometheus`。不暴露 `info,metrics,env,configprops,beans,mappings,loggers,
  heapdump,threaddump`；health `show-details=never`。
- nginx 在 `/api/` 通用代理之前使用高优先级规则拒绝 `/api/actuator` 及其子路径。
- liveness 只描述进程可运行性；ES 等可选读模型故障不触发无限重启。readiness 与具体依赖
  的纳入范围由部署环境显式配置，M5A 不以隐藏依赖故障换取绿色 health。
- 正式基线只从 management loopback/受信监控网络采集；公共 nginx、消费者 token、后台
  token 均不能取得管理数据。

## 6. 基线计算与缺失值

- Counter、MySQL status、Redis commandstats、ES node stats 使用每轮前后 delta。
- 三轮正式结果报告中位数并保留最差 P99；冷/热缓存分开，不混成一个均值。
- MQ lag 只接受本轮独立 Broker 的 `consumerProgress` 等管理面结果；无法采集则留空并注明
  BLOCKED，禁止填写 0。
- Canal E2E 时间只接受外部提交时间到 ES 可见时间；直接调用 consumer 只能标记为
  consumer-level。
- collector failure、进程重启或抓取缺失均留空/NaN 并说明，不向 CSV 填写虚假 0。
