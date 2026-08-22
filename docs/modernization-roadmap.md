# local-deals-service 改造路线与新会话执行手册

> 文档状态：当前执行入口
>
> 记录日期：2026-08-22
>
> 仓库：`/home/sd101t/IdeaProjects/hm-dianping`
>
> 当前分支：`codex/platform-hardening`
>
> 最新已完成阶段：M5D（提交链见 `docs/m5d-reliability-results.md`）
>
> 当前阶段：M5A、M5B、M5C、M5D 已完成；M6 未开始且未经授权

这份文档用于在新会话中继续实施。它首先记录中断现场，再给出后续路线、边界、验收标准和 Git 节点。执行前必须用 Git 重新核对实际状态；如果分支或 HEAD 已变化，以实际仓库为准并先更新本节，不能机械套用旧快照。

“点赞持久化与热榜”已在 M4 完成。M5A 已完成指标契约、隔离基线和故障行为盘点；M5B 已按
`docs/m5b-bounded-cache-plan.md` 完成有界缓存，证据见 `docs/m5b-bounded-cache-results.md`。
M5C 任务级契约见 `docs/m5c-resource-traffic-plan.md`，完成证据见
`docs/m5c-resource-traffic-results.md`。M5D 覆盖矩阵、隔离故障和恢复证据见
`docs/m5d-reliability-plan.md` 与 `docs/m5d-reliability-results.md`。本次未进入 M6，也未升级技术栈。

`docs/project-context.md` 保存的是更早阶段的历史上下文，其中的 `main` 分支和旧提交号已经过时。自本文件创建后，恢复项目应先读本文件，再按专题阅读 `docs/admin-rbac.md`、`docs/seckill-reconciliation.md` 和 `docs/blog-like-hot-rank.md`。

## 1. 项目定位与最终叙事

不建议把项目仅换成剧本杀、电影或卡牌的页面外壳。面试官仍然能识别黑马点评的接口和业务骨架，而且换皮不能说明为什么要引入某项架构。

建议将项目定位为：

> 面向本地生活商户的活动与内容平台。消费者发现商铺、发布内容并参与优惠活动；商户在独立后台管理自己的商铺和活动；平台重点保证突发流量下的资格判断、订单一致性、互动计数、数据隔离和故障恢复。

项目只保留三条相互连接的业务主线：

1. **商户运营边界**：消费者身份与后台运营身份分离，后台使用商户级 RBAC 和数据范围。
2. **活动交易可靠性**：优惠券秒杀从 Redis 资格预占，经 RocketMQ 到 MySQL 订单，具有精确状态、幂等、补偿、隔离和对账。
3. **内容互动可靠性**：点赞身份有持久事实来源，聚合计数可重放，热榜是可重建的有界读模型。

限流、降级、缓存、可观测和故障演练是这三条主线的保障，不作为孤立的“技术亮点”堆上去。

面试时应能用一句话概括：

> 我没有把教程项目简单换皮，而是围绕“谁能操作哪家商户的数据、一次活动资格如何精确对应一张订单、异步状态长期不收敛时怎么恢复、互动计数和排行榜如何在故障后重建”重做了核心边界，并用故障注入和一致性校验说明改造有效。

## 2. 对网络建议的取舍

| 网络建议 | 本项目决策 | 原因 |
| --- | --- | --- |
| 换成剧本杀、电影、卡牌平台 | 不作为核心改造 | 可以调整文案和演示数据，但不把换皮当架构成果 |
| 完整“租户-商户-用户”体系 | 保留现有单层 merchant scope，不扩父子租户 | 当前已有独立后台账号、固定角色、商户数据范围；没有真实业务需求时继续扩层级只会增加表和权限漏洞 |
| 用户标签和精准营销 | 有条件采纳，放到活动资格阶段 | 标签必须服务于“哪些用户能领取哪张券”，而不是单独做一个无消费者的标签 CRUD |
| 每日任务领取优惠券 | 有条件采纳，作为优惠券发放来源之一 | 与主动发券、用户领取共用同一份 grant ledger，避免再造三套领取逻辑 |
| 点赞异步批量回库、热榜缓存 | 采纳，但先收口当前 WIP | 这是当前最明显的教程式实现缺陷，也能形成数据真相、outbox、派生读模型的完整故事 |
| 多级缓存、布隆过滤器、双重锁 | 按读模型选择，不全局套模板 | 空值缓存、singleflight、逻辑过期各有适用场景；布隆过滤器还必须解决新增数据同步和误判边界 |
| 动态限流和降级 | 采纳，排在核心一致性收口之后 | 先定义资源、维度、失败语义和指标，再决定是否需要 Redis Lua 或控制面 |
| 继续重写秒杀 | 不重写 | 精确预约、事务消息、消费前校验、补偿、quarantine 和超龄对账已形成主线，后续重点是证据和故障演练 |
| 升级 Spring Boot 3 和全部中间件 | 延后为独立维护阶段 | 当前是 Java 8 + Spring Boot 2.3.12；Java 17/Jakarta 迁移会扩大验证面，但不会自动提升业务设计质量 |
| 把 RocketMQ 换成 Kafka | 不做 | 当前没有由业务语义或测量结果支持的替换理由，同一项目同时堆两套 MQ 只会削弱叙事 |
| 分库分表和全局路由 | 暂缓 | 只有单库在目标数据量下成为已测瓶颈后才立项；提前分片会制造分页、事务、扩容和对账问题 |

## 3. 必须遵守的设计原则

1. **每份关键状态只有一个明确真相源**。Redis 缓存、排行榜、WebSocket 和 Elasticsearch 都不能被描述成和 MySQL 同等可信的第二真相。
2. **先写业务不变量，再选择技术**。例如“一位用户对一个活动至多有一份有效资格”，应先由唯一约束和状态机定义，再讨论 Redis 或 MQ。
3. **异步不等于最终一定成功**。每条异步链路都必须回答：如何识别、如何重试、何时终止、如何隔离、谁能人工恢复。
4. **降级必须保持语义安全**。秒杀不能在 Redis 故障时绕过资格校验直写数据库；坏缓存不能解释为空榜；权限系统不能在 Redis 故障时默认放行。
5. **缓存按业务读模型设计**。不为追求“多级缓存”而同时堆本地缓存、Redis、布隆过滤器和数据库。
6. **先单体模块化，不先拆微服务**。目前事务、调试和部署成本都更适合一个有清晰模块边界的 Spring Boot 单体。
7. **所有性能结论必须带正确性证据**。吞吐、P99、拒绝率、订单数、库存、重复记录、积压和恢复时间要一起看。
8. **运维门禁也是实现的一部分**。不能滚动升级的协议必须写停写、停旧实例、回填、核对和回滚步骤。
9. **前端隐藏菜单不等于授权**。权限、商户范围和资源归属必须在后端同一条查询或条件更新中再次校验。
10. **默认关闭破坏性自动化**。历史回填、超时补偿、批量修复等功能必须有独立开关、观察阶段和审计证据。

## 4. 当前仓库真实快照

### 4.1 已提交里程碑

| 里程碑 | 提交 | 状态 | 已形成的能力 |
| --- | --- | --- | --- |
| M0 教程改造基线 | `39f611f` | 已提交 | 本地生活业务、搜索、早期 RocketMQ/Canal/WebSocket 基础 |
| M1 安全边界 | `064d10a` | 已提交 | 关闭匿名管理写接口；验证码发送/消费 Lua；上传归属、状态和路径边界；用户/管理 WebSocket 初步隔离 |
| M2 秒杀精确生命周期 | `3abad89` | 已提交 | RocketMQ 事务消息；`userId -> orderId` 精确预约；消费前校验；成功/失败状态；精确补偿；状态查询和前端轮询 |
| M3 商户后台与 RBAC | `91362e8` | 已提交 | 独立后台账号、BCrypt、固定角色权限、merchant scope、一次性 WS ticket、平台/商户频道、V5/V6 迁移 |
| M3.1 超龄预约对账 | `dd36b4b` | 已提交 | PROCESSING 索引、消费者共享锁、每订单调度仲裁、DB 精确分类、quarantine、可审批补偿、回填和运行手册 |
| M4 点赞持久化与热榜 | `710ad61` | 已完成 | MySQL 点赞关系、事务 outbox、停写导入与 cutover、generation-fenced Redis top-K、DB 安全回退 |
| M5A 可观测基线与故障盘点 | `4bbc2ba` | 已完成（1 项 BLOCKED） | 低基数指标目录、management 网络边界、B0-B4、F1-F4、MySQL/Redis 只读 backlog 采样；Broker 故障下新秒杀准入未形成有效隔离证据 |
| M5B 有界缓存 | `3ac79f3` / `e21ce4f` | 已完成 | 商铺详情/类型字典的 Redis 500ms 有界等待、DB 安全回退、per-key singleflight、坏值修复、短空值和 after-commit 失效；结果见 `docs/m5b-bounded-cache-results.md` |
| M5C 资源级流控 | `108d6fa`…`246988e` | 已完成（Broker F5 仍 BLOCKED） | 秒杀 activity/user/IP 前置门禁、DB/搜索本地并发舱、稳定 429/503/业务码和双实例/故障证据；结果见 `docs/m5c-resource-traffic-results.md` |
| M5D 指标与故障恢复收口 | `bda2b85`…（本阶段提交链） | 已完成 | 8.5 覆盖矩阵、秒杀 DB persist Timer、ES consumer retry/幂等、run-id 隔离栈和 F1--F5；结果见 `docs/m5d-reliability-results.md` |

“已提交”只表示形成了可追踪节点，不表示未来任何环境下都无需复验。发布或演示前仍应按照本文件的证据门禁运行当前版本测试。

### 4.2 M4 验收快照

M4 已交付的点赞与热榜范围包括：

- V7 热榜查询索引与 Redis generation-fenced top-K；
- V8 `tb_blog_like` 持久点赞关系、历史计数 offset、事务 outbox 和 cutover marker；
- 显式 `PUT like` / `DELETE like`，替换不可重试的 toggle 语义；
- 旧 `blog:liked:*` Redis 身份的停写导入；
- outbox 聚合、清理、worker 和启动门禁；
- 热榜 DB 回退、异步 singleflight 预热、原子发布和新博客 after-commit 更新；
- 用户端页面和对应单元、MVC、MySQL、Redis 测试。

2026-08-20 使用 Java 8 对最终源码执行编译和默认测试，228 个测试全绿；专用 MySQL
schema 与专用 Redis 中的 M4 集成测试 11 个全绿。Flyway 的 V1->V8、V6->V8 均通过，
最终 `invalid_blogs=0`、`pending_outbox=0`、专用 Redis `DBSIZE=0`。完整命令、时间、隔离
边界和测试计数记录在 `docs/blog-like-hot-rank.md`。这份证据不等于生产容量结论，也不等于
浏览器端到端测试。

## 5. 目标架构及真相边界

```text
用户 H5 --------------------> 用户 API / 用户会话
                                  |
后台 SPA ---> 独立后台认证 ---> Admin API ---> RBAC + merchant scope
                                  |
          +-----------------------+-----------------------+
          |                       |                       |
     商铺与内容域             活动与订单域            互动与营销域
          |                       |                       |
          +-----------------------+-----------------------+
                                  |
                        MySQL：持久业务真相
                         /       |       \
                        /        |        \
             Redis：状态/读模型  RocketMQ：传输  ES：搜索读模型
                        \        |        /
                         \       |       /
                    指标、对账、quarantine、运行手册
```

| 数据 | 真相源 | Redis/MQ/ES 的角色 | 故障时原则 |
| --- | --- | --- | --- |
| 消费者、后台账号、角色、商户归属 | MySQL | Redis 保存短期会话 | 无法验证身份时拒绝，不默认放行 |
| 商铺和券配置 | MySQL | Redis 缓存；ES 提供搜索 | 缓存坏则受控回库，搜索读模型坏则限流降级 |
| 秒杀资格预占 | Redis 精确状态机，订单最终由 MySQL确认 | RocketMQ 传递订单意图 | Redis 不可用时停止新预占，不能绕过校验 |
| 秒杀订单和 DB 库存 | MySQL | Redis 保存 PROCESSING/SUCCESS/FAILED 快速状态 | 用精确预约、DB 分类和对账收敛，不猜测成功 |
| 点赞身份 | MySQL `tb_blog_like` | outbox 聚合计数；Redis 只做热榜 | Redis 丢失不影响是否点赞的判断 |
| 博客热榜 | MySQL `tb_blog.liked` + 稳定排序 | Redis top-K 是可重建读模型 | metadata/live 不一致时整页回 DB |
| 搜索索引 | MySQL | Canal/RocketMQ/ES 是派生链路 | 记录 lag；不要把索引缺记录当数据库不存在 |
| WebSocket 通知 | 订单状态仍以持久查询为准 | Redis pub/sub + WebSocket 只加速通知 | 断线后使用状态接口有限轮询 |

这里必须诚实说明一个边界：当前秒杀预占的精确未落库状态依赖 Redis 持久化。若 Redis 在 PROCESSING 期间发生不可恢复的全量数据丢失，仅靠订单表无法还原所有尚未消费的预约。当前可接受的恢复契约应是“停止新流量、恢复 AOF/备份、排空 MQ、运行精确对账”；若未来要求在无 Redis 备份情况下仍零丢失，必须增加持久预约账本，而不是再叠一层缓存。

## 6. 总体实施顺序

| 顺序 | 阶段 | 优先级 | 前置条件 | 主要产物 |
| --- | --- | --- | --- | --- |
| 1 | M4 收口点赞持久化与热榜 WIP（已完成） | P0 | 当前中断现场 | `710ad61` 可验证、可回滚的独立提交 |
| 2 | M5 缓存语义、流控、降级与可观测（M5A 已完成） | P1 | M4 完成且工作区干净 | 资源级限流、降级矩阵、指标和故障测试 |
| 3 | M6 活动资格、标签、任务与统一发券账本 | P1 | M5 的幂等/流控基础可用 | 一条真实的新业务闭环，而非零散 CRUD |
| 4 | M7 故障演练、压测对比和项目展示收口 | P1 | 核心功能冻结 | 可重复证据、运行手册、架构图和面试材料 |
| 5 | M8 技术栈升级或分片研究 | 可选 | 前述阶段全绿且有测量理由 | 独立维护分支或明确“不需要”的结论 |

硬性停止线：M4 未验收、未形成 Git 提交前，不得开始 M5；M5 的限流和故障指标未验证前，不得用“大流量高可用”作为项目结论；M6 不需要为了显得功能多而同时实现自定义角色 UI、推荐系统和供应商父子层级。

## 7. 阶段 M4：收口点赞持久化与热榜 WIP（已完成）

### 7.1 目标

把教程版“Redis 判断点赞 + 每请求直接修改 `tb_blog.liked` + 热榜每次查 DB”改成：

- MySQL 持久关系记录用户是否点赞；
- 关系变化与不可变 outbox 在同一事务提交；
- worker 按 blog 聚合 delta，并在同一事务更新 aggregate 和标记精确 event；
- Redis top-K 只是 generation-fenced 的可重建读模型；
- 旧数据通过明确停写、停旧实例、导入、审计和 cutover marker 完成迁移。

### 7.2 M4 恢复动作（历史记录）

执行 M4 时首先只做只读盘点，不修代码：

```bash
cd /home/sd101t/IdeaProjects/hm-dianping
git branch --show-current
git log -5 --oneline --decorate
git status --short
git diff --check
git diff --name-status
git ls-files --others --exclude-standard
```

当时预期分支为 `codex/platform-hardening`、基线 HEAD 为 `dd36b4b`。若复核历史过程，必须先记录差异；禁止使用 `git reset --hard`、`git clean` 或切换分支覆盖现场，也不要未经检查把全部文件一次性加入暂存区。

建议先保存只读证据：

```bash
git diff --binary > /tmp/hm-dianping-blog-wip-20260820.patch
git ls-files --others --exclude-standard > /tmp/hm-dianping-blog-wip-untracked.txt
```

补丁不包含未跟踪文件内容，因此第二份清单不能省略；它只是恢复证据，不替代 Git 提交。

### 7.3 代码审计清单

恢复后逐项确认，不以旧聊天里的“已修复”代替当前源码证据：

1. **写入语义**
   - `PUT /blog/{id}/like` 和 `DELETE /blog/{id}/like` 表达目标状态；重复请求不重复产生 outbox。
   - 关系变化和 outbox 插入处于真实 Spring 事务代理内，outbox 失败时关系必须回滚。
   - 博客不存在、已删除、未登录和非法 ID 有明确的 HTTP 状态。
   - 旧 `/blog/like/{id}` 的兼容策略要明确：短期显式 `liked` 参数，或宣布破坏性切换并处理 7 天静态缓存；不能文档称兼容而旧页面实际 400。

2. **数据库不变量**
   - 唯一关系为 `(blog_id,user_id)`。
   - `tb_blog.liked = legacy_liked_offset + COUNT(tb_blog_like)`。
   - outbox 事件只允许 `+1/-1`，一次事件至多被聚合一次，计数不能为负。
   - 删除博客时 relation/outbox 的 FK 和清理策略一致，不能让已处理事件在保留期内意外阻塞业务删除。

3. **worker 正确性**
   - `SELECT ... FOR UPDATE` 是正确性边界；Redisson 锁只能减小竞争，Redis 故障不能令 DB outbox 永久停止消费。
   - 只标记本批精确 event ID，不能吞掉快照后新插入事件。
   - aggregate 更新与 processed 标记在同一事务；任何一项异常整批回滚。
   - JDBC batch 对 `SUCCESS_NO_INFO` 的处理必须符合所用驱动配置，不能在开启批量重写后永久误判失败。
   - cleanup 的批量和周期能追上目标流量；记录 pending 数和 oldest age，而不只记录总表行数。

4. **历史导入与切换**
   - 默认 `write/worker/backfill/read/refresh` 均安全关闭。
   - backfill 与写入、worker 不可同时开启；旧节点必须全部停止。
   - Redis 源集合在导入期间发生变化时拒绝完成。
   - 导入成功后才写持久 cutover marker；生产 write/worker 在 marker 缺失时拒绝启动。
   - 新旧计数无法对应的部分只能进入 `legacy_liked_offset`，不能伪造用户关系。

5. **热榜发布与读取**
   - DB 排序固定为 `liked DESC,id DESC`，同分跨页不重复、不漏项。
   - 缓存只服务完整页；跨 top-K 尾部的部分页整页回 DB。
   - builder generation、新博客 after-commit 增量和 publish Lua 的交错不能让旧快照覆盖新博客。
   - 读取同时校验 ready、generation、capacity、publishedAt、meta count 和 ZCARD；live key 丢失不能命中为空榜。
   - 临时 key 有界 TTL；空榜也能原子发布；错误 metadata 不能产生部分写。
   - Redis miss 触发的预热必须进程内和集群级 singleflight，且不能让请求无限等待。

6. **查询成本**
   - 热榜命中后批量回填作者和当前用户的点赞状态，避免一页产生 `1 + N + N` 次 MySQL 查询。
   - DB fallback 禁止不必要的总数 COUNT。
   - Redis 故障日志限频，避免故障时日志 IO 再次放大。

### 7.4 验证门禁

必须使用 Java 8。先运行最新源码的 compile/testCompile 和全部默认单测，再运行风险相关定向测试。实际 Maven 路径以本机为准，不要照抄失效路径。

至少需要以下证据：

- `git diff --check` 通过；
- Java 8 `compile`、`testCompile`、默认单元测试全绿；
- `BlogControllerLikeMvcTest` 覆盖登录、PUT/DELETE、重复请求和旧路由契约；
- `BlogServiceAfterCommitTest` 证明仅事务提交后更新派生热榜，回滚时不更新；
- 在**专用临时 MySQL schema** 中运行 `BlogLikeReliabilityIT`，证明事务回滚、并发幂等、worker 重放和 aggregate 恒等式；
- 在**专用临时 Redis** 中运行 `BlogHotRankRedisIT`，证明 generation 竞态、原子发布、坏 metadata 回退和 top-K 裁剪；
- Flyway 从 V1 全新迁移到 V8，以及模拟 V6 升级到 V8 均通过；
- 三个用户端 HTML 的内联脚本通过语法检查，并实际核对点赞交互；
- 集成测试不得消费共享库的 pending outbox，也不得删除共享 Redis 的正式热榜 key。

测试结果要记录命令、时间、环境和 test count。只写“测试通过”而没有命令及隔离边界，不算完成证据。

### 7.5 M4 完成条件和 Git 节点

满足以下条件才可提交：

- 所有 P0/P1 审计项关闭，或明确降级为有记录的后续项；
- 数据迁移和回滚手册与源码配置一致；
- README 不再把未运行的测试写成已验证结论；
- 工作区只包含本阶段意图文件；
- staged diff 经人工检查，未包含 `.env`、日志、测试数据库、构建产物或凭据。

建议提交信息：

```text
feat(blog): make likes durable and hot rank rebuildable
```

若 M4 一次审计后仍过大，可拆成两个可独立绿灯的提交：先提交 V7 DB 索引和热榜读模型，再提交 V8 点赞关系/outbox/cutover。不能为保留既有工作量而把未闭合代码硬塞进同一个提交。

完成记录：M4 于 2026-08-20 按上述门禁验收并形成 `710ad61`。路线文档此前已单独形成
`b0178e5`，两个提交均只保存在本地，未 push。后续变更若破坏 M4 不变量，必须重新运行
第 7.4 节的相关门禁，不能仅引用本次历史结果。

## 8. 阶段 M5：缓存语义、限流、降级与可观测

### 8.1 目标

回答“流量突发、Redis/MQ/MySQL 故障时系统做什么”，并让答案可以由指标和故障测试证明。该阶段不追求引入最多的组件。

### 8.2 按场景设计缓存

| 场景 | 推荐策略 | 不采用的做法 |
| --- | --- | --- |
| 商铺详情 | cache-aside + 短空值 + 热 key singleflight/逻辑过期；后台提交后失效 | 每次 miss 无保护回库；把不存在永久缓存 |
| 商铺类型等小字典 | Redis 或本地短缓存，带版本/失效通知 | 永久 JVM 缓存导致多实例不一致 |
| 搜索 | ES 派生读模型 + lag 指标 | ES 故障时无界 `LIKE %keyword%` 扫全库 |
| 秒杀活动 | Redis 资格状态机，不称为普通缓存 | Redis 故障时跳过资格判断直写 DB |
| 博客热榜 | M4 的 generation top-K + 索引化 DB fallback | 每次点赞直接无版本 `ZINCRBY` 造成漂移 |
| 用户是否点赞 | MySQL 关系表 | Redis 丢失后用空 ZSET 当作“无人点赞” |

商铺详情可实现“短等待 + 旧值服务 + 后台单飞重建”，但必须先采集当前 DB QPS、cache hit 和 P99。布隆过滤器只有在 ID 空间大、穿透流量已被证实，并且新增/删除商铺的过滤器同步契约闭合时才使用；当前用短空值缓存已经足够时，不新增布隆过滤器。

### 8.3 资源级限流

第一版只覆盖高风险入口：

| 资源 | 维度 | 推荐算法 | Redis 故障语义 |
| --- | --- | --- | --- |
| 发送验证码 | IP + phone | 固定窗/令牌桶 + 已有发送冷却 | fail closed |
| 后台登录 | client IP + username | 已有原子计数，补齐指标 | fail closed |
| 秒杀提交 | activity + user + IP | Redis Lua 令牌桶，先限流再资格预占 | fail closed，返回可识别 429/503 |
| 普通领券 | campaign + user | 令牌桶 + DB 唯一发放账本 | 不能确认资格时 fail closed |
| 点赞写入 | user + blog | 本地保护 + DB 连接池 bulkhead | Redis 只做热榜时可继续 MySQL 写入 |
| 搜索/热榜读取 | IP + endpoint | 本地并发上限 + 超时 | 返回受控降级结果，不放大 DB |

“动态限流”至少意味着规则有持久版本、可审核修改、实例能收敛到新版本，并且修改失败不会无限放行。建议先做配置文件和指标化静态规则；只有确实需要运行时调整时，再增加一个小型 `traffic_policy` 管理面和 Redis 版本通知，不先建设通用流量平台。

### 8.4 降级优先级

系统过载时按以下顺序关闭能力：

1. 暂停热榜刷新、推荐性查询和非必要预热；
2. 暂停营销通知、feed 扩散等可延迟任务，但保留持久事件；
3. 降低搜索并发，必要时返回缓存的热门分类入口；
4. 限制点赞和普通领券写入；
5. 最后才影响登录后的订单状态查询和已接受订单的消费/对账。

新秒杀预占、后台权限验证和资金/库存相关写入在依赖不可用时必须 fail closed。已经接受的订单消费、状态查询和对账属于高优先级恢复流量，不能和新流量一起被一刀切关闭。

### 8.5 可观测指标

复用现有 Micrometer/Prometheus，不新增另一套指标体系。M5D 最终覆盖矩阵见
`docs/m5d-reliability-plan.md`；实现和外部采集边界如下：

- 资源准入复用 `local_deals.traffic.decision{resource,result,reason}`，不注册重复指标；
- cache hit/miss/stale/fallback/rebuild 计数与重建耗时；
- 秒杀 accepted、rollback、consumer success/retry/DLQ、PROCESSING oldest age、quarantine 数；
- 点赞 outbox pending、oldest age、batch size、apply/rollback、aggregate invariant failure；
- 热榜 publish generation、age、DB fallback、singleflight skip/failure；
- ES 同步失败事件；DTO 无可信事件时间，event lag 为 `NA`，只从 Broker 管理面采 group lag；
- DB 连接池等待、关键 SQL P95/P99、RocketMQ backlog。

标签中禁止放 userId、orderId 等高基数字段。日志保留 trace/order/campaign 标识用于单条排障，指标只使用资源类型、结果和有限 reason。

### 8.6 M5 验收

- 固定一组正常流量和突发流量，记录改造前后吞吐、P95/P99、DB QPS、cache hit、拒绝率；
- Redis 延迟/断连、MySQL 断连、MQ consumer 停止、ES 不可用分别有故障注入；
- 每个故障场景同时核对业务正确性和恢复时间，不能只看进程仍存活；
- 限流返回 429，依赖故障返回 503，业务冲突使用稳定业务码，前端不把它们都显示为“系统错误”；
- 故障结束后 backlog 可收敛，重复订单为 0，库存和订单数满足不变量。

建议拆成小提交：

```text
feat(cache): add bounded rebuild and fallback policies
feat(traffic): add resource-level limiting and degradation
feat(observability): expose reliability and backlog metrics
```

### 8.7 M5 分解与当前执行顺序

M5A、M5B、M5C、M5D 已实施，并分别形成可独立回滚的绿灯节点：

1. **M5-A 基线与契约（已完成）**：核对干净工作区；固定商铺详情、热榜、搜索、验证码、后台登录和
   秒杀提交的正常/突发流量；记录现有吞吐、P95/P99、DB QPS、缓存命中/回退和拒绝语义；
   先定义 429/503 与有限 reason 标签，禁止 userId/orderId 进入指标标签。
   结果见 `docs/m5a-observability-results.md`；该阶段未实现 429/503 策略，F3 Broker 新准入
   补测因隔离停止线记为 BLOCKED，不得据此声称 producer 故障语义已验证。
2. **M5-B 有界缓存（已完成）**：商铺详情和类型字典已实现 Redis `500ms` 有界等待、
   miss/坏值/不可用后的 DB 安全回退、per-key singleflight、短空值和后台 commit 后精确失效；
   未引入布隆过滤器或额外缓存层。任务契约见 `docs/m5b-bounded-cache-plan.md`，正式与负面证据见
   `docs/m5b-bounded-cache-results.md`。
3. **M5-C 资源级流控（已完成）**：复用已有验证码/后台登录门禁，新增秒杀 activity+user+IP 和
   读接口本地并发上限；验证 429、依赖故障 503、秒杀 fail closed，以及已接受订单消费和
   对账不被新流量限流误伤。详细配置、响应矩阵、测试、隔离门禁和停止线见
   `docs/m5c-resource-traffic-plan.md`；完成证据见 `docs/m5c-resource-traffic-results.md`。该文件保留
   当时 Broker F5 `BLOCKED` 的历史事实，后续 M5D 证据不追溯改写。
4. **M5-D 指标与故障收口（已完成）**：补齐第 8.5 节覆盖矩阵和秒杀 DB persist Timer；ES
   consumer 目标失败抛出以进入 retry/DLQ；在 run-id 专用栈完成 Redis、MySQL、consumer pause、
   ES 和严格预检后的 Broker F5。结果见 `docs/m5d-reliability-results.md`，未写性能提升百分比。

M5-B、M5-C、M5-D 各自通过 Java 8 默认测试、相关隔离 IT、`git diff --check` 和 staged
diff 检查后再提交；任一节点未闭合，不进入下一节点，更不得开始 M6。

## 9. 阶段 M6：活动资格、用户标签、每日任务与统一发券

### 9.1 为什么选择这一阶段

现有商户 RBAC、优惠券和秒杀已经提供底座。相比再加一个孤立模块，把“标签 -> 活动资格 -> 领取/主动发放/任务奖励 -> 发放账本 -> 通知”串成一条业务链，更能体现产品和架构思考。

MVP 不做完整 CDP、实时推荐或任意表达式引擎，只支持少量可审计规则：商户手工标签、近 30 天消费次数、历史到店/互动、每日任务完成状态。

### 9.2 最小数据模型

表名可在实施时按现有命名统一，但必须保持以下职责分离：

| 模型 | 关键字段/约束 | 作用 |
| --- | --- | --- |
| 用户标签定义 | `id, merchant_id, code, name, source_type, status`，商户内 code 唯一 | 标签由哪家商户定义，避免跨商户营销数据泄漏 |
| 用户标签关系 | `merchant_id, tag_id, user_id, source_ref, expire_time`，关系唯一 | 手工或规则计算结果；过期可重算 |
| 优惠活动 | `id, merchant_id, voucher_id, grant_mode, rule_version, begin/end, quota, status` | 统一描述用户领取、平台主动发放、每日任务奖励 |
| 发放账本 | `campaign_id, user_id, source_type, idempotency_key, status, create_time`，活动用户或幂等键唯一 | 所有入口共享的最终幂等边界 |
| 每日任务进度 | `user_id, task_code, biz_date, progress, status`，用户/任务/日期唯一 | 防止重复完成和重复领奖 |

券库存与发放账本必须在一个明确事务/预约协议中收敛。Redis 可以缓存活动规则、保存快速计数或 Bitmap，但 MySQL 账本决定用户是否已获得资格。不要仅凭 Redis Bitmap 永久认定发券成功。

### 9.3 最小业务流程

1. 商户创建活动并选择券、时间、配额和可选标签条件；平台账号可跨商户，但仍需要权限码。
2. 用户查询可参与活动，后端在 merchant scope 内评估资格，返回 rule version。
3. 用户主动领取、平台批量发放和每日任务领奖都调用同一发放服务。
4. 发放服务以 idempotency key 和唯一约束确保重复请求只生成一份账本。
5. 发放成功后再通过 outbox/MQ 发送通知；通知失败不回滚已经完成的发放。
6. 活动取消、用户不满足资格、配额耗尽和重复领取都有稳定状态，不用异常字符串推断结果。

### 9.4 权限与数据范围

- 商户只能定义本商户标签、活动和券；
- 平台跨商户操作仍需显式 permission；
- 消费者无法伪造 merchantId、campaignId 读取其他商户内部规则；
- 批量发券必须记录 operator account、rule version、目标快照和审计时间；
- 前端菜单隐藏只是体验，Controller/Service/SQL 均再次校验。

### 9.5 高流量与失败处理

- 同一用户的重复领奖由 DB 唯一键兜底；
- 活动入口先限流，再评估资格和配额；
- 热活动规则可缓存，但规则版本不一致时回源，不使用旧规则发券；
- MySQL 不可用时停止新发放，不能先向用户显示成功再补账；
- MQ 不可用时发放事务仍可提交 outbox，由 worker 后续通知；
- 批量主动发券分页生成明确 job，支持暂停、进度、失败明细和重试，不能在 HTTP 线程一次扫完全部用户。

### 9.6 M6 验收

- 同一用户 100 个并发领取最终只有一份有效账本；
- 用户领取、平台发放、每日任务三个入口都复用同一幂等边界；
- A 商户操作 B 商户活动在 SQL 数据范围层失败且无缓存/MQ 副作用；
- 规则变更后旧 rule version 不能继续发券；
- 通知发送失败不丢发放事实，恢复后可重试；
- 任务跨天、重复回调和时区边界有确定测试；
- 压测同时报告发放数、重复数、剩余额度、P99、限流数和 outbox lag。

建议提交顺序：

```text
feat(marketing): add merchant-scoped eligibility and tags
feat(coupon): unify claim push and task reward grants
feat(marketing): add auditable batch delivery and notification outbox
```

## 10. 阶段 M7：故障演练和展示收口

### 10.1 必须能回答的故障矩阵

| 故障 | 新请求行为 | 已接受任务行为 | 恢复动作 | 证据 |
| --- | --- | --- | --- | --- |
| Redis 短时不可用 | 鉴权/秒杀 fail closed；热榜受控回 DB；M4 后点赞身份走 MySQL | MQ consumer 重试，不能无预约落库 | 恢复连接，核对 processing/outbox/cache rebuild | 错误码、DB QPS、积压年龄、恢复时间 |
| Redis 全量数据丢失 | 立即停止秒杀新预占，禁止用空库存 key 启动 | 保留 MQ，恢复 AOF/备份后精确对账 | 恢复备份、验证 reservation/status、再开流量 | 订单/库存/预约三方核对；明确 RPO |
| RocketMQ 不可用 | 半消息发送失败则不做本地预占；通知类写 outbox | 已在 Broker 的消息按其重试/DLQ 策略处理 | 恢复路由，观察 backlog/DLQ，运行对账 | accepted 数、PROCESSING age、DLQ |
| 消息延迟或重复 | 返回 PROCESSING，可通过状态接口查询 | 消费幂等；超龄后由 reconciler 精确分类 | 修复 consumer，先无补偿观察，再审批补偿 | 最终状态、重复订单 0、quarantine |
| MySQL 不可用 | 写操作 fail closed；只对安全读提供有界陈旧缓存 | consumer/outbox 保留重试，积压超阈值后停止新流量 | 恢复 DB，先消费高优先级积压 | backlog、连接池等待、收敛时间 |
| Elasticsearch 不可用 | 搜索限流降级，不做无界 DB 模糊扫描 | Canal/MQ 事件保留或进入失败处理 | 重建索引并比较源表行数/version | sync lag、缺失/多余文档数 |
| WebSocket/Redis pub-sub 断线 | 用户看不到即时通知但不影响订单 | 订单状态仍持久 | 前端有限轮询状态接口 | 最终能查询成功/失败，无无限轮询 |
| 单实例过载 | 低优先级功能先限流/降级 | 订单消费与状态查询保留资源 | 降低入口、扩容、排空积压 | 资源水位、拒绝率、P99、正确性 |

### 10.2 证据包

最终演示至少保留：

- 一张系统边界图和一张秒杀状态流转图；
- 固定参数的 baseline/current 压测结果；
- Redis 断连、consumer 停止、DB 永久冲突、WebSocket 断线四类故障演示；
- 每轮运行的环境、commit、命令、原始结果和业务不变量；
- Prometheus/Grafana 截图只作为辅助，CSV/SQL/Redis 校验仍需可复现；
- 一份“已解决、仍有边界、何时升级”的诚实清单。

不要声称“完美解决缓存问题”“绝不丢消息”或“无懈可击”。更可信的表达是说明故障模型、可接受 RPO/RTO、自动恢复边界和需要人工审批的情况。

## 11. Git 实施规范

### 11.1 每个新会话开始

```bash
git branch --show-current
git log -5 --oneline --decorate
git status --short
git diff --check
```

如果工作区不干净，先识别哪些是既有用户改动，不能假定都属于当前任务。不要使用破坏性命令清理现场。

### 11.2 每个阶段提交前

1. 列出本阶段意图文件；
2. 运行与风险相称的单元/集成/故障测试；
3. `git diff --check`；
4. 使用显式文件路径 `git add`，不要默认 `git add .`；
5. `git diff --cached --stat` 和 `git diff --cached` 人工审查；
6. 确认没有 `.env`、密钥、日志、数据库文件、JMeter 大产物和 `target/`；
7. 一个提交只表达一个可回滚设计决策；
8. 提交后再次 `git status --short` 并记录测试证据。

本文件可先作为独立文档提交：

```text
docs: add modernization execution roadmap
```

当前 WIP 没有通过门禁前，不要把它和本文件一起打成“阶段完成”提交。未经用户明确要求不要 push；本地 commit 与远端发布是两个动作。

### 11.3 分阶段而不是大提交

每个功能阶段最多包含：schema、领域逻辑、API/前端、测试、运行文档这些共同完成同一业务不变量的文件。限流、营销、技术升级不要混进点赞 WIP 提交。

## 12. 可量化验收口径

| 维度 | 必须记录 | 禁止替代证据 |
| --- | --- | --- |
| 正确性 | 订单/关系/账本数量、唯一约束、库存、状态、恒等式 | 只看 HTTP 200 或进程未崩溃 |
| 性能 | 吞吐、P50/P95/P99、DB QPS/慢 SQL、Redis/MQ latency | 用库存耗尽后的快速拒绝吞吐冒充下单能力 |
| 可靠性 | backlog、oldest age、retry/DLQ/quarantine、恢复时间 | 只证明正常路径成功 |
| 缓存 | hit/miss/stale/fallback、重建次数、坏缓存行为 | 只展示 Redis 有 key |
| 隔离 | 匿名、消费者 token、缺权限、跨商户、停用账号 | 只检查前端菜单是否隐藏 |
| 升级 | fresh migrate、旧版本升级、停写回填、回滚开关 | 只在已有开发库启动一次 |
| 前端 | 错误码映射、64 位 ID、断线轮询、静态缓存切换 | 只做 Java 后端单测 |

任何“提升 90%”“P99 降低多少”的表述必须来自同一机器、同一数据、同一流量模型、重复多轮的 baseline/current 对比，并同时通过业务正确性 gate。

## 13. 面试叙述提纲

### 13.1 为什么不是简单换皮

教程项目的主要问题不在页面主题，而在管理写接口无独立身份、秒杀预约和消息不精确对应、长期 PROCESSING 无恢复、点赞身份只在 Redis、排行榜冷缓存会放大数据库。改造围绕这些可复现问题，而不是为了展示组件数量。

### 13.2 为什么没有做完整 SaaS

当前真实需求只有平台和单层商户数据范围。项目已把消费者登录与运营账号分开，用 merchant 作为商铺归属根，并让券和订单沿商铺继承范围。父子供应商、CUSTOM_SHOP 和任意角色编辑只有出现明确业务用例时才扩展，否则权限组合会增加越权风险。

### 13.3 Redis、MQ、MySQL 如何分工

MySQL保存长期业务事实；Redis承担会话、资格状态机和可重建读模型；RocketMQ负责跨事务时间边界的可靠传递。三者不追求伪分布式事务，而是通过唯一约束、精确状态、消费前校验、幂等、对账和隔离闭环。

### 13.4 为什么点赞不用每次直接更新博客表

同用户并发、HTTP 重试和 Redis 写失败会让 `+1/-1` 漂移。M4 已以用户/博客关系作为事实，只有关系真实变化才写不可变 outbox，worker 聚合更新 aggregate；热榜从 DB 快照构建，因此计数和用户身份都可审计。

### 13.5 为什么不立即升级 Spring Boot 3、Kafka、分库分表

技术升级本身不能回答一致性和权限问题。先让业务不变量、故障恢复和证据闭合；如果 Java 8 生态、安全支持或单库容量成为已测阻塞，再用独立阶段迁移，才能比较迁移前后并控制回归面。

## 14. 明确暂缓的事项及启动条件

| 暂缓事项 | 只有满足以下条件才启动 |
| --- | --- |
| Spring Boot 3 / Java 17 | M4-M7 全绿、工作区干净、建立兼容矩阵；单独分支迁移 Jakarta/依赖，不同时加业务功能 |
| 分库分表 | 目标数据量和压测下单库 CPU/IO/锁等待成为持续瓶颈，且索引、批处理、冷热归档已验证不足 |
| Redis Cluster | 秒杀全部多 key Lua 先统一 hash tag，设计在线数据迁移并通过真实 Cluster CROSSSLOT 测试 |
| 完整父子租户 | 出现平台下代理商/子供应商的真实资源继承和结算需求，并能定义冲突权限合并规则 |
| 自定义角色 UI | 固定三角色无法满足至少两个真实运营场景，且能测试角色变更后的 token/WS 即时失效 |
| 推荐系统/AI | 有稳定行为数据、离线评估集、召回/排序指标和降级策略；不能只调用一个模型接口 |
| 微服务拆分 | 单体模块边界稳定，某模块需要独立扩缩容/故障隔离，且能承担分布式事务和运维成本 |
| 更换 MQ | RocketMQ 在已测语义、吞吐或运维约束上成为确定阻塞，而非为了简历多一个关键词 |

## 15. 新会话执行清单

M5 已收口。新会话先核对本阶段提交链、`docs/m5d-reliability-results.md` 和工作区状态；没有
用户明确授权时不自动进入 M6。发布或演示前可复验 M5D，但必须使用新的 run-id 和专用依赖，
保留 F5 全量预检、consumer-level/Canal E2E 边界以及历史负面证据。

### 可复制到新会话的提示词

```text
请先阅读 /home/sd101t/IdeaProjects/hm-dianping/docs/modernization-roadmap.md 和
docs/m5d-reliability-results.md，核对 branch、HEAD、status、diff 与 M5D 提交链，不覆盖用户改动。
M5A--M5D 已完成；未经用户明确授权不要实施 M6 或技术栈升级。如只复验 M5D，必须使用新的
run-id 专用依赖，保留严格 F5 门禁、全部负面证据和 consumer-level/Canal E2E 边界。
```

## 16. 路线完成的判定

这个项目不需要做到“功能和中间件越多越好”。达到以下结果即可认为改造主线完整：

- 商户后台身份、权限和数据范围可证明无越权；
- 秒杀在重复、延迟、永久失败和超龄状态下有可解释的收敛路径；
- 点赞身份、聚合计数和热榜在重试及缓存丢失后仍能恢复；
- 高风险入口有限流，依赖故障有明确 fail-open/fail-closed 与降级顺序；
- 至少一条标签/任务/发券业务链复用了既有商户和可靠性底座；
- 正常压测、故障注入、数据不变量和恢复时间都有可重复证据；
- README、运行手册和面试表述不夸大测试边界。

完成这些之后，继续升级框架、拆服务或分库分表都应被视为新的独立课题，而不是当前项目“还不够高级”的补丁。
