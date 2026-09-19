# ADR 0001：M0 瘦身、单一链路与开环基线

- 状态：已采纳（2026-09-19，tag `v2.0-m0`）
- 关联：[V2 计划 M0](../plan/v2-high-concurrency-plan.md)、[M0 基线](../../benchmark/v2/m0/baseline.md)

## 背景

V1 为了「从旧版本平滑切换」积累了大量只服务迁移的代码：秒杀的 legacy 已购 Set、PROCESSING
索引 backfill、点赞的停写导入与 cutover 标记；9 个 `*_ENABLED` 开关默认全关，默认启动跑的不是要
展示的系统。项目没有线上存量数据，这些复杂度只有成本。压测方面，V1 用 JMeter 闭环模型测到
「208 req/s」，这是发压速率，不是系统容量。后续所有优化都需要一个诚实的对照组。

## 备选

1. **兼容保留**：开关保持默认关闭，legacy 路径继续留着，只加新代码。代价是每个里程碑都要对双路径
   做测试、写文档，面试时解释不清哪条才是真的。
2. **只删代码，基线沿用 V1 数字**：省事，但 V1 数字的口径（闭环、未分核、Redis Stream 时代）无法和
   V2 对比。
3. **（采纳）先减法再测量**：删除一切迁移与兼容代码，每个功能只留一条链路，开关默认打开；在
   **不改业务逻辑**的前提下，用开环模型重新测一遍，作为 M3/M4 的对照组。

## 决策

- **删除**：legacy 已购 Set（准入/补偿 Lua 各少一个 KEY）、Redis Stream 残留、PROCESSING backfill
  runner、点赞 legacy 导入 / cutover guard / write 闸门 / 热榜 read 闸门、`PUT /blog/like/{id}`；
  定位之外的关注 / 共同关注 / Feed / 评论 / `/shop/of/name`（Flyway V12、V13 删表删列）；未被调用的
  逻辑过期缓存、`SimpleRedisLock` 等教程残留；21 个阶段脚本、pre-M8 compose、V1 过程文档
  （全部可从 tag `v1-final` 找回）。
- **保留**：Redis 热榜 miss 时回落 MySQL，这是降级路径不是开关；`seckill_voucher_backfill.lua` 实为启动
  预热 Redis，并非旧数据工具；`/user/sign/count` 未被 M6B 取代。计划原文对这三处的描述与代码不符。
- **开关**：只保留运维开关（worker 启停、补偿），默认全部开启；测试 profile 显式关闭后台 worker。
  用 `DefaultRuntimeSwitchesTest` / `LegacyRolloutGateContractTest` 锁住这条规则。
- **包结构**：`trade / marketing / merchant / content / platform`，纯移动提交；将来拆服务沿这些边界拆。
- **测试基础设施**：`scripts/stack.sh` 隔离栈（独立端口 + named volume，不碰开发库）跑 IT 与压测；
  CI 用 Testcontainers 跑 MySQL/Redis IT，需要 Broker/ES 的 IT 留在隔离栈。
- **压测口径**：k6 `constant-arrival-rate`；应用、依赖、压测端按物理核分开 pin；每档一张新券、每个
  请求一个新用户；入口限流阈值调到上限，让测到的是准入链路本身而不是限流器（这是配置，不是代码）。
- **TDD 与「每次提交全绿」的冲突**：red 提交里 `mvn test` 只允许新增的那几条测试失败，并把失败清单写进
  commit message；其余提交一律全绿。纯删除不造红测试，被删代码的测试随之删除。

## 代价

- 失去「从 V1 数据升级」的能力。没有存量数据，这是有意为之；真要升级时，从 `v1-final` 取回工具。
- 默认开启补偿：对账器在订单 PROCESSING 超过 `stale-after`（2 min）后会释放库存。演示环境里 MQ 长时间
  不可用会触发真实补偿，这正是要展示的行为，但需要在 runbook 里写清楚。
- 基线数据有噪声：宿主机磁盘 93%（RocketMQ 需要调高磁盘水位）、机器上还有其他项目的容器；落库速率
  三轮在 123–478 单/s 之间波动，只能报中位数 264 加区间，不能报单个漂亮数字。
- 隔离栈与 CI 都依赖 Java 8：MyBatis-Plus 3.4 的 lambda 查询在 JDK 17 上会初始化失败，M1 升级时一并解决。
- 既有 IT 缺陷未在 M0 修复：`ShopSearchAfterIT` 依赖 ES 里已有数据（`v1-final` 上同样失败）；
  19 个 IT 仍挂在 `M5*/M6*_ISOLATED` 阶段门控和 sentinel 库名守卫上，后续里程碑改为统一的隔离栈门控。
