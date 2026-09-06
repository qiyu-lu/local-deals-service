# 04. 点赞关系、Outbox 与 generation-fenced 热榜：源码级精读

## 业务场景

用户对博客进行 like/unlike，接口要幂等、可查询“谁点了赞”，博客列表还要按点赞数分页。热点读不能每次都扫 MySQL，但 Redis 热榜重建、发布或故障时不能把错误排序当成正确结果。

## 旧方案

教程/旧基线把 `tb_blog.liked` 作为主要计数，并直接操作 Redis ZSET 或把用户身份放在 Redis 集合中。它容易做出“计数变了”的演示，却难以同时回答用户关系、重复请求、数据库提交后 Redis 未更新、worker 重复消费和热榜重建竞态。

## 问题

点赞请求若先写 Redis 再写 MySQL，进程崩溃会留下无法审计的关系；若只更新 `tb_blog.liked`，不能知道具体用户，也难以在批量/重试时判断 delta 是否重复。热榜 builder 和 reader 若没有 generation fence，旧 builder 可能覆盖新榜；坏 metadata、空榜和 Redis 错误也不能返回“看似成功”的错误排序。

## 业务不变量

- `tb_blog_like(blog_id,user_id)` 是用户关系的持久事实；同一用户对同一 blog 的 desired state 重复设置是 no-op。
- 关系变更和一条 `+1/-1` `tb_blog_like_outbox` 在同一 MySQL 事务内提交；不先把 Redis 当作写入成功。
- batch worker 只选 pending 行并按 blog 聚合，更新 `tb_blog.liked` 时要求非负；只有本批选中的 outbox 行才标记 processed。
- `liked` 应满足 `legacy_liked_offset + active relation count` 的 cutover 关系；marker 未完成时写/worker gate 不放开。
- 热榜是可重建的 bounded top-K derived model。发布时 generation、count、capacity、ready、publishedAt 一起由 Lua fence；旧 builder 不能发布新 generation 之前的 staging。
- 热榜读到未 ready、过期、坏 member、generation/count 不一致或 Redis 错误，必须 MySQL fallback，而不是返回不可信页面。
- DB fallback 排序 `liked DESC,id DESC` 是正确性回退；Redis ZSET 只承担加速。

## A. 点赞 desired-state 事务

发起者是已登录消费者；`PUT /blog/{id}/like` 表达最终“已点赞”，`DELETE /blog/{id}/like` 表达最终“未点赞”。两条写路由不在 public allowlist，先由普通用户登录拦截器建立 `UserHolder`。迁移兼容路由 `PUT /blog/like/{id}?liked=true|false` 也必须显式携带 desired state；参数缺失由 MVC 返回 400，不恢复不可重试的 toggle。

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `BlogController#likeBlog/unlikeBlog` | blogId | `UserHolder` 已由拦截器准备 | 无 | 无 | 调 `setBlogLiked(id,true/false)` | 未登录在 Controller 前拒绝 |
| 2 | `BlogServiceImpl#setBlogLiked` | blogId、desired | `BlogLikeProperties#writeEnabled`、current userId | 指标 | 无事务 facade | 调 command；changed/unchanged 返回 HTTP 200 + `Result.ok(BlogLikeCommandResult)` | gate off→503；NOT_FOUND→404；其他 RuntimeException→500 |
| 3 | `BlogLikeCommandService#setLiked` | blogId、userId、desired | `SELECT id FROM tb_blog ... LOCK IN SHARE MODE` | 无 | public `@Transactional`；blog parent 共享行锁 | blog 存在并阻止并发 delete 越过检查 | blog 不存在→NOT_FOUND、零写入 |
| 4 | 同上 | desired=true | `tb_blog_like` PK | insert relation | 同一事务；PK `(blog_id,user_id)` 仲裁并发 PUT | insert 1→changed | `DuplicateKeyException`→unchanged；异常事务回滚 |
| 5 | 同上 | desired=false | exact relation | delete relation | 同一事务 | delete 1→changed | delete 0→unchanged |
| 6 | 同上 | changed、delta | 无 | insert `tb_blog_like_outbox(blog_id,+1/-1)` | 同一事务 | exactly 1 event，方法返回后 commit | insert 失败/行数异常→关系与 event 全部回滚 |

PUT/DELETE 可安全重试，因为请求描述目标状态；toggle 在客户端超时重试时可能反向操作。重复 PUT 的 PK DuplicateKey 被解释为“目标已满足”，重复 DELETE 的 affectedRows=0 同理，二者都不产生 outbox。这里捕获 DuplicateKey 是关系层 no-op，不是把任何数据库唯一键错误都吞成成功。

父 blog 的共享锁允许多个共享读锁并行，同时与删除 parent 所需的排他锁互斥，避免“存在检查通过、blog 被删、随后写 relation/outbox”的竞态；V8 外键仍是最终完整性边界。`writeEnabled` 是 cutover/维护 gate：`BlogServiceImpl` 映射为 503，command 自身也二次 fail closed。它不等于系统完成 cutover，生产启动还由 marker guard 检查。

关键失败现场：outbox insert 失败时整个 command 事务回滚，relation 不存在变化；关系 insert 成功但方法提交前进程失败也回滚；commit 已成功但 HTTP 响应丢失时，客户端重放 desired state 得 unchanged，不会再产生 delta。

## B. Outbox 聚合事务

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `BlogLikeOutboxWorker#scheduledFlush` | scheduler tick | `workerEnabled` | 指标/日志 | 无 | enabled 时调用 `flushOnce` | disabled no-op；异常记录后等待下轮 |
| 2 | `BlogLikeOutboxWorker#flushOnce` | batch config | Redisson `blog-like outbox` lock | lock state | no-wait Redisson lock | winner 进入 DB batch | lock busy 本轮空结果；Redis 异常仍进入 DB batch |
| 3 | `BlogLikeOutboxBatchService#processNextBatch` | positive batchSize | pending rows，按 id 的有限前缀 | 对所选行加锁 | public `@Transactional(rollbackFor=Exception, isolation=READ_COMMITTED)`；`FOR UPDATE` | 当前事务独占本批 event rows | 空队列返回 0；SQL 异常整体回滚 |
| 4 | 同上 | selected rows | event id/blogId/delta | 内存按 blogId 排序聚合 | 仍在 DB 事务 | delta 仅允许 ±1；同 blog net delta 合并 | 非 ±1 立即抛错，不 update/marker |
| 5 | 同上 | per-blog net delta | `tb_blog.liked` | `liked = liked + delta` | 条件 `liked + delta >= 0`；逐 blog exact row count | delta=0 不写；其他必须更新 1 行 | blog 缺失、将为负或行数异常→回滚 |
| 6 | 同上 | exact eventIds | 所选行仍 pending | 仅这些 id 写 `processed_time` | 同一事务 | marker 数等于 selected 数，aggregate 与 marker 一起 commit | marker 少写/失败→aggregate 一并回滚 |

`SELECT ... FOR UPDATE` 锁住本批真实 pending outbox 行；并发 worker 会等待后重新判断条件，而不是共同消费同一行。Redisson lock 只是跨实例减争用/削峰：拿不到时可以跳过，Redis 故障时更会直接退回 MySQL transaction，因此正确性不能归因于 Redis lock。

不能按 blogId 把“所有 pending”模糊标记，因为本事务只锁定并计算了有限 eventId；模糊 marker 会吞掉选取之后新提交的 delta。净 delta=0 表示同批正负抵消，aggregate 不写但本批 events 仍精确标记；非法 delta、非负更新失败或 row count≠1 均抛错。若 worker 在 aggregate 后、marker 前失败，两者同事务回滚，重试重新聚合；若 commit 后进程失败，processed 条件让已提交 event 不再入选，避免重复计数。

`BlogLikeOutboxCleanupService#cleanup` 只有限批删除超过 retention 的 processed evidence，捕获异常且永不触碰 pending。清理与聚合分离，避免 retention 故障阻断业务收敛，也避免把“表变小”误当成 pending 已处理。

## C. 热榜派生读模型

热榜入口 `GET /blog/hot?current=N` 是公共 GET。它返回博客列表的 HTTP 200 envelope；Redis hit 与 DB fallback 都是成功读取路径，不能从 HTTP 200 判断是否命中缓存。

### 读链与 fallback

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `BlogServiceImpl#queryHotBlog` | positive page | `BlogHotRankService#readPage` | hot-rank 指标 | 无 | safe hit 继续 hydration；ready empty 直接返回空列表 | page 非正返回业务 `Result.fail`（HTTP 200） |
| 2 | `BlogHotRankService#readPage` | page、topK/pageSize | meta ready/generation/count/capacity/publishedAt；live ZCARD/page；再次读 generation/count | 无 | Redis 读取，无快照事务，以前后 fence 检漂移 | 全部一致→按 ZSET 顺序返回 IDs | disabled/outside topK/not ready/stale/bad meta/member/count/live/Redis error→显式 miss |
| 3 | `BlogServiceImpl#queryHotBlogAdmitted` | rank result | hit 时 `listByIds` | 无 | DB_READ bulkhead | 所有 IDs 均存在则按 Redis ID 顺序重排 | 任一 blog 丢失或 rank miss→触发 warmup 并 DB fallback |
| 4 | 同上 DB fallback | page | `tb_blog ORDER BY liked DESC,id DESC` | fallback 指标 | 无 | 稳定分页 | DB bulkhead 满→429；DB 不可用→503/500 取决于封装异常 |
| 5 | `hydrateBlogList` | bounded blogs | 一次批量 user query；登录时一次 `findLikedBlogIds` | blog DTO 的 name/icon/isLike | read-only 查询 | 避免逐 blog 用户/点赞 N+1 | author 缺失只缺展示字段；查询异常失败请求 |

top-K 只缓存 `0 <= offset` 且完整页 end 不超过 `topK` 的页面；它不是全量分页事实。超过边界整页回 MySQL，避免半页 Redis/半页 DB 拼接。DB fallback 使用 V7 索引对应的 `liked DESC,id DESC`，同分按 id 保持稳定。

`ready=1` 但 `count=0`、`ZCARD(live)=0`、generation/capacity/publishedAt 合法，是显式发布的空榜 hit；`ready!=1` 是 cold/not-ready；`count>0` 但 live key 丢失、ZCARD 不符、metadata 非规范数字、future/stale publishedAt、读取期间 generation/count 变化都是 unsafe miss。Redis 异常也映射成 `REDIS_UNAVAILABLE` miss，而不是空集合。

### rebuild、generation fence 与 after-commit

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `BlogHotRankRefreshScheduler#rebuildIfEnabled` 或 `BlogHotRankWarmupService#triggerIfEnabled` | schedule/miss | refreshEnabled、本机 `AtomicBoolean` | warmup inFlight | 本机 singleflight | 有界异步调用 rebuild | disabled/inFlight/rejected 只跳过/记录 |
| 2 | `BlogHotRankService#rebuild` | topK | Redisson `{global}` refresh lock | 无 | no-wait cluster lock | winner 继续 | lock busy→`SKIPPED_LOCK_BUSY`，不查 MySQL |
| 3 | 同上 | lock winner | Redis INCR generation；MySQL top-K | generation；`temp:{generation}` ZSET + TTL | 非跨系统事务 | staging size 与候选一致 | Redis/MySQL/非法候选失败→删 temp best effort，FAILED |
| 4 | `blog_hot_rank_publish.lua` | live/meta/generation/temp | generation 与 temp ZCARD | empty 时删 live；非空 RENAME temp→live；原子写完整 metadata | 同一 hash tag 的 Lua | generation 未变且 count 合法→PUBLISHED | generation 已推进→删 stale temp、返回 0；temp 缺/数量错→拒绝 |
| 5 | `BlogServiceImpl#saveBlog` afterCommit → `addNewBlogAfterCommit` | committed blogId | ready/generation/meta capacity/live | `ZADD NX score=0`、bounded trim；若 newcomer 留在榜内则 INCR generation 并更新 meta generation/count | blog DB transaction 提交后的 callback；Redis Lua 原子 | 新 blog 可进入 ready top-K，并 fence 旧 DB snapshot builder | not ready/outside topK no-op；Redis 失败只日志，不影响已提交 blog |

generation fence 防止较早取得 MySQL 快照的 builder 在新博客 after-commit 或更新一代后覆盖较新的 live 榜。构建锁 busy 只代表另一 builder 可能工作，当前读请求仍同步 DB fallback；warmup 的本机 singleflight 与 Redisson lock 都是减少重复构建，不是榜单正确性事实。

## D. V8 stopped-write cutover

legacy 身份来自教程旧 key `blog:liked:{blogId}` ZSET：member 是 userId，score 是原点赞毫秒时间。V8 先把原 `tb_blog.liked` 复制到 `legacy_liked_offset`，因为仅有数字的部分不能凭空恢复用户身份。

| 阶段 | 动作 | 仲裁/校验 | 失败现场与停止线 |
| --- | --- | --- | --- |
| 1 冻结 | 网关停所有 like 写，停止全部旧节点，确认 ZSET 不再变化并保存 DB/Redis 回滚点 | 运营门禁；importer 只能核对 ZCARD 前后，不能识别同基数换成员 | 旧节点未停必须停止 cutover；不能滚动混跑 |
| 2 migration/import | 单个不接流量实例：write=false、worker=false、legacyBackfill=true、hot read/refresh=false | `BlogLikeProperties#validate` 强制 importer 与新写/worker 互斥 | 任一非法 key/member/time/missing blog/source change 使启动失败 |
| 3 `BackfillRunner` | SCAN canonical keys、按 bounded page 读取 ZSET | 记录 key/member；前后 ZCARD 与 scanned 必须一致 | Redis 源变化立即失败，不写 marker |
| 4 `ImportService#importBatch` | 锁 blog；`INSERT IGNORE` relation；按实际新增数等量减少 offset | public transaction；批内 user 唯一、timestamp 合法、offset 非负；`liked=offset+active` | 任何不变量失败整批回滚 |
| 5 marker | `BlogLikeCutoverService#recordCompleted` | 全库恒等式成立、pending outbox=0，写 `V8_LEGACY_REDIS_IMPORT/COMPLETED` | 缺 marker 时生产 guard 拒绝打开 write 或 worker |
| 6 开新链 | importer=false；先 write=true、worker=true、refresh=true、read=false | `BlogLikeCutoverGuard` 启动检查 marker | outbox 不收敛或榜构建失败时保持 read off，必要时关新写 |
| 7 canary read | 等待完整 generation，比较 MySQL/Redis ID、顺序、count、capacity、publishedAt，再逐步 read=true | reader 自身仍 fail-safe DB fallback | 坏榜不返回伪空，回退 DB |

`BlogLikeLegacyBackfillRunner` 负责读取 Redis、规范化/分页和汇总；`BlogLikeLegacyImportService` 负责每个 blog 的 MySQL 事务与 offset 恒等式；`BlogLikeCutoverService` 负责全局审计和 durable marker；`BlogLikeCutoverGuard` 只在非 test profile 阻止无 marker 的 write/worker 启动。四者不能互相替代。

当前协议不能普通滚动混跑：旧节点仍直接改 `tb_blog.liked` 和旧 ZSET，绕过 relation/outbox；新节点则以 relation/outbox 为唯一写事实。两种 writer 并存会破坏恒等式，而且 importer 对“ZCARD 不变但成员替换”不可见。

## 数据/状态模型

| 对象 | 事实/派生 | 关键状态 |
| --- | --- | --- |
| `tb_blog_like` | MySQL durable user relation | PK `(blog_id,user_id)` |
| `tb_blog_like_outbox` | MySQL durable delta queue | `processed_time IS NULL` 表示 pending；delta 只能 ±1 |
| `tb_blog.liked` | MySQL aggregate used by normal query | batch worker 更新，不能低于 0 |
| `tb_blog_like_cutover` + `legacy_liked_offset` | migration/import marker | stopped-write import 未完成时 gate 关闭 |
| Redis hot rank ZSET + metadata | rebuildable top-K read model | generation、ready、count、capacity、publishedAt、TTL |

`BlogLikeCommandService` 用 desired state 而不是“toggle”：重复 PUT/DELETE 的结果可重试且不会重复产生 delta。`queryBlogLikes` 从 MySQL 读 durable relation；返回的 top five 不是 Redis 事实。

## 异常与恢复

- blog 不存在先以 MySQL 关系检查，返回 404；不能因为 Redis 热榜未命中把不存在和空榜混淆。
- 关系插入/删除或 outbox 写入失败时事务整体回滚；不能留下已改变关系但没有 delta 的半状态。
- Redis lock 优化失败时 worker 回到 DB locking；worker 的正确性边界是 MySQL 事务，不是 Redisson lock。
- worker 重复运行、部分异常或重放时，selected marker 和聚合约束防止重复加计数；坏 delta/聚合不继续静默吞掉。
- 热榜 builder lock busy、stale builder、发布 generation 过期、空榜 ready、坏 metadata/member 都有显式结果；读侧回 MySQL。
- Redis 被清空时热榜可以从 MySQL rebuild；这不是 Redis 数据丢失已达到生产 RPO 的证明。
- V8 legacy importer 使用 stopped-write、验证用户/时间、批量导入和 marker；不允许在旧写节点仍运行时直接开启新关系写入。

## 调试现场检查顺序

1. 从用户/blogId 查 `tb_blog_like(blog_id,user_id)`，确认 desired relation；不要先以 Redis ZSET 或 `tb_blog.liked` 猜用户关系。
2. 查该 blog 的 `tb_blog_like_outbox`，按 id 分开 pending/processed，核对 delta 仅 ±1；不要手工模糊标记整 blog。
3. 核对 `tb_blog.liked = legacy_liked_offset + COUNT(tb_blog_like)`；若不等，先停 write/worker 并保留 pending/processed 现场。
4. worker 故障按 selected eventId 查锁等待、aggregate 条件更新 row count 和 marker count；Redisson lock busy 只是竞争信号。
5. 热榜按 `generation`→meta 五字段→live ZCARD→member 格式/顺序检查；任一不一致应确认请求走 DB fallback，而不是修成空数组。
6. rebuild 查 refresh/warmup gate、本机 inFlight、Redisson lock、temp generation/TTL 和 Lua return；stale builder 的正确结果是丢弃，不是强行发布。
7. cutover 问题先确认旧 writer 全停、legacy ZSET 不再变化，再查 offset/active/pending/marker；无 durable marker 不打开新 write/worker。

## 方案取舍

把写正确性放到 MySQL + Outbox，代价是多一张关系表、worker 和 cutover；换来的好处是可审计、可重放、可回答用户维度问题。把热榜定义为 derived cache，牺牲少量实时性，换取可重建和 DB fallback；generation fencing 比“最后一个 builder 赢”更保守，但避免旧快照覆盖新榜。

当前 top-K 是 bounded read model，不是全量排行榜；正常分页超过 top-K 或热榜不安全时，必须接受 MySQL 读取成本或明确未命中。

## 代码导航

| 关注点 | 路径与方法 |
| --- | --- |
| HTTP/查询 | [`BlogController`](../../src/main/java/com/localdeals/controller/BlogController.java)、[`BlogServiceImpl.queryHotBlog/queryBlogLikes/setBlogLiked`](../../src/main/java/com/localdeals/service/impl/BlogServiceImpl.java) |
| durable command | [`BlogLikeCommandService.setLiked/findTopFiveUserIds`](../../src/main/java/com/localdeals/service/BlogLikeCommandService.java) |
| aggregate worker | [`BlogLikeOutboxBatchService.processNextBatch`](../../src/main/java/com/localdeals/service/BlogLikeOutboxBatchService.java)、[`BlogLikeOutboxWorker`](../../src/main/java/com/localdeals/service/BlogLikeOutboxWorker.java) |
| hot rank | [`BlogHotRankService.readPage/rebuild/addNewBlogAfterCommit`](../../src/main/java/com/localdeals/service/BlogHotRankService.java)、[`BlogHotRankWarmupService`](../../src/main/java/com/localdeals/service/BlogHotRankWarmupService.java) |
| Lua fence | [`blog_hot_rank_publish.lua`](../../src/main/resources/lua/blog_hot_rank_publish.lua)、[`blog_hot_rank_add_new.lua`](../../src/main/resources/lua/blog_hot_rank_add_new.lua) |
| migration/import | [`V7__blog_hot_rank_indexes.sql`](../../src/main/resources/db/migration/V7__blog_hot_rank_indexes.sql)、[`V8__durable_blog_likes.sql`](../../src/main/resources/db/migration/V8__durable_blog_likes.sql)、[`BlogLikeLegacyImportService`](../../src/main/java/com/localdeals/service/BlogLikeLegacyImportService.java)、[`BlogLikeLegacyBackfillRunner`](../../src/main/java/com/localdeals/init/BlogLikeLegacyBackfillRunner.java) |

## 验证证据

本轮没有执行测试。代表性源码契约如下：

| 测试 | fixture | 动作 | 核心断言 | 证据层级 | 未证明内容 |
| --- | --- | --- | --- | --- | --- |
| `BlogLikeCommandServiceTest` | mock JdbcTemplate、write gate | like/unlike、重复状态、missing blog、outbox failure、批量 liked lookup | relation 与 ±1 event 配对；重复 no-op；异常传播；读取走 durable relation | 单元 | Spring 事务真实回滚、MySQL 锁/唯一键竞争 |
| `BlogLikeReliabilityIT` | `@SpringBootTest(test)`；创建自有 blog/user；用 DB trigger 注入 outbox/marker 失败 | 重复与并发 like/unlike；处理 outbox；并发 batch | 一条 relation/event；恒等式；outbox failure 回滚 relation；marker failure 回滚 aggregate；并发 consumer exactly once | MySQL 集成契约 | 类本身不创建专用 MySQL；不证明 Redis、HTTP 或生产吞吐 |
| `BlogLikeOutboxBatchServiceTest` | mock selected rows/update counts | empty、跨 blog 聚合、delta=0、非法 delta、不安全 aggregate | 只标 exact IDs；net zero 不写 aggregate；坏数据在 marker 前失败 | 单元 | InnoDB wait/deadlock、真实 rollback |
| `BlogHotRankServiceTest` | mock Redis/JDBC/Redisson/metrics | read 各 metadata 分支、rebuild、stale generation、lock busy、afterCommit Lua failure | explicit miss；完整页 top-K；padded ID；stale builder 不发布；Redis hook 不外泄 | 单元 | Lua 在真实 Redis 的原子行为、DB fallback Controller 链 |
| `BlogHotRankRedisIT` | `@SpringBootTest` 指定 read/topK/page 配置；清理固定 rank keys | 直接发布 staging、插新 blog、破坏 metadata | stale builder fence；NX/trim；empty ready 是 hit；corrupt meta 整页 miss | Redis 集成契约 | 类本身不创建/拥有 Redis；不证明集群、MySQL hydration 或 HA |
| `BlogServiceAfterCommitTest` | 自定义 transaction manager + mock dependencies | commit/rollback `saveBlog` | 只在 commit 后调用 hot-rank hook；rollback 不调用 | 单元事务同步契约 | 不写真实 blog/Redis，不证明 follower feed 交付 |
| `BlogLikeLegacyImportServiceTest` | mock blog lock、before/after counts、offset update | 导入已有/新增 identity；超出 offset | 只按实际新增数降 offset；负 offset 风险 fail closed | 单元 | Redis 扫描、真实 FK/事务 |
| `BlogLikeLegacyBackfillRunnerTest` | mock RKeys/ZSET/import service | canonical key 分页扫描、非法 key | bounded delegation 与计数；非法 key 在 DB mutation 前失败 | 单元 | 同基数换成员、真实启动/marker |
| `BlogLikeCutoverGuardTest` | properties + mock cutover service | gates closed/open、marker true/false | 任一 write/worker gate 打开都要求 marker | 单元 | test profile 中 guard 被排除；不证明运维已停旧节点 |

正式 runner 可为 `BlogLikeReliabilityIT`、`BlogHotRankRedisIT` 注入专用 MySQL schema 和 Redis 端口；测试类自身没有 Testcontainers 依赖，也不创建或拥有容器。脱离 runner 单独执行 IT 时必须核对实际连接目标。M4/M7 result 文档属于历史限定环境证据；其中计数、耗时和退出结果不是本轮重跑，也不是生产容量/SLA。

## 不能证明的边界

- 不能把 Redis ZSET、`tb_blog.liked` 或 hot-rank metadata 单独说成用户点赞关系真相。
- 不能声称跨多实例 worker 的整体吞吐、Redis HA/RPO 或实时排行榜 SLA 已验证。
- 不能用热榜 hit rate、HTTP 200 或 outbox worker 进程存活代替关系、delta、aggregate 的一致性检查。
- 搜索 ES 的博客读模型仍是最终一致的独立链路，不因 hot rank 正确就自动变成强一致。
- `UNVERIFIED`：当前源码没有证明真实线上曾执行 stopped-write cutover，也不能静态确认旧 `blog:liked:*` 在某环境是否仍有成员。
- `BOUNDARY`：热榜只在读取时发现坏快照并 DB fallback；它不自动修复业务 aggregate，aggregate 仍须从 relation/offset/outbox 审计。

## 项目特色摘要

面向点赞关系、聚合计数与热榜缓存可能漂移的问题，以持久关系和事务 Outbox 固化事实，按批聚合计数，并用代际隔离、完整性校验与数据库回退维护可重建热榜。正式简历表述与证据映射见 [07. 简历与面试定稿](07-resume-and-interview.md)。
