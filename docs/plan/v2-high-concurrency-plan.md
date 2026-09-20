# V2 重构计划：本地生活商户券营销与交易平台（面向万级并发）

> 状态：执行中（M0、M1、M2 已完成）。本文件是 V2 的唯一执行入口，供后续会话逐里程碑执行。
> 依据：2026-09-19 对 `src/main` 实际代码的走查，**不依赖** `docs/` 下旧文档；旧文档与代码冲突时以代码为准。
> 进度：每完成一个里程碑，在「进度表」打勾并填 tag 与实测数字。

## 0. 怎么用这份计划

### 提示词 A：执行里程碑（每个会话只做一个里程碑）

```text
读 docs/plan/v2-high-concurrency-plan.md，执行里程碑 M<n>。

要求：
1. 先看第 5 节进度表确认前置里程碑已完成；再读本里程碑引用的源码，确认计划描述的现状仍然成立，不成立的地方先告诉我再动手。
2. 以代码为准，不要参考 docs/ 下的旧文档。
3. 按第 2 节 Git 规范：从 v2/main 拉 v2/m<n>-<slug> 分支；TDD，先提交失败测试再提交实现；每类改动独立提交；性能类提交的 message 带前后实测数字。
4. 每个提交前 mvn test 必须全绿。数字只填真实跑出来的，达不到目标就如实记录并分析瓶颈。
5. 完成后：写本里程碑的 ADR（docs/adr/），更新进度表（状态、tag、实测数字），--no-ff 合回 v2/main 并打 tag v2.0-m<n>。不要 push。
6. 遇到计划没覆盖的设计取舍，给我推荐方案和理由后继续做；只有会删除无法找回的东西时才先问我。
7. 长测试分工：预计运行超过 10 分钟的测试（阶梯压测、故障演练、全链路场景）不要由你运行和监控，由我手动启动。你负责：
   a. 提供单一入口脚本 scripts/bench.sh <场景名>，必须可无人值守运行：
      - 自己起停隔离环境、预热、按场景参数跑完、结束后自动清理容器；
      - 每个阶段有超时，任何一步失败立即停止并把原因写入结果目录，不要卡死；
      - 结果写到 benchmark/v2/m<n>/<时间戳>/，包含 manifest.json（git commit、场景参数、分核方式、机器负载）、
        status（RUNNING/DONE/FAILED + 失败原因）、各阶段原始数据、run.log；
      - 启动前检查端口占用、磁盘空间、工作区是否有未提交改动（有则拒绝运行，保证结果能对应到 commit）。
   b. 用缩小参数（1–2 分钟）把脚本完整冒烟跑通一遍，确认结果目录结构正确、status=DONE。
   c. 冒烟通过后停下来，告诉我：完整启动命令（含 nohup）、预计耗时、跑完后该把哪个目录交给你。
      依赖长测结果的部分（填实测数字、写性能结论）留到长测之后的会话，不要用冒烟数据冒充正式结果；
      合并与打 tag 默认等长测收尾会话；M6 例外（见下方「测试节奏」）。
```

里程碑专属追加：

- M0（首次执行）开头加：`开工前先把 docs/learning 下未提交、未跟踪的文件用一个提交收进去，在该提交上打 tag v1-final，再拉 v2/main。`
- M1 末尾加：`时间盒 3 天工作量。ES 升级卡住就按计划里的退路处理；整体做不下来就保留分支、告诉我结论。`
- M6 末尾加：`长测并入 M8 那一晚，本会话只做冒烟；功能测试全绿且冒烟正常即可合并打 tag，数字栏留空。`
- M8 末尾加：`长测场景同时包含对 v2.0-m5 的同场对照（验证 M6 分片无吞吐回退）和多实例全链路场景。`

### 提示词 B：长测跑完后的收尾会话

```text
读 docs/plan/v2-high-concurrency-plan.md，继续里程碑 M<n> 的收尾。
长测已由我跑完，结果在 benchmark/v2/m<n>/<时间戳>/。
1. 先看 status 和 manifest.json：不是 DONE，或 commit 与当前分支 HEAD 不一致，先告诉我原因和是否需要重跑，不要硬分析。
2. 结果有效则：生成 summary.csv，与 M0 基线对比，结合火焰图定位当前瓶颈，写结论。
3. 结论指向需要继续优化，就列出改动建议和预期收益让我决定；否则完成 ADR、进度表、合并和打 tag。
```

### 提示词 C：会话中断后续做

```text
读 docs/plan/v2-high-concurrency-plan.md，继续里程碑 M<n>。
先看 git log v2/main..HEAD 和 git status 判断做到哪一步，列出剩余任务后继续，规则同提示词 A。
```

### 提示词 D：里程碑结束后的自检

```text
针对刚完成的 M<n>，扮演面试官向我连续追问 10 个问题（设计取舍、故障场景、数字来源三个角度），
我逐个回答，你指出答错或答不上的地方，并告诉我该去读哪段代码。
```

### 测试节奏

- **功能测试（`mvn test`、集成测试、1–2 分钟冒烟）**：AI 每个提交都跑，不可攒。冒烟数字只用于判断方向，不写进任何结论。
- **长压测**：沿用 M0–M5 已经跑通的做法——每个带性能影响的里程碑 = 会话 A（实现 + 场景脚本 + 冒烟）→ 我手动启动长测 → 会话 B（提示词 B：与上一个 tag 同场对照、分析、回填进度表、合并打 tag）。
- **剩余里程碑的长测合并为一晚**：M6（分片后吞吐有无回退，对照 `v2.0-m5`）与 M8（多实例全链路：10 万用户抢 1000 库存，30% 不支付触发关单回补后再次抢完）放在同一次运行里。因此 M6 功能测试全绿 + 冒烟正常即可合并打 tag，数字栏留空，M8 长测后统一回填。M6b、M7 不需要长测。
- 剩余顺序：M5 收尾 → M6 → M8（含合并长测）→ M6b / M7 视时间取舍 → M9。

三条硬规则：

1. **数字只写测出来的。** 本计划里的吞吐目标是「待验证目标」，达不到就如实记录并写瓶颈分析——“为什么没达到”本身是面试素材。
2. **做减法。** 本项目没有真实线上存量数据，所有“兼容旧版本数据”的代码（legacy Set、启动回填 runner 等）可以直接删，不要为不存在的历史包袱保留复杂度。
3. **每个设计决策能白板讲清。** 每个里程碑结束写一页 ADR（`docs/adr/NNNN-标题.md`：背景 / 备选 / 决策 / 代价），写不出来说明没理解，不要进下一个里程碑。

## 1. 现状诊断（基于代码）

| # | 位置 | 问题 | 量级放大后的后果 |
| --- | --- | --- | --- |
| D1 | `SeckillOrderProducer.executeLocalTransaction` | Lua 准入放在事务消息的本地事务里执行，**每个请求（含 99% 抢不到的）都先向 Broker 发一条 half message** | 10 万请求抢 100 件 = 99,900 条 half message + rollback，Broker 先于 Redis 被打垮 |
| D2 | `VoucherOrderServiceImpl.seckillVoucher` | `redisIdWorker.nextId` 在准入**之前**调用，失败者也消耗一次 Redis INCR；`SeckillTrafficGuard` 又是一次独立 Lua | 热路径 3 次 Redis RTT + 2 次 Broker RTT，其中大部分花在注定失败的请求上 |
| D3 | `SeckillOrderConsumer.onMessage` | 单条消费；每条消息：Redisson 锁(≥2 RTT) + 校验 Lua + 事务(insert + update) + markSuccess Lua | 实测约 75 单/s（1000 单 drain 13s）；万级成功单需要分钟级才能落库 |
| D4 | `createVoucherOrder` | 每单一次 `UPDATE tb_seckill_voucher SET stock=stock-1`，同一行 | InnoDB 热点行锁排队，消费线程加得越多越慢 |
| D5 | `seckill_check.lua` 的 KEYS | 一次 Lua 同时操作 `seckill:stock:{v}`、`seckill:order:status:{orderId}`、全局 ZSET `seckill:order:processing` | 跨 slot，**无法运行在 Redis Cluster 上**；单 key 库存也是单分片热点 |
| D6 | `tb_voucher_order` + `uk(user_id, voucher_id)` | 订单创建即终点，`status/pay_time/refund_time` 从未被写；唯一键导致取消后无法再买 | “抢到不付款怎么办”答不上；无支付、关单、退款、核销 |
| D7 | 单库单表、`RedisIdWorker` | 订单无分片键设计，ID 依赖 Redis | 订单表无法水平扩展；Redis 抖动直接影响发号 |
| D8 | 全部证据为单实例、单 Redis | 多实例调度锁、故障转移均无验证 | “分布式”没有证据；Redis 主从切换丢预占的后果未知 |
| D9 | 压测方法 | JMeter 1000 线程 / 5s ramp-up，闭环模型，208 req/s 是发压速率不是系统容量 | 没有拐点、没有瓶颈定位、没有调优前后对比 |
| D10 | Java 8 / Boot 2.3.12 / ES client 7.6 | 2026 年明显过时；Tomcat 默认线程模型 | 减分项，且无法用虚拟线程做对比实验 |
| D12 | `application.yaml` 9 个 `*_ENABLED:false` | 对账器、补偿、点赞 Outbox 写入/worker、热榜读/刷新、批量发券 worker、通知 worker **默认全部关闭**；`BlogServiceImpl`、`BlogHotRankService` 等保留新旧双路径 | 默认启动跑的是旧逻辑，亮点功能实际不生效；双路径使代码量和测试翻倍，面试演示时容易露馅 |
| D13 | 仓库整体 | 文档 108 个文件约 1.27 万行，多于 service 层代码（约 7800 行）；22 个按阶段命名的脚本（`m5a…m6c`）、3 个 compose、约 580 行只为“从旧版数据切换”而存在的代码 | 读者找不到重点；过程性材料淹没了结果 |
| D11 | 仓库名 `hm-dianping`、README 首屏写“基于黑马点评” | 第一眼仍是黑马点评 | 简历筛选阶段被归类 |

保留并复用的资产（不要重写）：准入 Lua 的活动元数据/时间校验思路、`PROCESSING→SUCCESS/FAILED` 状态与对账器的“DB 异常≠无单”分类、点赞 Outbox、营销 Grant/Batch、RBAC 与商户隔离、Flyway、Micrometer 指标、321 个测试（重构的安全网）。

## 1.5 产品定位与各链路取舍

### 定位：本地生活「商户券营销与交易平台」

不再是“点评 App 的后端”，而是**面向商户的券营销 SaaS 后端**：商户在后台建店、配券、办活动（秒杀 / 定向发放 / 签到奖励 / 批量发放），用户领券或购券，到店核销，平台与商户对账。一句话讲清业务闭环：

```text
商户建活动 → 用户抢/领/买 → 形成券资产 → 到店核销 → 退款/过期 → 日终对账
```

选这个定位的原因：项目里最不像教程的三块（多租户 RBAC、营销 Grant/Batch、秒杀恢复链路）恰好都属于它，只差“订单闭环 + 券资产 + 核销”就能连成一条线；而最像教程的部分（关注、Feed、探店笔记）都落在定位之外，可以名正言顺地删或冻结。不加新方向，只补断点。

### 各链路结论

| 链路 | 结论 | 理由与动作 |
| --- | --- | --- |
| 秒杀 + 订单 | **重构（主线）** | M2–M6 |
| 营销：活动 / 标签 / Grant / 批量 Job / 通知 Outbox | **保留并接入主线** | 设计已经成熟，不重写。唯一硬伤：`tb_voucher_grant` 只有“发放”事实，**没有使用状态、有效期、核销**——发出去的券不能用；且它与秒杀订单是两套互不相干的“券”。在 M2 统一为券资产（见下） |
| 商户后台 RBAC / 多租户隔离 | **保留，小幅扩展** | 加订单管理、核销、对账单三个后台入口，全部复用现有 permission + merchant scope；加后台操作审计日志（谁在何时对哪个活动/订单做了什么，一张表 + 一个切面） |
| 点赞 Outbox + 热榜 | **保留、冻结** | 设计本身是好的，M0 删掉旧 toggle 双路径后不再投入。简历降为备选条目，不进前三条——“点赞”“热榜”是黑马关键词 |
| 店铺缓存 / GEO / ES 搜索 | **保留**，M7 做多级缓存 | ES 链路会被 M6 的商户订单读模型复用 |
| 登录（短信码 + Redis token） | **保留不动** | 够用；为换 JWT 而换没有业务理由 |
| WebSocket 通知 | **保留不动** | 已经是 Redis pub/sub 扇出，多实例可用；M8 顺带验证 |
| 关注 / 共同关注 / Feed 推送 / 关注流 | **删除** | 纯教程演示（Set 交集、ZSet 推模式），与定位无关；推模式在大 V 场景本身就是错的，要改对得做推拉结合，投入大且偏离主线。删：`FollowController`、`FollowServiceImpl`、`BlogServiceImpl` 的 feed 推送与 `/blog/of/follow`、对应表与前端入口 |
| 其他教程残留 | **删除** | 空的 `BlogCommentsController`；`/shop/of/name`（与 `/shop/search` 重复）；旧 toggle 接口 `PUT /blog/like/{id}`；`/user/sign/count` 若已被 M6B 签到任务取代则一并删 |

### 值得新增的功能（只有这三个，都是补业务断点）

1. **券资产统一（并入 M2，必做）**：新表 `user_coupon`（`coupon_no`、`user_id`、`voucher_id`、`merchant_id`、`source`=`PURCHASE/CLAIM/ADMIN_GRANT/TASK_REWARD/BATCH_GRANT`、`source_ref`=订单号或 grant id、`status`=`AVAILABLE/USED/EXPIRED/REFUNDED/FROZEN`、`valid_from/valid_to`、`verify_code`）。秒杀订单支付成功 → 发一张；营销 Grant 成功 → 同事务发一张。核销、过期、退款都只操作这一张表。`tb_voucher_grant` 保留为“发放流水”（幂等与额度的依据），`user_coupon` 是“资产”。
2. **核销（并入 M2，必做）**：商户端凭 `verify_code` 核销，CAS `AVAILABLE→USED`，校验券属于本商户（复用 merchant scope）；核销码防枚举（足够长的随机串 + 商户维度限频）。过期由定时任务批量置 `EXPIRED`。
3. **日终对账（新增 M6b，可选，约 2 天）**：按商户、按日汇总 订单 / 支付流水 / 退款 / 核销，生成对账单；并做一次**流水核对**——`payment_record` 与 `trade_order` 逐笔比对，产出差异单（有支付无订单、金额不一致、已关单却已支付未退款）。这是交易系统面试里区分度很高的话题，而且能反过来检验 M2 的竞态处理是否真的没漏。

### 明确不做（被问到时能说出理由）

- **拆微服务 / 上网关 / 注册中心**：人人都拆，拆完只多出运维复杂度，没有任何一个一致性或性能问题因此变得更有深度。保持模块化单体，把包结构按域整理清楚（`trade`、`marketing`、`merchant`、`content`、`platform`），能讲出“将来沿哪条边界拆、拆之前要先解决什么”即可。
- 推荐、IM、评论体系、积分商城、AI 客服等：与闭环无关的广度。
- 真实支付渠道接入：mock 渠道 + 完整的回调/幂等/对账语义已经覆盖全部面试考点。

## 2. Git 规范

- 先处理工作区：当前 `docs/learning/**` 有未提交、未跟踪的笔记，开工前用一个 `docs: snapshot v1 learning notes` 提交全部收进去（它们会在 M0 被删除，先提交才能从 `v1-final` 找回），不要混进 V2 提交。
- 集成分支：先在当前 HEAD 打 tag `v1-final`（瘦身删除的一切都能从这里找回），再拉 `v2/main`；每个里程碑一个分支 `v2/m<n>-<slug>`，完成后 `--no-ff` 合回 `v2/main` 并打 tag `v2.0-m<n>`。
- Conventional Commits，scope 用业务域：`feat(order)`, `perf(seckill)`, `refactor(redis)`, `test(order)`, `bench(seckill)`, `docs(adr)`, `build`, `chore`。
- TDD 留痕：同一功能至少两个提交——`test(x): ...（红）` 在前，`feat/fix(x): ...（绿）` 在后。
- 性能优化提交的 message body 必须带前后数字，例：

  ```text
  perf(seckill): batch-consume orders and merge stock decrement

  before: 78 orders/s persisted (single consume, per-row stock update)
  after:  <实测> orders/s (batch=64, one stock update per voucher per batch)
  bench:  benchmark/v2/m4/summary.csv @ <commit>
  ```

- 压测原始数据（JTL、火焰图 html）放 `benchmark/v2/m<n>/`，该目录 gitignore；只提交 `summary.csv` 与结论 md。
- 不 rewrite 已推送历史；不 force push。

## 3. 目标架构

```mermaid
flowchart LR
    C[客户端] --> N[nginx\nlimit_req + 静态资源]
    N --> A1[app-1] & A2[app-2]
    subgraph 准入漏斗 每层只放行下一层需要的量
      A1 --> L1[L1 本地售罄标记\n0 RTT]
      L1 --> L2[L2 本地令牌桶\n0 RTT]
      L2 --> L3[L3 Redis Lua\n限流+判重+分桶扣减+写预占 1 RTT]
    end
    L3 -->|仅成功者| MQ[RocketMQ 5.x]
    MQ --> B[批量消费\n批量 insert + 合并扣库存]
    B --> DB[(MySQL 订单分片\nuser_id 基因)]
    DB --> CDC[Canal] --> ES[(ES 商户侧订单读模型)]
    MQ -. 延迟消息 .-> CLOSE[超时关单 → 回补库存]
    PAY[支付回调 mock] --> DB
    REC[对账器] --> DB & R[(Redis Cluster\n{hash tag} 分桶)]
    L3 --> R
```

核心思想一句话：**让 99% 注定失败的请求在最便宜的层死掉，让 1% 成功的请求以批量方式落库，Redis 只做可丢的准入层，MySQL 条件更新做最后防线。**

## 4. 里程碑

### M0 基线、瘦身与压测工具（约 2 天）

- 任务
  - 拉 `v2/main`，然后按下面「瘦身清单」做减法。每类一个独立提交（`chore(cleanup): ...` / `refactor(x): remove legacy ...`），删除前先 `grep` 确认无引用，删除后 `mvn test` 必须全绿；被删代码对应的测试一并删除，不要留下只测已删逻辑的测试。
  - 建开环压测工具：k6（`constant-arrival-rate`）或 wrk2，脚本入 `benchmark/v2/scripts/`；预生成 N 万用户 token 的 fixture 脚本。压测机与被测应用用 `taskset`/cgroup 分核，记录分核方式。
  - 接入 async-profiler，产出火焰图的脚本化命令。
  - GitHub Actions：`mvn test` + Testcontainers（MySQL/Redis）。
  - **在不改任何业务代码的前提下**跑阶梯加压（500→1k→2k→5k→1w req/s），记录准入接口拐点、消费落库速率、各层 CPU。这是全部后续优化的对照组。
- 验收：`benchmark/v2/m0/summary.csv` + 一页瓶颈分析（预期能直接看到 D1/D3/D4）；瘦身前后的文件数/行数对比写进合并提交的 message。

#### M0 瘦身清单

判断标准：**没有真实线上存量数据，所以一切“为了从旧版本平滑切换”而存在的东西都是冗余**；一个功能只保留一条链路；过程性材料不进主干。

1. 冗余链路（代码）
   - 秒杀旧链路：`lua/seckill.lua`（Redis Stream 版）及 `RedisLuaScript` 中的引用；`seckill_check.lua` / `seckill_compensate.lua` 里的 legacy purchased-user Set 分支与 `RedisConstants` 对应常量。
   - 秒杀升级工具：`SeckillProcessingIndexBackfillRunner`、`lua/seckill_reconcile_backfill.lua`、`lua/seckill_voucher_backfill.lua` 中仅服务旧数据的部分、`SeckillProperties` 的 `backfill-on-startup` 及互斥校验。
   - 点赞切换工具：`BlogLikeLegacyImportService`、`BlogLikeCutoverService`、`BlogLikeCutoverGuard`、`BlogLikeLegacyBackfillRunner`、`BlogLikeProperties.legacyBackfillOnStartup`。
   - **消灭新旧双路径（D12）**：删除 `BlogServiceImpl` 中 `!isWriteEnabled()` 的旧 toggle 点赞分支、`BlogHotRankService` 中 `!isReadEnabled()` 的旧查询分支，新链路成为唯一实现。开关只保留真正的运维开关（worker 启停、补偿审批），且**默认值改为开启**，让 `mvn spring-boot:run` 默认跑的就是要展示的系统。
   - 定位之外的链路（见 1.5）：关注 / 共同关注 / Feed 推送 / 关注流整条删除（controller、service、mapper、entity、表用新 Flyway 版本 `DROP`、用户端前端入口）；空的 `BlogCommentsController`；`/shop/of/name`；旧 toggle 接口 `PUT /blog/like/{id}`。
   - 包结构按域整理：`trade`、`marketing`、`merchant`、`content`、`platform`（认证、限流、观测、缓存等横切）。纯移动提交，不夹带逻辑改动，方便 review。
   - 教程残留：`CacheClient` 中未被调用的方法（逐个 `grep` 调用方后删除）、未使用的 DTO/常量/工具类；拼写错误的包名 `interctptor` → `interceptor`。
   - 隔离区（quarantine）与 orderId 冲突分支：M3 改用本地 Snowflake 后重新评估——若 orderId 碰撞在新发号方案下不可能发生，连同 `seckill_reconcile_quarantine.lua`、`seckill_reconcile_defer_unresolved.lua` 一起删，写进 ADR。防御不可能发生的故障不是亮点，是噪音。
2. 冗余脚本与配置
   - `scripts/` 下按阶段命名的 22 个脚本（`m5a-*`…`m6c-*`、`check-m*-frontend.js`、`run-m5*`）：合并为 `scripts/stack.sh`（起停隔离环境）、`scripts/bench.sh`、`scripts/check-docs.py` 三个，其余删除。
   - `docker-compose.pre-m8.yml` 并入主 compose 的 profile；主 compose 补上 RocketMQ（现在主 compose 不含 Broker，Quick Start 跑不通完整链路）。
   - `docs/testing/*.jmx` 两份旧 JMeter 计划在 k6 脚本就绪后删除。
3. 冗余结果与杂物
   - 工作区根目录未跟踪的 `秒杀抢购.jmx`、`jmeter.log`、`note/`、`benchmark/` 下 Redis Stream 时代的 JTL/日志：本地删除（Stream 链路已不存在，数据无对照价值）。确认 `.gitignore` 覆盖 `*-data/`、`target/`、`benchmark/**/raw`。
   - `figure/`：只留 README 实际引用的图。
4. 冗余文档（此处先归档，M9 再重写）
   - `docs/archive/`（60 文件）、`docs/evidence/`（27 文件）、`docs/modernization-roadmap.md`、`docs/guides/project-demo.md`：整体 `git rm`。它们在 Git 历史与 tag `v1-final`（瘦身前先打）里永久可查，不需要留在主干。
   - `docs/design/` 4 篇：与 V2 冲突的部分随对应里程碑重写为 ADR，M9 时删除原文。
   - `docs/learning/`（含未跟踪的 `10-seckill.md`、`flow-notes/`、`interview-deep-dive/`、`prompt-kit/`）：**整体删除**。V2 完成后按新实现重新总结，旧笔记描述的是将被重构掉的系统，留着只会误导。注意其中有未提交、未跟踪的文件：先把它们提交进 `v1-final` tag 之前的最后一个提交，再删除，保证可找回。
   - 目标：`docs/` 最终只有 `README.md`（索引）、`adr/`、`plan/`、`benchmark-report.md`、`runbook.md`，总量控制在 2000 行内。

### M1 Java 21 + Spring Boot 3.x（时间盒 3 天）

- 理由：后面每个里程碑都会新增代码，先升级避免迁移两次；虚拟线程是 M8 的对比实验素材。
- 任务：Boot 3.3+/Java 21；`javax→jakarta`；MyBatis-Plus 3.5.x boot3 starter；rocketmq-spring 2.3.x；Redisson 对应 starter；mysql-connector-j；Flyway 升级；ES 升到 8.x 镜像（带 IK）+ 新 Java client。
- 风险与退路：ES 是最大风险点。若 ES 卡住超过 1 天，先把搜索收敛到一个接口后面并临时用低级 RestClient；若整体超出时间盒，保留分支、回到 Java 8 继续 M2（后续设计不依赖 Java 21）。
- 验收：原有测试全绿；M0 同参数复测无回退。

### M2 订单域重建：支付、超时关单、退款、券资产与核销（约 6 天，面试必问，P0）

- 范围补充（见 1.5）：新增 `user_coupon` 券资产表；支付成功与营销 Grant 成功都在各自事务内发券资产（改 `VoucherGrantTransactionService`，一处改动覆盖领取 / 后台发放 / 签到奖励 / 批量发放四个入口）；退款成功 → 券 `REFUNDED`，已 `USED` 的券拒绝退款；过期任务批量置 `EXPIRED`。后台新增订单列表、核销、操作审计日志。

- 表设计（新 Flyway 版本，不改历史迁移）
  - `trade_order`：`order_no`(PK)、`user_id`、`voucher_id`、`shop_id`、`merchant_id`、`amount`、`status`、`expire_at`、`version`、时间戳。
    - 限购唯一键：生成列 `active_flag = IF(status IN (CLOSED, REFUNDED), NULL, 1)`，`uk(user_id, voucher_id, active_flag)`。利用 MySQL 唯一索引允许多个 NULL：进行中订单唯一，取消/退款后可再次购买。
    - 索引：`idx(status, expire_at)` 供关单兜底扫描。
  - `order_state_log`（状态流转审计）、`payment_record`（`pay_no` 唯一、`channel_txn_no` 唯一）、`refund_record`（`refund_no` 唯一）、`user_coupon`（券资产，字段见 1.5，`coupon_no` 与 `verify_code` 唯一）。
  - 旧 `tb_voucher_order` 数据迁移脚本 + 删除旧表。
- 状态机：`PENDING_PAY → PAID → USED`；`PENDING_PAY → CLOSED`；`PAID → REFUNDING → REFUNDED`。所有流转用 `UPDATE ... WHERE order_no=? AND status=?` CAS，影响行数为 0 即并发失败方；集中在一个 `OrderStateMachine` 类，禁止散落的 `setStatus`。
- 超时关单：下单落库成功后发 RocketMQ 5.x 定时消息（Broker 升 5.x；任意时长定时）；消费者 CAS `PENDING_PAY→CLOSED` 成功才回补（DB `stock+1` 同事务；Redis 回补走已有的精确补偿 Lua 思路，幂等键为 order_no）。兜底：定时任务扫 `idx(status, expire_at)`，防延迟消息丢失。
- 支付（mock 渠道，独立 controller 模拟三方）：预下单生成 `pay_no`；异步回调带 HMAC 签名、会重试、会乱序；回调处理以 `channel_txn_no` 幂等。
- 必须覆盖的竞态（先写测试）
  1. 支付回调与超时关单同时到达：CAS 只有一方成功；关单赢则对迟到的支付成功回调**自动发起退款**。
  2. 回调重复 N 次：只入账一次。
  3. 关单消息重复：库存只回补一次。
  4. 退款中再次申请退款；已核销后申请退款被拒。
  5. 关单后同一用户可再次抢购（验证生成列唯一键）。
- 核销：商户后台扫码核销接口，复用现有 RBAC + merchant scope。
- 验收：上述 5 类并发 IT 全绿；状态机图入 README。

### M3 秒杀热路径重构：准入漏斗（约 4 天）

- 设计
  1. **L1 本地售罄标记**：JVM 内 `ConcurrentHashMap<voucherId, soldOut>`；Lua 返回库存不足时置位，并经 Redis pub/sub 广播给其他实例；库存回补（关单/补偿）时广播清除。售罄后请求 0 次网络 IO。
  2. **L2 本地令牌桶**：每实例按「剩余库存 × 系数」放行，超出直接返回排队失败。替代现在独立的 `SeckillTrafficGuard` Redis 往返中的活动级限流；用户级/IP 级限频并入 L3 Lua。
  3. **L3 单次 Lua**：限频 + 活动状态 + 判重 + 扣减 + 写预占，一个 RTT。
  4. **订单号改本地 Snowflake**（workerId 启动时从 Redis 租约获取），且**只在 Lua 成功后**才需要——改为 Lua 成功后生成会破坏“Lua 内写 orderId”，因此做法是：本地发号零 RTT，仍在 Lua 前生成。订单号低位嵌入 `user_id % 1024` 基因，为 M6 分片做准备。
  5. **只有成功者才碰 Broker**。对 D1 做 A/B 两个方案并用数据决策，写 ADR：
     - A：保留事务消息，但 L1–L3 前置，Lua 成功后才发 half message（本地事务只做确认）。
     - B：Lua 成功 → 普通同步发送；发送失败/进程崩溃由对账器从 PROCESSING 索引**重驱动**（预占本身就是 outbox）。
     - 预期 B 更快更简单；若选 B，必须补“Lua 成功后进程立刻被 kill”的故障测试证明订单最终收敛。
  6. nginx 层 `limit_req` + 秒杀令牌：活动开始后才下发、与 user 绑定的短期 token，拦脚本与提前请求。
- 验收：同机同参数对比 M0——准入接口拐点 QPS、P99、Broker TPS 占用（重点展示 half message 数量从“=请求数”降到“=成功数”）。待验证目标：单实例准入 ≥ 1 万 req/s。

### M4 消费侧吞吐：批量落库与热点行消除（约 3 天）

- 设计
  - 改用 `DefaultMQPushConsumer` 批量监听（`consumeMessageBatchMaxSize=64`，消费线程数按 DB 连接池定）。
  - 一批内：按 voucher 分组 → 多值 `INSERT IGNORE` 批量插入 → 依据实际插入行数 `UPDATE stock = stock - n WHERE stock >= n`（**每批每券一次**，消除 D4）→ pipeline 批量 markSuccess。
  - 部分失败处理：`INSERT IGNORE` 被忽略的行逐条走现有分类逻辑（幂等重放 / 冲突）；库存不足时退化为逐条处理。批失败整体重试必须幂等（靠唯一键）。
  - 去掉消费路径的每消息 Redisson 锁：消费者与对账器的互斥改为 Redis 内状态认领（`PROCESSING → PERSISTING(owner, lease)` 的 Lua CAS，带租约超时），对账器只处理无人认领或租约过期的单。用并发测试证明“消费者落库中 + 对账器到期”不会误补偿。
  - HikariCP、`rewriteBatchedStatements=true`、消费线程数做参数扫描。
- 验收：落库速率前后对比（M0 约 75 单/s → 待验证目标 ≥ 3000 单/s）；火焰图证明热点从锁等待转移；全部一致性 IT 仍绿。

### M5 Redis Cluster 化与库存分桶、故障演练（约 4 天）

- Key 重设计（解决 D5）：
  - 库存拆 K 桶：`sk:{v17:b3}:stock`、`sk:{v17:b3}:resv`、`sk:{v17:b3}:status`（订单状态收进桶内 Hash，field=orderId）、`sk:{v17:b3}:processing`（桶内 ZSET）。同一 Lua 的所有 key 共享 hash tag → 同 slot。
  - 路由：`bucket = hash(userId) % K`。同一用户永远落同一桶，所以**判重天然原子**，不需要跨桶。
  - 桶空：返回库存不足前，应用层最多再试 1–2 个相邻桶的“借库存”Lua（只扣库存，预占仍写回用户本桶——两步用幂等 token 串联，失败由对账器归还）。先实现不借库存的简单版并量化尾部库存浪费，再决定是否值得做借库存（写 ADR）。
  - 对账器按桶扫描，天然可多实例分片并行。
- 部署：compose 起 3 主 3 从 Cluster；Lettuce 开拓扑刷新。
- 故障演练（本项目最有辨识度的证据）：
  - 压测中 kill 一个 master：异步复制会丢最近的预占与扣减 → 新主库存“变多”→ Redis 层超放。**验证 MySQL `stock >= n` 条件更新作为最后防线保持零超卖**，超放请求走补偿 + 用户可见失败；统计丢失预占数与收敛时间。
  - 结论写清：Redis 层是「可丢的性能层」，正确性不依赖它；给出 RPO 实测值。
- 验收：Cluster 下全量 IT 绿；故障演练报告含时间线与数字。

### M6 订单分库分表与商户侧读模型（约 4 天）

- **M6 开头先做的两件事（2026-09-20 决定）**：
  1. 补上 M4 → M5 一路欠着的仪表：`local_deals.seckill.consume.batch.size{stage=delivered|persisted}`
     与 `local_deals.seckill.consume.degraded{reason}`；`scripts/bench.sh` 每轮归档 actuator 快照，
     `app.log` 改为轮转而不是覆盖；`summary.csv` 增加 `batch_mean` 列。**已完成。**
  2. 仪表当场推翻了一个结论：**批大小实测约 1.03**（`batchSize=64`，`persisted == delivered`，
     退化计数为 0）。也就是说「落库 ≈ 80 次提交/s × 批大小」里卡住的是**批大小**，不是提交速率。
     成因：16 个消费线程的处理能力（约 1300 次/s）本来就跟得上到达速率（1000–2000/s），
     队列里永远没有第二条消息在等，RocketMQ 自然只能一次交一条。同一次运行的 Redis 宕机轮里
     积压形成了，批大小立刻到 max 32——机制被看到了两次。
     因此**先扫消费参数（`pull-interval` / `pull-batch-size` / 线程数），再决定要不要拆 MySQL 热点行**。
     **已完成**（`scripts/bench.sh m6-consume`，结果
     [`benchmark/v2/m6/20260920-215408-m6-consume`](../../benchmark/v2/m6/20260920-215408-m6-consume)，
     `status=DONE`，commit `f35f34d`，分析见 [M6 消费参数扫描](../../benchmark/v2/m6/consume-sweep.md)）：
     - **拉取间隔确实填批**：`batch_mean` 2.16（0 ms）→ 3.90（10 ms）→ 6.53（20 ms）→ 12.68（50 ms）；
       其中 0 ms → 10 ms 两行只差这一个旋钮（生效 pullBatchSize 都是 64）。
     - **消费默认值改为 `256:4:50ms:256`**（`batch-size` / `thread-count` / `pull-interval` /
       `pull-batch-size`）：2001.5 单/s、负载后 0.1 s 排空、批 12.68，而当时的默认值
       `64:16:0:32` 在同一场次两轮分别是 944.6 / 11.6 s 与 432.9 / 30.7 s。整组一起改，
       因为只有整组被测过；四项里只有拉取间隔有干净的单旋钮证据。
     - **2001.5 单/s 是下限不是上限**：场景只发 2000 req/s，负载停止时已落完，本场景测不出
       更高的数字。上限要靠更高的 `S_DRAIN_RATE` 去量，并入 M8 那一晚。
     - **MySQL 热点行 `tb_seckill_voucher` 决定不拆**（ADR 0007 决策 4）。理由：那 80 次提交/s
       是双稳态的慢档不是那一行的能力——同一轮里同一行被量到约 394 次/s 与约 73 次/s 两段；
       新默认值下那一行每秒只被要求约 150 次提交，当前的约束是到达速率。回归条件：到达速率
       远高于 2000/s、`drain_s_after_load` 重新大于 0、且 `persist_orders_per_s ÷ batch_mean`
       不再随负载上升——挂在 M8 的高速率落库轮上。

- ShardingSphere-JDBC：`trade_order` 及其附属表按 `user_id` 分片（本地 2 库 × 4 表）；`order_no` 含 user 基因，所以按 `order_no` 查与按 `user_id` 查都能单分片命中，避免广播。
- 限购唯一键 `uk(user_id, voucher_id, active_flag)` 含分片键，分片内唯一即全局唯一——讲清为什么这成立。
- 商户/平台侧“按店铺查订单”不走分片库：Canal → RocketMQ → ES 订单索引（复用现有 `EsSyncConsumer` 模式，并把 Canal 链路补成真正的端到端测试，解决旧文档里的 consumer-level 遗留）。
- 关单兜底扫描按分片并行；对账器读主库（Hint 强制）。
- `tb_seckill_voucher` 等低频表设为单库表/广播表。
- 验收：分片路由单测（按 order_no / user_id 均单分片）；跨分片限购并发 IT；商户订单列表端到端延迟实测。

### M6b 日终对账（约 2 天，可选）

- 按商户、按业务日汇总订单 / 支付 / 退款 / 核销生成 `settlement_daily`；`payment_record` 与 `trade_order` 逐笔核对，差异写 `reconcile_diff`（类型：有支付无订单、金额不一致、已关单已支付未退款、重复支付）。
- 分片环境下按分片并行扫描，按业务日 + 商户幂等重跑。
- 测试：人为注入四类差异，对账任务必须全部检出；无差异日重复运行结果不变。
- 后台：商户只能看到本商户对账单（复用 merchant scope）。

### M7 读路径：多级缓存与热 key（约 2 天，可裁剪）

- 店铺详情/券列表：Caffeine(L1) + Redis(L2)，失效经 Canal→MQ 广播到各实例；保留现有逻辑过期与互斥重建。
- 秒杀活动页：活动元数据进本地缓存，库存展示走最终一致（定时拉取），不读写路径 key。
- 验收：读接口拐点前后对比；失效传播延迟实测。

### M8 多实例全链路压测与可观测（约 3 天）

- nginx + 2–3 实例 + Redis Cluster + RocketMQ 5.x + 分片库，全链路场景：10 万用户抢 1000 库存；其中 30% 不支付触发关单回补，被回补库存再次被抢完。最终核对：成功单数 = 库存、零超卖、零重复、预占/订单/库存三方对账一致。
- 实例数 1→2→3 的吞吐扩展曲线（证明水平扩展，或找出不线性的原因）。
- **M5 留下的一次演练**：Redis 主从切换的 RPO 实测为 0（转移期间 slot 不可写，买家收到 503 而不是假的成功），
  所以「Redis 超放 → MySQL `stock >= n` 兜底」这条防线**至今没有被真实触发过**。M8 要先用 `CLIENT PAUSE`
  或断开复制链路制造几秒复制空窗，再杀 master，把超放逼出来，量化丢失预占数与用户可见失败。
- 虚拟线程 on/off 对比实验（Boot 3 `spring.threads.virtual.enabled`），如实写结论，哪怕结论是“瓶颈不在线程模型，收益有限”。
- Grafana 看板（漏斗各层拒绝数、Lua 延迟、MQ lag、批量大小分布、关单延迟）、traceId 贯穿 HTTP→MQ→DB，导出看板 JSON 入库。
- 验收：`docs/benchmark-report.md`——方法（开环、分核、预热）、逐里程碑对比表、瓶颈演进叙事、已知边界。

### M9 包装与笔记总结（最后做）

- 仓库改名（如 `local-deals-platform`，定位语：本地生活商户券营销与交易平台），包名已是 `com.localdeals` 无需改；README 首屏：一句话定位 + 目标架构图 + 实测数字 + 故障演练结论；“源自教程原型”降到文末致谢一句。
- 文档收口：M0 已删除 V1 过程文档（历史在 tag `v1-final`）；此处删除 `docs/design/` 残留原文，确认 `docs/` 只剩 README 索引、ADR、plan、benchmark-report、runbook。
- 学习笔记总结：**要求由我在执行到这一步时给出**，执行会话不要自行设计笔记结构或提前生成。
- 简历项目描述不属于本计划，由我在普通会话中直接改写。

## 5. 进度表

| 里程碑 | 状态 | tag | 关键实测数字 | ADR |
| --- | --- | --- | --- | --- |
| M0 基线与工具 | ☑ | `v2.0-m0` | 准入拐点 ≈1 万 req/s（p99 63 ms），天花板 15.9k req/s，15k 时 Broker 先流控；落库 123 / 264 / 478 单/s（三轮，中位 264）；half message 数 = 请求数；[基线](../../benchmark/v2/m0/baseline.md) | [0001](../adr/0001-m0-cleanup-and-baseline.md) |
| M1 Java 21 / Boot 3 | ☑ | `v2.0-m1` | Java 21 + Boot 3.5.16 + ES 8.18.8；`mvn test` 311/311，隔离栈 IT 执行的 38 个全绿；同场对照 `v2.0-m0`：准入天花板 14.5k → 17.5k req/s，10k 档 p99 73.7 → 51.8 ms、CPU −20%，拐点仍 ≈1 万；落库中位 180.8 vs 155.6 单/s（区间重合，无差别）；[复测](../../benchmark/v2/m1/comparison.md) | [0002](../adr/0002-m1-java21-boot3.md) |
| M2 订单域闭环 | ☑ | `v2.0-m2` | 5 类竞态 IT 全绿且两种交错都跑到（支付/关单 20 轮：支付胜 5、关单胜 15；核销/退款 10 轮：3/7）；定时消息 3 s 超时实测关单，HTTP 端到端 20 s 超时 23 s 内关单并可再买；`mvn test` 356/356，隔离栈 IT 72 个执行全绿，营销业务 IT 17/17；落库同场 A/B 中位 189.4 vs 177.5 单/s（区间重叠，无可测回退），M2 新增开销 < 2%，44% 仍在热点行 `UPDATE`；[复测](../../benchmark/v2/m2/comparison.md) | [0003](../adr/0003-m2-order-lifecycle.md) |
| M3 准入漏斗 | ☑ | `v2.0-m3` | `mvn test` 401/401；同场对照 `v2.0-m2`：准入拐点（p99<100 ms 且丢弃<1%）5k → 20k req/s（p99 83 ms），天花板 13.2k → ≥26.2k req/s（30k 档未见顶，丢弃已由 k6 吃满 3.57 核造成）；10k 档 p99 128.2 → 32.8 ms、应用 CPU 3.04 → 0.60 核；half message = 请求数 → 0，普通消息 = 成功数；Broker 0.9 → 0.08 核、Redis 0.4 → 0.01 核；每档 accepted = stock，无超卖；落库中位 217.5 vs 154.9 单/s（区间重合，消费侧未改）；`kill -9` ×2 与「先杀 Broker 再杀应用」三方一致收敛（102.8 / 102.7 / 574.2 s，重投 4249 单全部落库）；[复测](../../benchmark/v2/m3/comparison.md) | [0004](../adr/0004-m3-admission-funnel.md) |
| M4 批量消费 | ☑ | `v2.0-m4` | `mvn test` 429/429；同场对照 `v2.0-m3`：落库中位 156.3 → 319.1 单/s（最好轮 945.2，参数扫描最优 `32:16` 1056.2，默认 `64:16` 975.3），负载后排空 105.6 → 47.7 s，阶梯 1000 单档 144.8–172.9 → 691.5–703.1 单/s；**待验证目标 ≥3000 单/s 未达成**——同一版本关掉批量（`1:16`）是 168.7 单/s，与基线同档，证明提速只来自批量，而一次提交的上限约 80 次/s，吞吐 ≈ 80 × 实际批大小；准入端 10k/20k 档无回退、half message 恒 0、`accepted = stock`；`kill -9` 收敛 102.8 → 44.7 s，先杀 Broker 572.1 s（对账器未批量化，与预期一致），两次演练预占=订单、Redis 库存=DB 库存、无残留 PROCESSING；[复测](../../benchmark/v2/m4/comparison.md) | [0005](../adr/0005-m4-batch-consumption.md) |
| M5 Cluster 分桶与故障演练 | ☑ | `v2.0-m5` | `mvn test` 442/442，隔离栈全量 IT 在真实 3 主 3 从 Cluster 上 579 个全绿；同场对照 `v2.0-m4`（对照组单节点——旧 key 无 hash tag，在 Cluster 上跑不起来）：准入 10k 档 9944 → 9956 req/s、20k 档 19661 → 19333（−1.7%），两档 `accepted = stock`、无超卖、half message 恒 0，代价是 p99 +28~45 ms；**落库无可测变化且这次量不出来**——九轮平均由消费端的双稳态决定（A 段占比 0%→94% 对应 860→190 单/s），基线与 M5 混在同一条曲线，K=1/8/64 不单调；**Redis master 宕机 ×2：丢失预占 0、库存差 0、超卖 0**，转移 5.2/7.8 s、恢复 7.3/9.4 s、收敛 40.2/31.4 s，代价约 1.26 万个 503（42% 请求）；应用 `kill -9` 收敛 44.7 → 56.8 s；尾部浪费实测 0，故不做借库存；[复测](../../benchmark/v2/m5/comparison.md) | [0006](../adr/0006-m5-cluster-buckets.md) |
| M6 分库分表与读模型 | ☐ | | | |
| M6b 日终对账（可选） | ☐ | | | |
| M7 多级缓存（可裁剪） | ☐ | | | |
| M8 全链路压测与可观测 | ☐ | | | |
| M9 包装 | ☐ | | | |

M0 执行时发现的计划偏差（详见 ADR 0001）：D12 并无旧 toggle 点赞分支（只有维护闸门，热榜 DB 查询是降级兜底，保留）；`seckill_voucher_backfill.lua` 是启动预热而非旧数据工具（保留）；`/user/sign/count` 未被 M6B 取代（保留）；根目录 `秒杀抢购.jmx`、`note/` 实为已跟踪文件；D3 的 Redisson 锁在火焰图中仅占 0.2%，消费瓶颈是热点行锁（44.7%）与连接池等待（49.5%），其中 25% 来自通知补查 merchantId 的同步 DB 查询（M4 处理）。

M1 执行时发现的计划偏差（详见 ADR 0002）：选 Boot 3.5 而不是已发布的 4.x；Broker 保持 4.9.4 到 M2；ES 退路（低级 RestClient）未启用，直接迁到 Spring Data ES 5.5；`ShopSearchAfterIT` 的真实病因是没往 MySQL 写数据，并借此修掉“类型 + 距离”搜索丢类型过滤的旧缺陷；Hutool 5.8 默认丢 JSON 数组里的 null，会让损坏的列表缓存被当成空命中；ES 8 在 90% 磁盘水位下拒绝分配分片，数据目录挪到了数据盘；M0 当天的机器状态比今天约快 10%，所以 M1 的对照组是同一场次重跑的 `v2.0-m0`。

M2 执行时发现的计划偏差（详见 ADR 0003）：`seckill_compensate.lua` 只能释放 `PROCESSING` 预占，关单/退款另写了 `seckill_release.lua`（以“预占仍指向本订单”作为天然幂等条件）；`tb_voucher` 没有有效期字段，新增 `valid_days`；`MarketingTagMemberMapper` 的“商户关系”判定也读旧订单表，改为 `trade_order.merchant_id`；旧表按计划迁移而非直接删除（V1 建单即成交，迁为 `PAID` 并补券，隔离栈 14.2 万单）；退款成功与关单一样回补库存并允许再买；后台审计用挂在 `@RequireAdminPermission` 上的切面覆盖全部非 GET 接口，而不是逐个加注解；竞态测试起初只跑到一种交错（关单 20/20、退款 10/10 胜），改为部分轮次给一方先手并断言两种结果都出现；营销业务 IT 可以用隔离栈的环境变量打开门控，17 个全绿，Flyway 升级与故障注入类仍需独立实例。

M4 执行时发现的计划偏差（详见 ADR 0005）：rocketmq-spring 的监听容器逐条投递，只调 `consumeMessageBatchMaxSize` 不会产生批量，M4 自己持有 `DefaultMQPushConsumer`；「PROCESSING → PERSISTING(owner, lease)」没有新增状态值，而是在原状态 Hash 上加 `claimOwner` / `claimExpireAt`，对外状态仍是 `PROCESSING`，查询契约不变；`INSERT IGNORE` 不告诉你哪几行被忽略，所以扣库存的 n 不准时整组回滚、退化为逐条重放；验收目标 ≥3000 单/s 未达成（中位 319、最好 1056 单/s），瓶颈定位为「提交速率约 80 次/s × 实际批大小」：批量没让提交更快，只是让一次提交带走整批，而消费线程从 16 加到 32 反而更慢（更多线程抢同一行）。**抬高 80 次/s 这个上限正是 M5 的库存分桶**，M4 遗留两件事带进 M5：批大小的分布指标 + 退化计数（`LocalDealsMetrics` 现在没有，所以「批为什么攒不满」这次只有推断没有实测），以及让 `scripts/bench.sh` 每阶段归档 `app.log` 与一次 actuator 快照（`app.log` 每次 `app-start` 被覆盖）。

M5 执行时发现的计划偏差（详见 ADR 0006）：hash tag 用 `{sk:b<n>}` 而**不带券号**（按 orderId 查状态时拿不到券号），代价是不同券的同号桶共用 slot；活动元数据按桶复制 K 份；消费批次的认领与收尾按桶分组、每桶一次往返，但**落库仍按券分组**；L1 售罄标记跑在鉴权之前、没有用户也就没有桶，改为「每个桶都标记」才本地拒绝；L2 的按券下限除以 K 分摊，所以 20k 档的 429 从 21 涨到 1480（设计内行为）；对照组必须跑单节点，因为 `v2.0-m4` 的 key 没有 tag、在 Cluster 上根本起不来，这是本次 A/B 唯一的结构性差异。**两处需要改正的预期**：(1) M4 复测与上一段里「抬高 80 次/s 这个上限正是 M5 的库存分桶」是错的——那 80 次/s 是 MySQL `tb_seckill_voucher` 同一行的代价，M5 分的是 Redis 的 key，`SeckillVoucherMapper` 至今一张券一行，**拆这一行目前不属于任何里程碑**；(2) 计划预期的「主从切换丢预占 → Redis 超放 → MySQL 条件更新兜底」**没有复现**，实测 RPO = 0，因为转移期间那些 slot 直接不可写（买家收到 503 而不是假的成功），丢失窗口只有不足 1 ms 的复制延迟；要逼出那条防线需要先制造复制空窗（`CLIENT PAUSE` / 断链）或把写速率提高一两个数量级，已记在 M8。M4 遗留的两件仪表工作（批大小分布 + 退化计数、`bench.sh` 按阶段归档 `app.log` 与 actuator 快照）**M5 仍未做**，落库吞吐的 A/B 结论在补上之前一律不可信。

M6 执行时发现的计划偏差（详见 [ADR 0008](../adr/0008-m6-order-sharding.md)）：分片拓扑用 `slot = key % 8`、
`ds = slot % 2`、`table = slot / 2`，靠「8 整除 1024」让 M3 的基因同时决定库和表，所以按 `user_id` 查和按
`order_no` 查必然同片；`pay_no` / `refund_no` / `P<order_no>` 都继承基因，只有营销发放的 `G<grant_id>` 没有。
**`tb_seckill_voucher` 明确不做广播表**——`stock` 是写热点，广播写要 2PC 才能自洽，把最后一道防线建在需要
分布式事务的数字上是把问题变复杂，所以它是只在 ds_0 的单表。**七件计划没写、但实际挡路的事**：
(1) ShardingSphere 5.5.2 的元数据仓库带进 `jackson-dataformat-xml`，Spring 就把 XML 转换器排在 JSON 前面，
不带 Accept 头的请求开始返回 `<Result>…`；排除这个依赖会让启动直接失败（它运行时真的要 `XmlMapper`），
最终在 `WebConfig` 里显式删掉声明 XML 媒体类型的转换器——**只有 4 个控制器测试抓到了它**。
(2) 四类 SQL 被分片拒绝且理由都成立：`INSERT INTO 分片表 ... SELECT ... JOIN 单表`、多表 `DELETE ... JOIN`、
`UPDATE ... LIMIT`（limit 会按节点生效，「最多过期 200 张」会变成 1600 张）、以及不带分片键的
`SELECT ... FOR UPDATE`——最后一类**不报错**，只是在 8 张表上各锁一行，是最危险的一类。
还有第五类：**单表与分片表的关联子查询**（`SELECT ... FROM tb_user u WHERE EXISTS (SELECT 1 FROM trade_order o WHERE o.user_id=u.id)`）
会被路由到一个根本没有 `tb_user` 的库；`MarketingTagMemberMapper.countBusinessRelationship` 因此拆成了两问一合。
(3) 一条 INSERT 同时写 `order_no` 和 `user_id` 时两列必须指向同一片，于是测试里伪造的 `BASE + n` 订单号被拒；
这不是限制而是不变量生效——顺带使「订单号被别的用户占用」这类冲突**只可能来自同基因用户**，测试也照此改写。
(4) 附属表的 `AUTO_INCREMENT` 会在每张物理表各自从头数，改用 ShardingSphere 的 `SNOWFLAKE`。
(5) `uk(channel_txn_no)`、`uk(channel_refund_no)`、`uk(verify_code)` 收窄为片内唯一（前两者只会被同时带着
`pay_no` / `refund_no` 的语句碰到，幂等不受影响）；而 `uk(user_id, voucher_id, active_flag)` 语义不变，
因为一个用户的行永远在同一张表里——这正是分片键选 `user_id` 的原因。
(6) 营销那四个刻意收窄的测试上下文自己 import 数据源自动配置，必须显式 import 分片配置，否则拿到的是
ds_0 的裸连接。(7) 事务选 **LOCAL 而不是 XA**：一个消费批次确实横跨两个库（`OrderShardRoutingIT` 钉住了这点），
崩在两次提交之间的后果是**有界的少卖而不是超卖**（未提交的那一半仍持有 Redis 预占，对账器重投 + `INSERT IGNORE`
幂等，但库存会被多扣一次），**故障注入本身没有做，这一条是推理不是实测**。
另：ds_1 的 URL 从 `spring.datasource.url` 推导（schema 加 `_1`），所以隔离栈与压测脚本的变量不用改，
代价是 V16 搬迁 pre-shard 旧行时要跨 schema 读，**两个库必须是同一实例的两个 schema**。

时间不够时的裁剪顺序：先砍 M7，再砍 M6b，再把 M6 缩为“只做分片、不做 ES 读模型”。**M2、M3、M4、M5 的故障演练不可砍**——它们分别对应面试里的“业务闭环”“高并发设计”“性能调优”“分布式故障”四类必问题。

## 6. 诚实边界

- 单机（16C/30G）压测得到的是单机拐点与扩展趋势，不是生产容量；“万级”的论证方式是：单实例实测值 × 已验证的水平扩展性 + 各层无共享瓶颈的设计说明。
- 支付为 mock 渠道；不涉及真实资金、对账文件、风控。
- 未覆盖：多机房、RocketMQ 多副本切换、MySQL 主从切换。面试被问到时直接说没做，并说明会怎么做。
