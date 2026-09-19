# 05. 从图片上传、Blog 发布到粉丝 Feed

## 一、作者先把图片上传成自己的临时资源

作者调用 `POST /upload/blog`，带来一个图片文件，希望得到一条以后能放进 Blog 发布请求的受管路径。当前用户从 `UserHolder` 取得；图片默认最大 5 MB，只接受 JPG、JPEG 和 PNG。

- `UploadController` 先从 `UserHolder` 取 `UserDTO`。没有用户或 userId 时直接返回“请先登录”；通过后再校验 multipart 文件。
  - 文件为空或超过 `local-deals.upload.max-image-size` 就直接拒绝。默认根目录是 `./frontend/user/imgs`，大小上限 5 MB。
  - Controller 取客户端文件名最后一个点后的扩展名并转成小写，只允许 jpg、jpeg、png；jpg/jpeg 必须对应 `image/jpeg`，png 必须对应 `image/png`。
  - 最后读取前 8 字节：PNG 要完整匹配 8 字节签名，JPEG 前 3 字节必须是 `FF D8 FF`。扩展名、Content-Type 或文件头任一不匹配都返回失败，文件和数据库尚未变化。
- 校验通过后，服务端生成 UUID，并取它的 hash 低 8 位拆成两个 `0..15` 的目录，组成 `blogs/{d1}/{d2}/{uuid}.{extension}`，不使用客户端原文件名作为落盘名称。
- Controller 把配置根目录转成绝对规范路径，创建后取得 real path；接着创建目标父目录，再把父目录解析成 real path，要求它仍以根目录开头。
  - 检查通过后，用这个真实父目录和 UUID 文件名组成最终路径并调用 `transferTo`；目录越界、文件 IO 失败都会结束当前请求。
- 文件落盘后，Controller 调用 `registerTemporary(relativeName,userId)`。该方法构造 `UploadFile`，把相对路径、当前用户和 `TEMP` 状态写入 `tb_upload_file`，插入必须恰好影响一行。
  - 数据库登记抛异常时，Controller 尝试 `deleteIfExists` 删除刚写文件，再把异常交给外层；删除也失败可能留下孤儿文件，当前没有后台清理任务。
- TEMP 记录保存成功后，Controller 返回 `"/" + relativeName`，例如 `/blogs/3/12/<uuid>.png`。数据库保存的是不带开头斜杠的相对路径，后面的 Blog 发布会先把客户端路径规范化成这个形式，再核对当前用户和 TEMP 状态。

## 二、作者发布 Blog，并在同一事务占用图片

作者调用 `POST /blog`，带来标题、正文、店铺 ID（shopId）和已上传图片路径，希望同时提交 Blog 与图片使用关系。作者身份不采用请求里的 userId，而是由服务端从 `UserHolder` 覆盖。

- `BlogServiceImpl.saveBlog` 开启 `rollbackFor=Exception` 的 MySQL 事务，先从 `UserHolder` 取得当前用户，再调用 `validateTemporaryImages(blog.images,userId)`。
  - images 为 null 或去掉空白后为空时，校验返回空列表；否则按逗号拆分，拆分所得数组长度超过 9 就直接拒绝。这里使用 Java 默认的 `split(",")`，末尾空段不计入数组长度。
  - 每段路径交给 `ManagedImagePath.normalize`：它统一斜杠，去掉 `/imgs/` 或开头 `/`，做相对路径 normalize，再要求结果符合 `blogs/0..15/0..15/UUID.jpg|jpeg|png`。路径非法或同一规范路径重复都拒绝。
  - 对每个规范路径按主键查询 `tb_upload_file`，只有 owner 等于当前 userId 且 status 为 TEMP 才加入返回列表；记录不存在、属于别人或已发布都返回“图片无效或无权使用”。
- 图片检查通过后，服务用当前 userId 覆盖请求对象中的作者，再通过 MyBatis-Plus `save(blog)` 向 `tb_blog` 插入标题、正文、店铺 ID、图片字符串等字段，数据库回填 blogId。
  - `validateTemporaryImages` 返回的规范路径用于后面的图片状态更新；当前方法没有把 `blog.images` 重写成规范路径，Blog 行保存的仍是请求对象原有图片字符串。
  - `save` 返回 false 时方法返回“新增笔记失败”，不继续更新图片，也不登记 Redis 回调。
- Blog 插入成功后，服务遍历规范路径，逐张执行 `UPDATE tb_upload_file SET status='PUBLISHED',blog_id=? WHERE path=? AND owner_user_id=? AND status='TEMP'`。
  - 每张图必须恰好更新一行。某张图在前置检查后被并发删除或占用时，方法抛“图片发布状态发生冲突”；Blog 插入和此前图片更新都在同一个事务中，因而一起回滚。
- 图片都占用成功后，事务查询 `tb_follow` 中 `follow_user_id=当前作者ID` 的记录，再从每行取 `user_id` 组成 followerIds。这里保存的是当前粉丝快照，关注请求不是本次发布流程中的一步。
- 服务检查 Spring 事务同步确实处于 active；不是 active 就抛异常，让 Blog 与图片状态回滚。通过后保存回填的 blogId，并用当前 Java 毫秒时间生成 Feed score，然后登记 `afterCommit` 回调。
- 方法内部准备返回 `Result.ok(blogId)`，事务拦截器随后提交 MySQL。只有提交成功才执行回调；回滚时不会写热榜或 Feed。
  - 对外成功能够说明 Blog 与图片数据库状态已提交，不能说明每个粉丝都已经收到 Feed 项。

## 三、提交后尽力更新热榜和粉丝 Feed

这是发布事务的提交后回调，起点是 Blog 与图片状态已经成为 MySQL 事实。它仍在当前提交线程中依次尝试 Redis，通常发生在 HTTP 响应真正发出前，但已不能再让刚提交的数据库事务回滚。

- 回调先调用 `BlogHotRankService.addNewBlogAfterCommit(blogId)`。这个方法不检查热榜 read/refresh 开关，而是交给 Lua 判断现有榜是否 ready。Java 要求 blogId 为正，再把 19 位补零 blogId 和配置 top-K 作为 ARGV，把正式榜、metadata、generation 三个同槽 key 作为 KEYS，执行 `blog_hot_rank_add_new.lua`。
  - 脚本先读 metadata.ready；不是 `1` 就返回 0，不创建或擅自宣布一份热榜 ready。接着要求 top-K 是正数。
  - 脚本再读取全局 generation、metadata generation 和 metadata capacity。两个 generation 都必须是规范正整数，全局版本不能小于 metadata 版本，capacity 必须等于传入 top-K；不满足返回 Redis error，而且尚未改正式榜。
  - 检查通过后，对正式 ZSET 执行 `ZADD NX score=0 member=补零blogId`。成员已存在时返回 0，不覆盖原分数；新增后若基数超过 top-K，就按升序 rank 删除最低的多余成员。
  - 如果新 Blog 自己在裁剪中被移除，脚本返回 0，不推进版本；如果仍在榜内，就 INCR 全局 generation，并把 metadata 的 generation 和当前 ZCARD 一起更新，最后返回新增数 1。这样此前已读取旧 MySQL 快照的 builder 会在发布时被版本拒绝。
  - 这段脚本不修改 ready、capacity 或 publishedAt，因此加进一篇新 Blog 不等于完成了一次全量刷新，也不会延长原快照的时效。
  - Java 不使用脚本返回值继续业务，只在 Redis 抛异常时记录日志；榜未就绪、新 Blog 没留在 top-K 或异常都不影响已提交 Blog。完整热榜机制见 [04](04-like-and-hot-rank.md)。
- 接着回调遍历事务内取得的 followerIds。每个 followerId 都拼成 `feed:{followerId}`，再执行 ZADD：member 是十进制 blogId 字符串，score 是登记回调前取得的同一个 `publishedAt` 毫秒时间。
  - 代码不检查 ZADD 的布尔返回值；同一 Feed 中已有相同 blogId 时，Redis 会更新该成员 score，而不会增加第二个成员。
  - 每个粉丝的 Redis 调用单独捕获运行时异常：某一人失败只记录日志，循环继续写其他人，发布接口也不会因此撤销 Blog。
- 回调结束后，原发布请求返回 blogId。热榜以后可以在刷新能力开启时从 MySQL 完整重建；Feed 没有相同的恢复任务。
  - `afterCommit` 只是进程内回调，不是持久 Outbox。提交后、回调前进程退出，或者某个粉丝写入失败时，数据库里没有待办供 worker 重放。

## 四、关注和取关是另一条请求

用户调用 `PUT /follow/{followUserId}/{isFollow}`，带来对目标用户的关注选择。当前用户仍从 `UserHolder` 取得；这条请求独立改变关注关系，不是每次发布的必经步骤，也不会补发历史 Blog。

- `FollowServiceImpl.follow` 从 `UserHolder` 取 userId，用它先拼好 Redis key `followS:{userId}`。当前方法没有另外检查 followUserId 是否为正、目标用户是否存在或是否关注自己，而是直接按 isFollow 分支处理。
- `isFollow=true` 时，服务构造 `Follow(userId,followUserId)`，调用 `save` 向 `tb_follow` 插入关系。只有 `save` 返回 true，才执行 SADD，把 followUserId 的十进制字符串加入当前用户的 Redis Set。
  - MySQL 保存长期关注关系；Redis Set 供“我的关注”和“共同关注”读取，是派生数据。SADD 的返回数量没有参与后续判断。
  - `save` 返回 false 时不写 Redis，但方法最后仍返回 `Result.ok()`，当前接口不会把这个明确失败转换成业务错误。
- `isFollow=false` 时，服务用 `WHERE user_id=? AND follow_user_id=?` 删除所有匹配关系。只有 `remove` 返回 true，才执行 SREM，从 Set 移除 followUserId 字符串。
  - 没有匹配行时不写 Redis，方法最后同样返回 `Result.ok()`。
- MySQL 与 Redis 没有共同事务，也没有 Outbox。Redis 写异常会向上抛出，使请求表现为失败，但此前成功的 MySQL 插入或删除不会自动撤销。
  - `isFollow` 从 MySQL 查询计数，可能已经反映新关系；“我的关注”和“共同关注”依赖 Redis Set，可能仍显示旧结果。
- `tb_follow` 只有自增主键，没有 `(user_id, follow_user_id)` 复合唯一约束。重复或并发关注可能插入多行，只是 Redis Set 会合并相同成员；取关条件会删除所有匹配行。
- 发布只给事务内查询到的粉丝快照写 Feed。用户在 Blog 发布后才关注作者，或者并发关注发生在发布查询粉丝列表之后，都不会自动收到这篇 Blog。

## 五、粉丝按时间游标读取自己的 Feed

粉丝调用 `GET /blog/of/follow?lastId=<max>&offset=<offset>`，希望从自己的收件箱继续向后读取。这个接口需要登录；Feed 当前固定每页最多取 2 条。

- `queryBlogOfFollow(max,offset)` 从 `UserHolder` 取得粉丝 ID，用它拼出 `feed:{userId}`。当前方法没有先校验 max 和 offset，直接调用 `reverseRangeByScoreWithScores(key,0,max,offset,2)`。
  - Redis 在 score `0..max` 内逆序取值，先跳过 offset 个成员，最多返回 2 个带 score 的 tuple。key 不存在、结果为 null 或空集合时，方法直接返回空列表；这里没有从 Blog 与关注关系重建历史 Feed。
- 服务按 Redis 返回顺序遍历 tuple，把 member 用 `Long.valueOf` 解析成 blogId，同时计算下一页游标。`minTime` 初始为 0，`os` 初始为 1；遇到与当前 minTime 相同的 score 就把 os 加一，遇到不同的 score 就更新 minTime 并把 os 重置为 1。
  - 逆序结果使循环结束时的 minTime 是本页最小时间，os 是本页末尾连续等于该时间的数量。非法 member 会在 Long 解析处抛异常。
- 服务把 blogId 列表拼进 `WHERE id IN (...)` 查询 Blog，并附加 `ORDER BY FIELD(id, id列表)`，让 MySQL 按 Redis 返回的 ID 顺序排列。
  - Feed ZSET 只保存收件箱索引，正文仍来自 MySQL；某个 blogId 已不存在时，查询只会少返回记录，不会像热榜那样让整页回退。
- 对查到的每篇 Blog，服务按作者 ID 单独查询用户并补 name/icon；当前用户存在时，再按 `(blogId,userId)` 单独查 `tb_blog_like` 设置 `isLike`。
  - 这里没有使用热榜读取的批量补全方法，因此一页虽固定只有 2 条，仍是逐篇补作者与点赞状态。
- 最后把 Blog 列表、循环得到的 `minTime` 和 `os` 封装成 `ScrollResult` 返回，客户端下一次把二者作为 max 和 offset 传回。
  - 如果本页 minTime 仍等于本次传入的 max，代码也只返回本页统计出的 os，没有加上传入的旧 offset；同一毫秒内容跨过两页时，下一次可能再次从旧位置读取。后文只用具体数据推演这个结果。
  - Redis 读取、参数错误、成员解析或数据库查询异常没有局部恢复逻辑，会让当前请求失败。

## 六、临时图片删除为什么还要经过 DELETING

用户调用 `DELETE /upload/blog?name=...` 只能删除自己的 TEMP 图片。文件系统和 MySQL 没有共同事务，所以入口先用数据库状态抢占，再处理物理文件。

- Controller 先取得当前用户，再用 `ManagedImagePath.normalize(name)` 得到受管相对路径；身份缺失或路径不符合上传命名规则就直接返回，不读取任意文件位置。
- 接着把相对路径解析到真实图片根目录下，要求规范化目标仍以根目录开头且存在父目录。如果物理文件已经不存在，入口执行 `DELETE ... WHERE path=? AND owner_user_id=? AND status='TEMP'`，然后不检查行数就返回成功。
- 文件存在时，Controller 先解析真实父目录，要求它仍在图片根目录内，并拒绝符号链接或目录类型的目标。通过后才调用 Mapper 抢占删除权。
- Mapper 执行 `UPDATE tb_upload_file SET status='DELETING' WHERE path=? AND owner_user_id=? AND status='TEMP'`。只有影响一行才算抢到；否则返回“图片不存在或无权删除”，物理文件不动。
- 抢到后，Controller 用 `deleteIfExists` 删除物理文件，再执行 `DELETE ... WHERE path=? AND owner_user_id=? AND status='DELETING'` 删除记录。
  - 文件删除或数据库方法抛异常时，捕获分支尝试执行 `UPDATE ... SET status='TEMP' WHERE ... status='DELETING'`，再把原异常向上抛，让用户以后重试。回退更新不检查行数，回退本身也可能抛异常，因此不能保证失败后一定恢复成 TEMP。
  - 物理文件删除成功、数据库删除却抛错时，回退可能留下“TEMP 记录存在但文件已不存在”；再次删除会走缺失文件分支清理记录。`completeTemporaryDeletion` 没有检查删除行数，零行不会触发回退，也可能留下 DELETING 记录。

当前没有定时任务扫描长期 TEMP、DELETING、孤儿文件或缺失文件。已 PUBLISHED 的图片不能走这个临时删除入口。Blog 发布事务回滚只恢复 Blog 与图片数据库状态，不删除此前上传的物理文件；回滚后的 TEMP 文件可供重试或显式删除。

## 七、同分 Feed 游标的当前边界

同一篇 Blog 写给不同粉丝时，score 相同没有影响，因为它们位于不同 Feed key。真正的同分发生在同一个粉丝关注的多位作者于同一毫秒发布时，多篇 Blog 会进入同一个 ZSET 并得到相同 score。

Redis 会再按 member 字符串排序；这里的 blogId 没有像热榜那样补齐位数，因此同分顺序不等于可靠的发布时间顺序或数值 ID 顺序，不过回库后会保留 Redis 给出的顺序。

当前 offset 只统计本页末尾的同分数量；如果本页最小时间仍等于传入 max，代码没有把传入的旧 offset 累加进去。假设时间 T 下有 6 条内容：

```text
第 1 页：max=T, offset=0  -> 取第 1、2 条，返回 offset=2
第 2 页：max=T, offset=2  -> 取第 3、4 条，仍返回 offset=2
第 3 页：max=T, offset=2  -> 又取第 3、4 条
```

因此大量内容同分时，当前实现可能重复读取并卡在同一组内容。接口带了时间和 offset 两个参数，不等于同分分页已经正确完成。

## 八、出问题后由谁继续

### Blog 事务回滚，或提交后回调中断

- Blog 插入、图片更新、粉丝查询或事务同步登记只要以异常结束，数据库修改回滚，after-commit 不执行。此时不存在已提交 Blog，也没有热榜或 Feed 项需要撤回。
- Blog 已提交但进程在回调前退出时，Blog 与 PUBLISHED 图片保留，热榜可等独立重建；Feed 没有投递记录、重试 worker 或补发接口。

### 某个粉丝没有看到已发布 Blog

- 若某个 `feed:{followerId}` 写失败，代码只记日志并继续其他粉丝；Feed key 后来丢失也直接表现为空。
- 先查 MySQL 可以确认 Blog 事实，但当前源码没有自动恢复 Feed 的闭环。不能为了 Feed 缺项删除 Blog，也不能把发布成功说成“所有粉丝已收到”。

### 关注 MySQL 成功，Redis Set 失败

- 请求可能向上返回异常，但 MySQL 关系已经成立。直接重试关注又可能因缺少复合唯一约束而插入重复行。
- 当前没有后台任务重建 Set。判断长期关系应先查 `tb_follow`，不能根据“共同关注为空”反推 MySQL 没有关系。

## 九、放到具体情形里理解

### 用户拿别人的图片路径发布

客户端能看见图片 URL，不等于拥有这个资源。发布先查 owner 和 TEMP，别人的路径在 Blog 插入前就被拒绝；即使验证与更新之间发生竞争，最终条件更新还会再次按 owner 和状态仲裁。

### Blog 已发布，某个粉丝却没看到

MySQL 提交后，对粉丝 A 的 ZADD 成功、对粉丝 B 的 ZADD 抛错，循环仍会继续，但 B 没有待办可重放。这个例子说明数据库发布成功、Redis 扩散完成和用户实际看到内容是三件事。

## 十、源码反查与必要修正

- 上传、校验与删除：[UploadController](../../../src/main/java/com/localdeals/controller/UploadController.java)、[UploadFileService](../../../src/main/java/com/localdeals/service/UploadFileService.java)、[UploadFileMapper](../../../src/main/java/com/localdeals/mapper/UploadFileMapper.java)
- 图片表与配置：[V3 图片归属迁移](../../../src/main/resources/db/migration/V3__upload_file_ownership.sql)、[V4 状态扩展](../../../src/main/resources/db/migration/V4__expand_upload_file_status.sql)、[UploadProperties](../../../src/main/java/com/localdeals/config/UploadProperties.java)
- Blog 发布与 Feed：[BlogController](../../../src/main/java/com/localdeals/controller/BlogController.java)、[BlogServiceImpl](../../../src/main/java/com/localdeals/service/impl/BlogServiceImpl.java)
- 关注关系：[FollowController](../../../src/main/java/com/localdeals/controller/FollowController.java)、[FollowServiceImpl](../../../src/main/java/com/localdeals/service/impl/FollowServiceImpl.java)、[V1 基线表结构](../../../src/main/resources/db/migration/V1__baseline_schema.sql)
- 热榜交接：[新 Blog 加榜 Lua](../../../src/main/resources/lua/blog_hot_rank_add_new.lua)、[04. 点赞与热榜](04-like-and-hot-rank.md)

这份笔记采用当前源码中的三项边界：after-commit 是提交后的进程内回调，不是持久 Outbox，Feed 单粉丝失败只有日志且没有补发；关注关系先写 MySQL 再写 Redis Set，但没有跨存储事务或复合唯一约束；Feed 虽返回时间和 offset，当前代码没有累计连续同分页的旧 offset。本次仅按源码校对笔记，没有启动服务、执行迁移或运行测试。
