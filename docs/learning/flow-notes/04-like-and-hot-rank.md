# 04. 从点赞目标状态到可重建热榜

## 一、用户把点赞关系改成希望得到的状态

用户点“点赞”时，请求带的是 Blog ID，希望最终处于已点赞状态；点“取消点赞”时仍带同一个 Blog ID，希望最终处于未点赞状态。新点赞写入由 `write-enabled` 控制，默认关闭；非 test 环境打开它之前，还必须完成后文的 V8 旧数据切换并留下完成标记。

- 点赞请求通过 `PUT /blog/{id}/like` 进入，取消点赞通过 `DELETE /blog/{id}/like` 进入。Controller 分别把 `true` 或 `false` 交给 `BlogServiceImpl.setBlogLiked`，所以请求表达的是目标状态，不是让服务端无条件翻转一次。
  - 兼容入口 `PUT /blog/like/{id}` 也必须显式带 `liked=true|false`，不再接受无法安全重试的无参数 toggle。
- 请求进入 Controller 前，身份恢复拦截器根据 token 把用户放进 `UserHolder`，登录拦截器再拒绝没有身份的写请求。业务代码从 `UserHolder` 取得当前用户 ID，Blog ID 只取路径参数，不接受客户端自报点赞用户。
- `BlogServiceImpl` 先检查 `write-enabled`，关闭时抛出映射为 503 的“点赞功能维护中”；打开后才从 `UserHolder` 取 userId，并调用 `BlogLikeCommandService.setLiked(blogId,userId,desired)`。
  - command 方法本身再次检查写开关，防止其他调用方绕过外层门禁；接着要求 blogId 和 userId 都是正数，不满足就抛参数异常。
- command 开启 MySQL 事务，先执行 `SELECT id FROM tb_blog WHERE id=? LOCK IN SHARE MODE`。它拿请求中的 blogId 查询并共享锁住父 Blog，把查询结果用于后面的关系写入。
  - 查不到就构造 `NOT_FOUND(desired)` 返回，不写关系和 Outbox；外层收到 NOT_FOUND 后记录结果并抛出 404。
  - 查到后，共享锁一直持有到事务结束，用于缩小“存在检查刚通过，Blog 就被并发删除”的窗口；V8 外键仍是最终完整性约束。
- 希望最终已点赞时，command 执行 `INSERT INTO tb_blog_like(blog_id,user_id,liked_at) VALUES(?,?,CURRENT_TIMESTAMP(3))`，把 blogId 和 userId 组成长期关系。
  - 插入返回 1 表示关系确实新增；`(blog_id,user_id)` 主键冲突会被捕获并返回 `UNCHANGED(true)`，调用方不再执行 Outbox 插入。
- 希望最终未点赞时，command 执行 `DELETE FROM tb_blog_like WHERE blog_id=? AND user_id=?`。
  - 删除 1 行表示关系确实取消；删除 0 行表示本来就没有点赞，返回 `UNCHANGED(false)`。当前代码对插入返回 0 也按 `UNCHANGED(true)` 处理；行数既不是 0 也不是 1 时才抛异常。
- 只有关系恰好改变一行，command 才执行 `INSERT INTO tb_blog_like_outbox(blog_id,delta,create_time)`：点赞的 delta 是 `+1`，取消点赞是 `-1`，时间使用 MySQL 当前毫秒时间。
  - 这里的 Outbox 是同一个 MySQL 中的持久待办，后面的 worker 根据 blogId 和 delta 更新聚合。插入必须恰好影响一行，否则抛异常。
  - 关系变化和 Outbox 插入位于同一事务。Outbox 写失败、影响行数异常或提交失败时，关系也回滚，异常返回给当前请求。
- 事务提交后，结果沿 `BlogServiceImpl`、`BlogController` 返回 `Result.ok(BlogLikeCommandResult)`。结果只说明期望状态、关系是否改变以及 `CHANGED/UNCHANGED`，不返回一个猜测出来的即时点赞数。
  - 到这里同步请求已经结束。成功当时成立的是关系达到目标状态；如果关系有变化，对应待办也已入库。`tb_blog.liked` 和 Redis 热榜都可以稍后再变化。

## 二、聚合 worker 把待办合并进 MySQL 计数

这是点赞请求提交后的后台任务，不由用户请求同步等待。`worker-enabled` 默认关闭；启用后，调度器默认在启动 5 秒后执行，前一轮结束 200 毫秒后再运行，每批最多取得 500 条待办。

- `BlogLikeOutboxWorker` 每轮先无等待地尝试 Redisson 锁 `lock:blog:like:outbox`，用它减少多个实例同时争抢数据库。
  - `getLock` 或 `tryLock` 抛异常时，worker 记录 `REDIS_LOCK_ERROR`，不再依赖 Redis，直接调用数据库批处理。
  - `tryLock` 正常返回 false 时记录 `LOCK_BUSY`，本轮返回处理 0 条，等下次调度；返回 true 才执行数据库批次，最后检查锁仍由当前线程持有再释放，释放失败只记日志。
- `BlogLikeOutboxBatchService.processNextBatch(batchSize)` 开启 READ COMMITTED 事务，先要求 batchSize 大于零，再执行下面的锁定查询：
  - `SELECT id,blog_id,delta FROM tb_blog_like_outbox WHERE processed_time IS NULL ORDER BY id LIMIT ? FOR UPDATE`。输入的 LIMIT 来自配置，查询结果同时给出本批事件 ID、目标 Blog 和增量。
  - 查询为空就返回 `BatchResult(0,0,[])`，外层记录 EMPTY；查询到记录后，`FOR UPDATE` 把这些真实 pending 行锁到事务结束。并发 worker 可能等待，但不能同时提交同一批事件。
- worker 顺序遍历选中行，检查每个 delta 只能是 `+1` 或 `-1`，把全部原始事件 ID 保存到列表，再用按 Blog ID 排序的 `TreeMap` 合并净增量。
  - 例如同一 Blog 的事件是 `+1、+1、-1`，映射中得到净增量 `+1`，但事件 ID 列表仍保留三项，后面要逐项标记。
  - 发现约定外 delta 就立刻抛异常；此时还没有更新聚合，也不会标记事件。
- worker 按 TreeMap 的 Blog ID 固定顺序处理净增量。净值为零时跳过 Blog 更新；非零时执行 `UPDATE tb_blog SET liked=CAST(liked AS SIGNED)+? WHERE id=? AND CAST(liked AS SIGNED)+?>=0`。
  - 把无符号 liked 转成有符号数后再计算，取消点赞的负增量才能参与非负判断。SQL 必须恰好更新一行；Blog 不存在、结果会变负或行数异常都抛错，让本批回滚。
- 所有聚合更新通过后，worker 用本批事件 ID 拼出 IN 条件，执行 `UPDATE tb_blog_like_outbox SET processed_time=CURRENT_TIMESTAMP(3) WHERE processed_time IS NULL AND id IN (...)`。
  - `processed_time IS NULL` 防止覆盖已处理状态，影响行数必须等于选中事件数；少标一条也会抛异常。
  - 聚合 SQL 和精确 marker 位于同一事务。全部通过后才返回“处理事件数、更新 Blog 数、事件 ID 列表”并提交；更新计数后、marker 前失败会一起回滚，下轮重新选取。
- 调度入口捕获批处理异常并记录日志，pending 事件仍留在 MySQL，交给后续调度重试。已处理事件的定时清理只删除超过保留期的 processed 行，不会触碰 pending。

## 三、热榜 builder 从 MySQL 聚合构建一代新榜

热榜重建是另一项后台任务，不在每次点赞或 worker 提交时同步执行。`refresh-enabled` 默认关闭；启用后，定时刷新默认在启动 10 秒后运行，之后每轮结束 30 秒再运行。不可信的读请求也可以在当前 JVM 内触发一次异步 single-flight 重建，但不会等待它完成。

- `BlogHotRankService.rebuild` 先无等待地尝试热榜专用 Redisson 锁，减少多个 builder 重复查询和写榜。
  - 锁忙时本次返回 `SKIPPED_LOCK_BUSY`；获取锁句柄、尝试加锁或后续构建的 Redis 操作抛异常时，本次返回 FAILED，不会像 Outbox worker 那样绕过锁继续构建。最后释放锁失败只记录告警，不会推翻已经得到的构建结果。
- 拿到锁后，builder 先对 Redis generation 计数加一，取得本次递增版本号，再从 MySQL 按 `liked DESC, id DESC` 读取 top-K，默认最多 1000 条。
  - generation key 是 `blog:hot:{global}:generation`。INCR 返回空或非正数就抛异常；正常值既用于拼临时 key，也作为稍后发布的版本凭据。
  - 接着执行 `SELECT id,COALESCE(liked,0) AS liked FROM tb_blog ORDER BY liked DESC,id DESC LIMIT ?`。任何候选 blogId 非正或 liked 为负都会让构建失败。
  - generation 在查询 MySQL 之前取得，后面的发布脚本才能识别这份数据库快照是否已经失去发布资格。
- builder 把候选写到 `blog:hot:{global}:temp:{generation}` 临时 ZSET，而不是直接改正式榜。score 是 MySQL 聚合点赞数，member 是补齐到 19 位的 Blog ID。
  - 写入前先删同名临时 key；候选为空就不创建 ZSET。非空时一次加入全部 tuple，Redis 返回的新增数必须等于候选数，再把临时 key 的 TTL 设为 `max-stale`，默认 2 分钟。
  - 候选数超过 top-K、ZADD 数量不符或 TTL 设置失败都会抛异常，外层尽力删除临时 key 并返回 FAILED。
- 临时榜完成后，Java 调用 `blog_hot_rank_publish.lua`。四个 KEYS 依次是正式 ZSET、metadata Hash、generation String 和本代临时 ZSET；四个 ARGV 依次是本代 generation、候选数、当前 Java 毫秒时间和 top-K capacity。所有 key 使用 `{global}` hash tag，使 Redis Cluster 能在同一 slot 执行脚本。
  - 脚本先 GET 全局 generation；若不等于传入版本，说明 builder 已过期，于是删除它的临时 key 并返回 0。
  - 版本仍有效时，把候选数和 capacity 转成数值，要求 `0 <= 候选数 <= capacity` 且 capacity 为正；不合法直接返回 Redis error，Java 将本次构建记为 FAILED。
  - 候选数为零时，脚本删除正式榜和临时 key；候选非零时，要求临时 key 存在且 ZCARD 等于候选数。缺少临时榜返回 `-1`，数量不符时删除临时榜并返回 `-2`，Java 都按异常构建处理。
  - 非空候选检查通过后，脚本用 RENAME 把临时榜变成正式榜；RENAME 会把临时榜的 TTL 一起带过去。空候选则沿用前面的删榜结果。两种分支随后都向 metadata Hash 一次写入 `ready=1、generation、count、capacity、publishedAt`，最后返回 1。检查、换榜和写元数据都在同一次 Lua 原子执行中。
- 控制回到 Java：Lua 返回 1 就返回 PUBLISHED；返回 0 就返回 STALE_GENERATION；空值、`-1/-2` 或其他结果会抛异常，清理临时 key 并返回 FAILED。最后释放构建锁，释放失败只记告警。
  - 正式榜此时是一份从 `tb_blog.liked` 得到的完整有限快照，不代表刚提交但仍在 pending Outbox 中的关系变化已经排进榜单。

## 四、用户读取热榜时验证 Redis，再回库补全或回退

用户调用 `GET /blog/hot?current=N`，带的是页码，希望得到按当前聚合点赞数排序的一页 Blog。`read-enabled` 默认关闭；关闭、超出完整 top-K 或 Redis 快照不可信时，这条读请求都走 MySQL 回退，而不是返回伪造的空榜。

- `BlogServiceImpl.queryHotBlog` 先检查页码为正，再把页码交给 `BlogHotRankService.readPage`。readPage 先看读开关，然后计算 `offset=(page-1)×pageSize` 和当前页的末尾位置。
  - 开关关闭返回 `READ_DISABLED`；起点已超出 top-K，或这一页不能完整落在 top-K 内，返回 `OUTSIDE_TOP_K`，外层随后走数据库。readPage 被直接传入非法页码时会返回 `INVALID_PAGE`；当前 HTTP 调用链已经在外层返回“页码必须为正数”的业务失败，不会拿非法页码继续回库。
- 页范围可用时，readPage 从 `blog:hot:{global}:meta` 先读 ready；不等于字符串 `1` 就返回 `NOT_READY`。接着依次读取并解析 generation、count、capacity、publishedAt。
  - generation、capacity、publishedAt 必须是规范正整数，count 必须是规范非负整数，capacity 还要等于当前 top-K；不满足返回 `BAD_METADATA`。
  - publishedAt 不能晚于 Java 当前时间，并且 `当前时间-publishedAt` 不能超过 `max-stale`；否则返回 `STALE`。
- 元数据通过后，服务对正式 ZSET `blog:hot:{global}:live` 执行 ZCARD，要求它等于 metadata count 且 count 不超过 top-K，再以计算出的 offset 和 end 执行 ZREVRANGE。
  - ZCARD 或成员集合返回空引用、基数不符，都返回 `INCONSISTENT_SNAPSHOT`；正常的空集合仍会继续后面的前后版本检查。
- 取出成员后，服务再次读取 metadata 中的 generation 和 count，与读取前保存的字符串逐项比较，再把每个 member 解析为恰好 19 位的正 Blog ID。
  - 前后任一值改变说明读取期间发生切版，返回 `INCONSISTENT_SNAPSHOT`；member 长度、字符或数值不合法返回 `BAD_MEMBER`。整个 Redis 过程抛异常时返回 `REDIS_UNAVAILABLE`，不会把异常伪装成空榜。
- readPage 通过上述检查且返回的本页 ID 列表为空时，`queryHotBlog` 就直接返回空列表，不申请数据库许可。这既可能是 `ready=1、count=0` 的空榜，也可能是榜内有数据但当前页已超出实际数量。其他结果先进入 DB_READ 并发舱；非空命中只得到有序 Blog ID，接着批量回 MySQL 补 Blog 正文。
  - 当前没有额外比较本页实际成员数与按 count、offset 推算的应有数量，所以这些检查也不能证明读取过程中完全没有发生过期或成员变化。
  - `listByIds` 的结果先转成 `blogId -> Blog` 映射，再按 Redis ID 列表逐项取回；映射数量不等或任一 ID 缺失，就返回 null，让外层放弃整页 Redis 结果。
  - 全部存在时，服务收集作者 ID，一次批量查询用户并补 name/icon；当前用户已登录时，再用一次 `WHERE user_id=? AND blog_id IN (...)` 查询 `tb_blog_like`，给每篇 Blog 填充 `isLike`。
- 如果 Redis 返回 MISS 或详情映射不完整，服务在 DB_READ 许可内直接按 `liked DESC, id DESC` 查询 MySQL，再做相同的作者与点赞状态补全。
  - 回库前先调用 `BlogHotRankWarmupService.triggerIfEnabled`：刷新开关关闭或本 JVM 的 `AtomicBoolean inFlight` 已为 true 时直接返回；否则用 CAS 占住本机 single-flight，并把 rebuild 提交给应用线程池。
  - 异步任务结束时在 finally 中清除 inFlight；线程池拒绝任务时也先清除再记日志。当前请求不等待 rebuild，而是继续查询本次 MySQL 结果。
  - 数据库查询按当前已聚合的 `liked` 排序，不会临时计算 pending Outbox；DB_READ 许可拿不到或数据库异常时，由并发舱/全局异常处理结束当前请求，而不是退回不可信 Redis 数据。

## 五、关系、聚合和热榜保存的是三种数据

前四段流程分别在不同时间推进，不能把三层数据当成一份必须实时相等的状态。

| 层次 | 保存位置 | 它回答的问题 | 允许怎样滞后 |
| --- | --- | --- | --- |
| 点赞关系 | `tb_blog_like` | 某用户现在是否点赞某 Blog | 写事务成功后就是关系事实 |
| 聚合计数 | `tb_blog.liked` | 已处理到的点赞总数，供 MySQL 排序 | 可以落后于 pending Outbox |
| 聚合进度 | `tb_blog_like_outbox.processed_time` | 哪个 `+1/-1` 已经计入聚合 | pending 由 worker 后续处理 |
| Redis 热榜 | 正式 ZSET 与五项 metadata | 最近一次可信构建的有限 top-K | 可以落后于聚合，过期或损坏就回退 |

旧数据还多一项 `tb_blog.legacy_liked_offset`，保存 V8 以前只有计数、无法归属到具体用户的部分。存在 pending Outbox 时，应按下面的关系理解：

```text
某篇 Blog 的 liked + 这篇 Blog 所有未处理 Outbox 的 delta
  = 这篇 Blog 的 legacy_liked_offset + 当前点赞关系数
```

只有没有 pending Outbox 时，才简化为 `tb_blog.liked = legacy_liked_offset + 当前关系数`。

假设用户 21 给 Blog 100 点赞，原聚合数为 37。写请求提交后，关系表已有 `(100,21)`，Outbox 多一条 pending `+1`，`tb_blog.liked` 和热榜分数仍可能是 37；详情查询已经能从关系表得到 `isLike=true`。worker 提交后聚合变为 38，下一次完整热榜重建后 Redis 排名才跟上。

## 六、临时榜、generation 和同分排序解决什么

临时榜解决的是半成品可见：builder 写成员期间，读者仍读取旧正式榜，只有候选全部写完才由 Lua 原子替换。generation 解决的是完整但过时的结果晚到，它不能由“曾经拿到过构建锁”代替。

假设任务 A 先启动并取得 generation 40，随后因长时间停顿、锁续期失效或 Redis 故障转移失去锁；任务 B 后启动，以 generation 41 查询了更晚的 MySQL 状态并先发布。A 最后才恢复时，发布 Lua 发现全局 generation 已不是 40，于是删除 A 的临时榜并拒绝覆盖。锁减少正常重复工作，generation fence 才拒绝已经失去时序资格的旧结果。

新 Blog 提交后还有一段独立 Lua：它只对已经 ready 的榜执行 `ZADD NX score=0` 并裁剪 top-K。只有新成员最终留在榜内时才推进 generation，用来拒绝此前已经读完 MySQL 的旧 builder。这是 Blog 发布后的尽力更新，不会响应每一次点赞计数变化。

MySQL 在点赞数相同时按 Blog ID 降序。Redis 逆序读取同分 ZSET 时按 member 逆字典序；把正 Blog ID 补齐到 19 位后，字符串顺序才和数值 ID 顺序一致，因此两边能得到同样的 ID 降序。

## 七、旧 Redis 点赞怎样切到当前关系表

教程旧方案的 `blog:liked:{blogId}` ZSET 曾保存点赞用户和点赞时间。当前正常点赞、取消和点赞查询已经不再读写这组 key；它只作为一次性 V8 旧身份导入的来源，不能再描述成当前主链。

- V8 先把原 `tb_blog.liked` 复制到 `legacy_liked_offset`。切换时必须停旧写和所有旧节点，再用一个不接流量的实例开启 `legacy-backfill-on-startup=true`；配置同时要求新写和 worker 都关闭。
- 启动导入器用 `blog:liked:*` 和配置的 scan count 遍历旧 key，从 key 后缀解析正 Blog ID；对每个 ZSET 先保存 ZCARD，再按配置 batch size 用 `rangeWithScores` 分页读取。
  - member 必须是没有前导零的正 userId；score 必须是有限、正整数毫秒时间，而且最多允许比当前 Java 时间快 5 分钟。任一 key、member、score 或 Redis 返回值非法就抛异常，不继续写完成标记。
- 每页转换成 `(userId,likedAtMillis)` 后，调用 `BlogLikeLegacyImportService.importBatch(blogId,likes)`。这个方法为单个 Blog 开启 MySQL 事务，先用 `SELECT liked,legacy_liked_offset ... FOR UPDATE` 锁住 Blog，再统计导入前的关系数。
  - 批内 userId 必须互不重复且 ID、时间为正；随后执行 `INSERT IGNORE` 写关系。因为批处理返回值可能无法给出精确新增数，方法重新查询“本批用户是否都已存在”，再用全量关系数的前后差得到实际 inserted。
  - 只有实际新增数大于零，才执行 `legacy_liked_offset=legacy_liked_offset-inserted`，WHERE 同时要求 offset 足够；这样把可归属计数从 offset 搬到关系表，`tb_blog.liked` 总值保持不变。
  - 最后重新读取 liked 和 offset，要求 `liked=offset+当前关系数`。任何影响行数或恒等式异常都会回滚这一批；正常则把 scanned、inserted、剩余 offset 和关系数返回导入器累计。
- 一个 Redis key 读完后，导入器再次读取 ZCARD，要求前后基数相同且实际扫描数等于基数；不一致说明停写条件没有成立，立即失败。这个检查只能发现基数变化，发现不了“删一个成员又加一个成员”的同基数替换，所以真正停掉旧 writer 仍是切换前提。全部 key 完成后，汇总 source key 数、成员数和实际新增数。
- `BlogLikeCutoverService.recordCompleted` 在事务中检查汇总计数非负且 imported 不超过 source members，再确认全库不存在 `liked <> legacy_liked_offset + relation count` 的 Blog，并要求 pending Outbox 数为零。
  - 通过后插入 marker key、COMPLETED、source key 数、source member 数、imported 数和完成时间。重复 marker 的 UPSERT 只保持原 marker key，不刷新旧计数；若 SQL 返回 0，还会重新查询确认 COMPLETED 标记确实存在，否则抛异常。
  - 方法正常返回后，启动导入器才记录完成日志；前面的任何异常都不会留下新的完成标记。
- 非 test 环境只要准备打开新写或 worker，启动 guard 就要求先看到完成标记。当前协议不支持旧节点与新节点滚动混写，否则旧节点会绕过关系和 Outbox，破坏新链恒等式。

## 八、出问题后由谁继续

### 重复请求或响应丢失

- 第一次 PUT 插入关系并写 `+1`，第二次 PUT 由主键判断目标已满足，不再写事件；连续 DELETE 也以删除零行收敛。
- 如果第一次已经提交但 HTTP 响应丢失，客户端只能认为结果暂时未知。它可以重放同一目标状态，再查 `tb_blog_like` 确认关系；不能改用 toggle，也不需要人工补一条 delta。

### worker 在 marker 前失败

- worker 已执行聚合 SQL、但尚未标记事件就失败时，聚合与 marker 一起回滚。pending 事件由下一轮 worker 重新选取，不会多加一次。
- 如果 worker 事务已经提交，只是进程没来得及写后续日志，计数和 marker 都已成立，下一轮不会再选中这批事件。

### 永久坏事件

- 正常写路径只会写 `±1`，V8 迁移也声明了 CHECK；worker 仍主动复核。非法 delta、聚合将为负、Blog 更新不到一行或 marker 数不一致都会让整批回滚。
- 当前没有自动 DLQ、隔离表或跳过坏事件的终态。坏事件位于待处理前部时可能持续阻塞后续批次，需要人工保留关系、聚合和事件现场，修复事实后再重试；不能直接把它标成 processed。

### 榜单丢失、损坏、切版或旧任务晚到

- 检查到榜单基数不符、元数据过期或损坏、详情 ID 不完整、读取期间 generation/count 变化时，当前请求回退 MySQL；刷新开关开启时另行触发重建。检查通过后的空页直接返回空，具体范围见第四节。
- 旧 builder 发布时由 Lua 作最终 generation 裁定。版本已推进就删除旧临时 key，不重试发布旧数据；后续刷新使用新 generation 构建。

## 九、放到具体情形里理解

### 页面没响应，用户连续点了两次点赞

两个 PUT 都希望 Blog 100 最终已点赞。即使它们先后查到 Blog 存在，最终也只有一个关系插入能通过 `(blog_id,user_id)` 主键并产生一条 `+1`；另一个返回 UNCHANGED。如果接口是 toggle，第二次反而会取消第一次。

### 点赞成功，页面数字稍后才变化

写请求返回 CHANGED 时，关系和 `+1` Outbox 已提交，但 worker 可能还没运行，所以页面可以同时看到 `isLike=true`、`liked=37`。这属于允许的聚合滞后；不能据此把关系回滚或再补点一次赞。

### 较早启动的热榜任务反而更晚完成

任务 A 的旧候选即使最终写完，也会因 generation 40 已经过期而被拒绝；任务 B 的 41 代正式榜保持不变。临时榜防止半成品可见，generation 防止完整旧榜晚到，两者解决的是不同问题。

## 十、源码反查与必要修正

- 点赞入口与响应：[BlogController](../../../src/main/java/com/localdeals/controller/BlogController.java)、[BlogServiceImpl](../../../src/main/java/com/localdeals/service/impl/BlogServiceImpl.java)
- 关系事务与迁移：[BlogLikeCommandService](../../../src/main/java/com/localdeals/service/BlogLikeCommandService.java)、[V8 点赞迁移](../../../src/main/resources/db/migration/V8__durable_blog_likes.sql)
- 聚合处理：[BlogLikeOutboxWorker](../../../src/main/java/com/localdeals/service/BlogLikeOutboxWorker.java)、[BlogLikeOutboxBatchService](../../../src/main/java/com/localdeals/service/BlogLikeOutboxBatchService.java)、[Outbox 清理](../../../src/main/java/com/localdeals/service/BlogLikeOutboxCleanupService.java)
- 热榜读写：[BlogHotRankService](../../../src/main/java/com/localdeals/service/BlogHotRankService.java)、[刷新调度](../../../src/main/java/com/localdeals/service/BlogHotRankRefreshScheduler.java)、[读触发 warmup](../../../src/main/java/com/localdeals/service/BlogHotRankWarmupService.java)
- Redis 协议：[正式榜发布 Lua](../../../src/main/resources/lua/blog_hot_rank_publish.lua)、[新 Blog 加榜 Lua](../../../src/main/resources/lua/blog_hot_rank_add_new.lua)
- 切换与门禁：[旧关系导入](../../../src/main/java/com/localdeals/service/BlogLikeLegacyImportService.java)、[启动导入器](../../../src/main/java/com/localdeals/init/BlogLikeLegacyBackfillRunner.java)、[切换标记](../../../src/main/java/com/localdeals/service/BlogLikeCutoverService.java)、[启动门禁](../../../src/main/java/com/localdeals/init/BlogLikeCutoverGuard.java)
- 默认开关：[BlogLikeProperties](../../../src/main/java/com/localdeals/config/BlogLikeProperties.java)、[BlogHotRankProperties](../../../src/main/java/com/localdeals/config/BlogHotRankProperties.java)、[application.yaml](../../../src/main/resources/application.yaml)

这份笔记采用当前源码中的三项边界：点赞是 MySQL 目标状态关系加同事务增量 Outbox，不是旧 Redis toggle；点赞成功不表示聚合和热榜同步变化；热榜锁、临时榜和 generation 分别减少重复工作、防半成品、防旧任务晚到。本次仅按源码校对笔记，没有启动服务、执行迁移或运行测试。
