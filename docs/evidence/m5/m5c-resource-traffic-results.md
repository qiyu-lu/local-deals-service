# M5C 资源级流控与稳定降级实施结果

> 状态：完成，Broker 新准入故障项继续 `BLOCKED`
>
> 执行日期：2026-08-21（Asia/Shanghai）
>
> 分支：`codex/platform-hardening`
>
> 起点：`4d4c7d5 docs(cache): record M5B evidence and prioritize M5C`
>
> 汇总：`docs/evidence/m5/m5c-resource-traffic-summary.csv`

## 1. 结论

M5C 已按 `docs/evidence/m5/m5c-resource-traffic-plan.md` 的任务 0→7 完成。秒杀请求在生成订单号和发送
RocketMQ 事务消息前，经 Redis Lua 按 activity、user、可信 client IP 三维固定窗准入；两个 JVM
共享同一 activity 上界。DB 回源和 SEARCH 只使用两枚非公平 JVM semaphore，singleflight 只给
follower 增加 750ms 等待上界，没有取消 leader、增加请求线程池、Redis 锁或通用流控平台。

HTTP 429、503、500 与稳定业务码已经分离。库存不足、重复购买和活动时间窗继续使用 HTTP 200
业务响应；ES 故障只返回 429/503，不自动回退 MySQL `%LIKE%`。consumer、reconciliation、订单
状态查询、Outbox 和 after-commit 缓存维护均不调用入口 guard。

本阶段没有调整 Hikari、Redisson 或 M5B cache key/payload/TTL，也没有开始 M5D/M6。F2 真实
故障保留了首批 4 个已获许可 leader 约 30 秒的 Hikari 底层等待；结果不能表述为“全部 MySQL
故障请求小于 2 秒”。双实例冷 key 的实际 DB fallback delta 为 2，只证明每 JVM 至多一次。

M5A 的 Broker 不可用新秒杀准入仍为 `BLOCKED`。最终 runner 的进程 TCP 预检没有形成全部合格
证据，因此没有停止 Broker、没有发送 F5 请求，也没有用其他依赖故障替代该结论。

## 2. 实现范围

- `Result` 增加可空 `code`，统一 429/503/500；catch-all 返回真实 500/`INTERNAL_ERROR`。
- 验证码、后台登录、用户 token 恢复、秒杀、DB 读和搜索依赖边界返回稳定状态与 code。
- `TrustedClientIpResolver` 只在 direct peer 命中显式 CIDR 时信任 `X-Real-IP`/首个 XFF；Redis
  key 仅存 SHA-256 IP 摘要。
- 秒杀 Lua 使用 Redis `TIME`、同 voucher hash tag、先读后写和至多 `2*window+1s` TTL；429
  不消耗非目标维度，也不会到达 ID worker、MQ 或资格预占。
- `DB_READ` 覆盖实际 cache leader DB callback、热榜 DB 查询与 hydrate；`SEARCH` 覆盖 shop/blog
  ES 搜索及 legacy name search。permit 在成功、异常和中断后精确释放。
- follower timeout 不 cancel leader、不移除 leader entry、不启动第二次 DB load；leader 仍在请求
  线程执行。
- ES connect/socket 默认配置为 500ms/1s；正式隔离 F3 只做过一次已记录的 runner 调整到 800ms。
- 前端只在 OTP 成功后倒计时，秒杀失败不启动 watcher，搜索失败保留现有列表；Axios 网络异常
  不再解引用缺失的 `error.response`。
- fresh schema 首轮发现 ES initializer 早于 Flyway，现以 `@DependsOn("flywayInitializer")` 固定
  启动顺序；最终专用 schema 的 Flyway V1–V8 共 8 条全部成功。

## 3. 环境与隔离

| 项目 | 实际值 |
| --- | --- |
| Java | Alibaba Dragonwell Extended Edition `1.8.0_472` |
| Maven | `3.9.11` |
| MySQL / Redis / ES / RocketMQ | `mysql:8.0` / `redis:6.2` / ES `7.17.18` IK image / RocketMQ `4.9.4` |
| run-id / schema | `m5c-20260821e` / `m5c_m5c_20260821e` |
| app / management | `127.0.0.1:18081,18082` / `127.0.0.1:19081,19082` |
| MySQL / Redis / ES | `127.0.0.1:13316` / `127.0.0.1:16389` / `127.0.0.1:19201` |
| NameServer / Broker | `127.0.0.1:19877` / `127.0.0.1:20921`，HA `20922` |
| topic / consumer / producer group | `m5c-20260821e-seckill` / `m5c-20260821e-consumer` / `m5c-20260821e-producer` |

5 个容器和网络均带 `com.localdeals.m5c.run-id=m5c-20260821e`。MySQL 在应用启动前仅由 stack
执行 `SELECT DATABASE()`；没有预建 sentinel table。Redis 使用 run-id sentinel，ES cluster name、
RocketMQ cluster/topic/group 均逐项核验。应用命令显式传入真实 `ROCKETMQ_NAME_SERVER`。

最终 cleanup 只删除上述 label 的 5 个容器和 1 个网络；容器数据不可恢复。残留检查为 0，
`benchmark/m5c/` 已加入 `.gitignore`。

## 4. 测试计数

| 测试层 | 结果 |
| --- | --- |
| M5C 主要定向单元/MVC（实现提交时） | 58 tests，0 failure/error/skip |
| 用户 token Redis 故障补测 | 3 tests，0 failure/error/skip |
| 默认 4 permit 与 20 follower 精确补测 | 8 tests，0 failure/error/skip |
| C4–C6 runner 内定向 | 10 tests，0 failure/error/skip |
| 真实 Redis `SeckillTrafficGuardRedisIT` | 2 tests，0 failure/error/skip |
| Java 8 默认全量（最终） | 300 tests，0 failure/error/skip |
| 前端契约 | `M5C frontend contracts: PASS` |
| 脚本/源码检查 | 两个 Bash `bash -n`、Python CLI、`git diff --check` 全部通过 |

真实 Redis IT 首次在 sandbox 内因禁止创建 socket 而 2 error，使用同一专用 sentinel 在获准网络
环境下原命令复跑为 2/2 通过；该失败不是业务结果，仍保留在执行记录中。

## 5. 场景证据

精确数值见 CSV。关键结果如下：

- C0：search/hot/OTP/admin/seckill 各 20；80 个正常 HTTP 200，后台 20 个无效凭据为预期 401，
  没有基础设施 429/503。所有入口最大 170.950ms，秒杀最大 73.780ms；订单/reservation/SUCCESS
  均为 20，DB/Redis 库存均为 980，processing/重复订单为 0。搜索 P99 为 60.951ms；当前 Python
  小样本与 M5A JMeter 的 B3/B4 流量工具和线程模型不可比，吞吐记 `NA`，不声称提升或回归。
- C1：Redis TIME 对齐后 activity `300 allowed + 20 rejected`，P99 242.672ms；300 个准入全部
  收敛，DB/Redis 库存均为 700。
- C2：user `2 allowed + 20 rejected`；两个已准入请求中一个成功、一个按现有一人一单返回
  `SECKILL_DUPLICATE`，不误计为限流。
- C3：可信同 client IP `100 allowed + 20 rejected`，P99 49.281ms；非可信代理头边界由 resolver
  contract test 覆盖。
- C4/C6：默认 4 permits，4 个 holder 外的 20 个 overflow 均在 250ms 内被拒绝；成功、异常、
  中断后 permit 可再次取得，inflight 回到 0。
- C1–C3、X1 和 F4 的 429 样本最大值分别为 56.170/12.273/18.540/71.339/47.193ms，均小于
  250ms；该门禁只针对拒绝样本，不把同批已准入请求的端到端时延冒充本地拒绝时延。
- C5：1 leader + 20 follower，DB callback 仅 1 次；followers 约 750ms 503，leader entry 保留并
  在完成后清理，没有第二 leader。
- X1：两个不同 app/management 端口合计仅 300 allowed，其余 20 为 activity 429；初始 pilot
  跨相邻固定窗而 320 allowed，按停止线保留并改为 Redis TIME 对齐后重测。
- X2：40/40 HTTP 200，两个 JVM 合计 DB fallback delta=2；结论明确为 per-JVM。
- F1：Redis pause 下 OTP/admin/seckill 各 20/20 为 503，最大 509.831ms；商铺控制路径 20/20
  回 MySQL，最大 507.865ms。首次秒杀得到 500，定位到 token interceptor 后补为稳定 503。
- F2：20 个 distinct cold key 中 16 个 `READ_OVERLOADED` 429，4 个 leader
  `DATABASE_UNAVAILABLE` 503；max 30012.504ms。相同 key 的 20 个 follower P95 757.532ms，唯一
  leader 30011.526ms。没有 HTTP 200 假成功。
- F3：40 个混合搜索为 36 个 `SEARCH_OVERLOADED` 429 + 4 个 `SEARCH_UNAVAILABLE` 503；
  max 1014.008ms，无 MySQL LIKE fallback。第一次 1s socket timeout pilot max 2006.810ms，随后仅
  调整一次隔离 runner 到 800ms。
- F4：先接受 1 单并确认 PROCESSING，再暂停专用 consumer group；测试夹具只为消除消费竞态而
  临时持有该券 MySQL 行锁。同时入口过载为 300 allowed + 20 rate rejected；暂停时主 topic
  lag=301、retry lag=0、总 lag=301。resume 后释放夹具，14980ms 内主/retry/总 lag 全部归零，
  301 个订单与预约均为 SUCCESS、processing=0、重复订单=0、负库存=0。
- R1：MySQL 恢复后先等待两个 readiness；shop/search 分别 8.752ms/9.756ms 返回正确 200。
- F5：`BLOCKED`，0 请求，未停止 Broker。M5A 原结论不变。

实际响应样例如下；成功的 64 位 orderId 保持 JSON 字符串，业务重复没有被误映为基础设施过载：

```json
{"http":200,"body":{"success":true,"data":"89801697370570756"}}
{"http":429,"body":{"success":false,"code":"SECKILL_RATE_LIMITED","errorMsg":"请求过于频繁，请稍后重试"}}
{"http":503,"body":{"success":false,"code":"AUTH_STATE_UNAVAILABLE","errorMsg":"登录状态暂时不可用，请稍后重试"}}
{"http":200,"body":{"success":false,"code":"SECKILL_DUPLICATE","errorMsg":"您已抢过该优惠券"}}
```

## 6. 负面 pilot 与修正

所有负面 pilot 均未被改写成 0：

1. stack 首次用 `consumerProgress` 判断尚无 retry topic 的新 group，产生 false negative；改为
   `getConsumerConfig` 做存在性校验。
2. fresh schema 首次启动因 `ShopIndexInitializer` 早于 Flyway 查询而失败；固定 bean 顺序后，
   V1–V8 fresh migrate 成功，schema 共 22 张表。
3. HTTP probe 的 JSON literal 被 Python `format` 当成字段而 `KeyError`；改为只替换显式占位符。
4. C1 HTTP 已是 300/20，但 Prometheus tag 顺序匹配错误令 runner 停止；改为匹配实际有限标签。
5. F1 秒杀首次 20/20 为 500/`INTERNAL_ERROR`；根因是 token interceptor Redis timeout 未映射，
   修复后 20/20 为 503/`AUTH_STATE_UNAVAILABLE`。
6. F3 首轮 max 2006.810ms；按计划唯一一次把隔离 socket timeout 调到 800ms 后通过。
7. F4 pilot 遗留 legacy membership 导致 `SECKILL_DUPLICATE`；fixture 同时清理 reservation 与
   legacy set 后再测，不能把旧状态当 consumer pause 证据。
8. X1 pilot 跨两个 60 秒 bucket 而 320 allowed；正式轮用 Redis TIME 对齐 10 秒测试窗。
9. MySQL start 后立即恢复探针曾 5 秒 transport timeout；runner 改为两个 JVM readiness 均恢复
   后才记录 R1，没有调整 Hikari。
10. 第二个 run-id 的 runner 在业务流量前两次遇到单次 `topicList` 暂不可见；独立 `topicStatus`
    连续 5 次均返回 8 个队列。隔离门禁改为有界等待真实 topic route，不绕过 topic/group 校验。
11. F4 初版按 `Diff Total=301` 等待，但真实输出还含 `%RETRY%` 的 33 条而为 334；该轮被中止并
    精确清理。最终轮分别采集主 topic/retry/总 lag，并要求恢复后三者同时归零。
12. 下一轮虽通过 lag 门禁，但审查发现顺序是“先 pause 再接受”，不符合 F4 契约；没有采用该轮
    作为最终证据。最终轮用临时行锁消除消费竞态，严格执行“先接受并确认 PROCESSING，再 pause”。
13. 最终审查发现早期 runner 只记录 C0/C1–C3/X1 的 HTTP/指标，没有把 DB/Redis 库存、订单、
    reservation、SUCCESS、processing 和重复订单全部设为硬断言；最终轮补齐后全部通过。

另有一次误写 Maven phase `testCompile`，命令立即失败；正确 Java 8 命令使用 `test-compile`。

## 7. 保留边界与下一阶段

- 固定窗在相邻窗口理论上允许短时 2 倍突发；本阶段没有升级为滑动窗或令牌桶。
- singleflight 和 semaphore 均为 JVM 本地；双实例不承诺集群一次回源。
- 首批 DB leader 仍受 Hikari/driver 约 30 秒边界；M5C 不通过线程池取消 JDBC，也不调整 Hikari。
- Redisson timeout、M5B 缓存协议、消费重试、对账和 Outbox 未改动。
- Broker 新准入仍 `BLOCKED`；只有专用 label/port/topic/group、正确 NameServer、进程 TCP 无 9876
  和 delta 证据同时成立，未来才可执行 F5。
- 下一阶段只进入 M5D 指标与故障收口，不开始 M6，也不升级技术栈。

## 8. M5D 后续收口说明（2026-08-22）

本文件以上内容仍是 M5C 当时的历史证据，尤其 Broker F5 的 `BLOCKED`/0 请求不追溯改写。
M5D 后续建立 run-id 专用 RocketMQ，并以两个应用 PID 的环境变量和到专用 NameServer/Broker
TCP 连接为门禁；门禁通过后 F5 得到 20/20 503 `SECKILL_SUBMIT_UNAVAILABLE`，库存、预约、
processing、DB 和 Broker 可见消息均无增量。完整结果见 `docs/evidence/m5/m5d-reliability-results.md`。
