# M5B 有界缓存实施与验证结果

> 状态：完成
>
> 执行日期：2026-08-20
>
> 分支：`codex/platform-hardening`
>
> 规划起点：`5617826 docs: mark M5A complete and prioritize M5B`
>
> 结果摘要：`docs/m5b-bounded-cache-summary.csv`

## 1. 结论与范围

M5B 已按 `docs/m5b-bounded-cache-plan.md` 完成。商铺详情与商铺类型字典保留原有 Redis
key 和 payload，在 Redis miss、坏值、命令超时或断连时由当前请求线程安全回源 MySQL，并以
单 JVM、单 key singleflight 合并同一时间窗内的 DB 查询。Redis 回写和事务提交后的精确失效
均为 best-effort，不改变已经得到的 MySQL 结果。

本阶段没有引入布隆过滤器、逻辑过期、Caffeine、多级缓存、Redis/Redisson 锁、新请求线程池、
Hikari 调整、MQ/ES 升级、HTTP 429/503 或 M6 业务功能。M5A 的“Broker 不可用时新秒杀准入”
仍为 `BLOCKED`，本阶段没有补测，也不能由商铺读路径推断其安全性。

## 2. 已实现契约

- 保持 `cache:shop:{id}`、Shop JSON、`_NULL_PLACEHOLDER_`、
  `cache:shop:type:list:`、List JSON 和空数组 `[]` 兼容，无数据迁移。
- Spring Data Redis command timeout 与 Lettuce pool max-wait 均为 `500ms`，由 full-context
  测试读取实际 `LettuceConnectionFactory` 配置确认。
- TTL 使用 `Duration` 配置并校验：详情正值 `30s`、详情空值 `30s`、类型正值 `100m`、
  类型空值 `30s`；全部必须大于 0，空值 TTL 不得大于对应正值 TTL。
- 详情 payload 校验 ID 一致性；类型列表校验数组形态、非 null 元素、正数且不重复的 ID。
  malformed JSON、错误 ID 和非法列表均计为 `bad_value`，不解释为不存在或空列表。
- miss、bad value 和 Redis unavailable 进入同一 DB load 边界；DB 异常按原异常传播，
  不写空值。Redis read 已失败时跳过本轮 set，避免再等待一次必然失败的命令。
- singleflight 不使用 executor、自旋或分布式锁；leader/follower 共享值、空结果和异常，
  follower 中断恢复 interrupt 标记，完成后精确清理 map entry。
- create/update/assign 只在事务 commit 后删除 exact 详情 key；rollback 不失效。详情 eviction
  与 GEO remove/add 独立执行，任何 Redis 维护失败均不改变已经提交的 DB 结果。
- 指标只增加有限枚举：`bad_value`、`leader|shared`、以及缓存
  `write|evict` 的 `success|failure|skipped`，不包含 shop ID、key 或异常文本。

## 3. 环境与隔离

| 项目 | 实际值 |
| --- | --- |
| Java | Alibaba Dragonwell Extended Edition `1.8.0_472` |
| Maven | `3.9.11` |
| MySQL | `8.0.45`，镜像 `mysql:8.0` |
| Redis | `6.2.21`，镜像 `redis:6.2` |
| Elasticsearch | `7.17.18`，本地 IK 镜像 |
| RocketMQ 测试启动依赖 | 镜像 `apache/rocketmq:4.9.4` |
| run-id | `m5b-20260820a` |
| schema | `m5b_m5b_20260820a` |
| 应用 / management | `127.0.0.1:38093` / `127.0.0.1:38094` |
| MySQL / Redis | `127.0.0.1:33316` / `127.0.0.1:36380` |
| ES / NameServer / Broker | `127.0.0.1:39210` / `127.0.0.1:39877` / `127.0.0.1:30921` |

隔离脚本强制 `M5B_ISOLATED=true`、合法 run-id、loopback 和互不重复的专用端口；容器和网络
均带 `com.localdeals.m5b.run-id=m5b-20260820a`，MySQL 以专用 schema、Redis 以 run-id
sentinel 双向确认连接目标。应用使用真实属性 `ROCKETMQ_NAME_SERVER` 指向专用 NameServer。
正式比较只运行一个应用实例，因此 singleflight 结论只覆盖单 JVM。

原始响应、时延、Prometheus、DB/Redis 计数与日志保存在已忽略的
`benchmark/m5b/m5b-20260820a/`，没有提交凭据、日志或全量指标。收尾检查确认本 run-id 的
容器和网络均无残留；容器删除不可恢复，原始本地 benchmark artifact 仍保留。

## 4. 自动化测试

所有 Maven 测试均显式使用 Java 8。

| 层级 | 命令/选择器 | 结果 |
| --- | --- | --- |
| 缓存定向 | `mvn -Dtest=BoundedCachePropertiesTest,RedisTimeoutConfigurationTest,LocalDealsMetricsTest,SingleFlightLoaderTest,CacheClientTest,ShopTypeServiceImplTest,AdminCatalogServiceTest test` | 35 tests，0 failure/error/skipped |
| 风险定向 | 下方 14 类精确 selector | 109 tests，0 failure/error/skipped |
| 真实 Redis IT | `mvn -Dtest=BoundedCacheRedisIT test`，显式 M5B Redis env/sentinel | 4 tests，0 failure/error/skipped |
| 真实 MySQL + Redis IT | `mvn -Dtest=BoundedCacheMySqlRedisIT test`，显式 M5B schema/Redis env | 3 tests，0 failure/error/skipped；Flyway V1-V8 全部应用 |
| 默认全量 | `mvn clean test`，所有依赖变量指向 M5B 隔离栈 | 267 tests，0 failure/error/skipped |
| 脚本 | `bash -n scripts/m5b-isolated-stack.sh scripts/run-m5b-cache-check.sh` | 通过 |

风险定向命令中的 `-Dtest` 精确值为：

```text
BoundedCachePropertiesTest,LocalDealsMetricsTest,SingleFlightLoaderTest,CacheClientTest,
ShopTypeServiceImplTest,AdminCatalogServiceTest,AdminAuthServiceTest,SeckillLuaScriptContractTest,
SeckillOrderProducerTest,SeckillOrderConsumerTest,SeckillOrderReconcilerTest,
BlogLikeOutboxBatchServiceTest,ReliabilityBacklogCollectorTest,BlogHotRankServiceTest
```

在最终一处纯指标描述文字修正后，又单独执行 `LocalDealsMetricsTest`，结果通过。并发单测用
32 个同时到达的调用验证相同 key 的 callback 恰好执行一次，也覆盖空值、leader 异常、
follower 中断和 entry 清理。

## 5. 正式场景结果

下表为最终正式轮；`correct` 同时检查 HTTP transport 和响应 body 的 success、ID、名称或列表
内容。完整逐行数据见 summary CSV。

| 场景 | phase | correct/total | P95 / P99 / max ms | throughput req/s | DB fallback | 结论 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| C1 | round-1 cold / warm | 1000/1000；1000/1000 | 0.745 / 2.647 / 7.583；0.574 / 0.866 / 1.104 | 1278.557；1288.711 | 1；0 | PASS |
| C1 | round-2 cold / warm | 1000/1000；1000/1000 | 0.632 / 1.350 / 5.707；0.495 / 0.877 / 1.441 | 1260.964；1267.465 | 1；0 | PASS |
| C1 | round-3 cold / warm | 1000/1000；1000/1000 | 0.559 / 0.992 / 3.176；0.518 / 0.826 / 3.599 | 1266.224；1281.272 | 1；0 | PASS |
| C2 | cold / warm | 20/20；100/100 | 0.637 / 2.238 / 2.238；0.439 / 0.543 / 0.988 | 860.922；1146.545 | 1；0 | PASS |
| C3 | cold / warm / empty | 20/20；100/100；1/1 | 31.632 / 31.678 / 31.678；4.659 / 8.250 / 8.349；2.967 / 2.967 / 2.967 | 453.317；1153.161；68.664 | 1；0；1 | PASS |
| C4 | malformed / wrong-id / invalid-list | 2/2；2/2；2/2 | 2.743 / 2.743 / 2.743；2.778 / 2.778 / 2.778；3.754 / 3.754 / 3.754 | 92.876；100.034；95.616 | 1；1；1 | PASS |
| F1a | Redis pause | 20/20 | 502.297 / 503.687 / 503.687 | 38.020 | 11 | PASS，0 transport error |
| F1b | Redis stop | 20/20 | 502.498 / 502.557 / 502.557 | 38.175 | 9 | PASS，0 transport error |
| F2a | warm shop / types，MySQL stop | 20/20；20/20 | 0.650 / 0.971 / 0.971；0.775 / 1.061 / 1.061 | 825.153；817.607 | 0；0 | PASS |
| R1 | Redis pause hot-rank | 1/1 | 533.630 / 533.630 / 533.630 | 1.841 | NA | PASS，返回非空 DB 榜单 |

F1a/F1b 全部请求均小于 2 秒。F1b Redis ready 后 `51ms` 内重新写缓存并转为 hit，低于 5 秒
恢复门禁。Redis 故障期间的 20 个请求跨越多个约 500ms 的完成窗口，所以分别产生 11 和 9
次 leader DB 查询；每个重叠 singleflight 窗口仍只有一个 leader。C1 的严格同批冷并发三轮
均为 1 次 fallback，满足计划的放大门禁。

C1 三轮 warm 吞吐中位数为 `1281.272 req/s`，高于 M5A warm 基线 `1077.586 req/s` 的
90% 门槛 `969.827 req/s`；最终最差 C1 P99 为 `2.647ms`，低于 `10ms`。但 M5A 使用 JMeter，
M5B runner 使用 curl/xargs，生成器并非同条件，因此这里只判定灾难性回归门禁通过，不声明性能
提升百分比。

C2/C3 的空值 key 均验证 TTL `>0` 且不超过配置；真实 Redis IT 进一步以紧容差验证正/空 TTL。
C4 三类坏值均在第一次请求回 DB 并覆盖 exact key，第二次命中修复值。

## 6. 指标与依赖计数

最终 Prometheus 快照中的关键累计值：

- `shop_detail`: `redis_error=40`、`bad_value=2`、`db_success=27`、`db_empty=1`；
- `shop_type`: `bad_value=1`、`db_success=3`、`db_empty=1`；
- singleflight：详情 `leader=28/shared=48`，类型 `leader=4/shared=19`；
- maintenance write：详情 `success=8/skipped=20/failure=0`，类型
  `success=4/skipped=0/failure=0`。

故障过程重启了 MySQL/Redis，服务端累计计数器从 MySQL `178` 到 `59`、Redis `1269` 到
`172`。因此 dependency delta 明确记录为 `NA/counter_reset_by_fault_scenario`，没有把缺失或
重置伪填为 0。正确性、应用侧 fallback 和 Redis 恢复证据不依赖这两个不可比较的累计差值。

## 7. 保留的负面证据

- 第一版隔离栈在 Flyway 前预建 MySQL sentinel table，触发 `baseline-on-migrate` 跳过 V1，
  随后 V2 失败。该做法已改为只读 `SELECT DATABASE()` 身份校验；删除并重建仅属于该 run-id
  的隔离栈后，V1-V8 和真实 MySQL IT 通过。
- pilot 的首次 C1 cold P99 为 `48.455ms`；第一次 formal attempt 为 `10.941ms`，超出 10ms
  门槛 `0.941ms`。两轮原始摘要均保留。加入 1000 次不计分 JVM/连接预热后重新执行完整正式轮，
  所有 C1 P99 才通过；没有删除或改写失败数据。
- MySQL cold miss 的等待上界、Redis 与 MySQL 同时不可用时的稳定 HTTP 503、以及 429 限流
  不属于 M5B，仍未解决。

## 8. 后续边界

下一阶段只能进入 M5C，并继续保留以下边界：

- DB cold miss 没有线程级 timeout 或 Hikari 隔离，leader 卡在 MySQL 时 followers 会共享等待；
- singleflight 为每 JVM、每 key，多个实例仍可能各自回源一次；
- Redis 故障加高并发 DB fallback 尚无资源级并发上限，HTTP 429/503/业务码尚未统一；
- Redisson 是独立客户端，不继承本次 Spring Data Redis timeout，锁策略未调整；
- M4 热榜仅验证全局 timeout 下的安全 DB fallback，不改变 generation/fencing 协议；
- M5A Broker unavailable 新秒杀准入保持 `BLOCKED`。
