# 点赞持久化、Outbox 聚合与热榜发布门禁

本阶段把教程版的 `Redis ZSET -> 每次请求直接 +/-1 更新 tb_blog` 拆成两个边界：

- MySQL 关系表和事务 outbox 是点赞身份及聚合变更的唯一事实来源；
- Redis 热榜只是可丢弃、可重建的有界读模型，任何不完整或过期状态都回退 MySQL。

这不是可滚动升级。旧节点仍会绕过关系表/outbox 写 `tb_blog.liked` 和旧 Redis ZSET，
因此 V8 切换必须停写并停止全部旧实例。

## 写入与聚合契约

新接口表达目标状态，不再使用不可重试的 toggle：

| 接口 | 目标状态 | 重复请求 |
| --- | --- | --- |
| `PUT /blog/{id}/like` | 已点赞 | `changed=false` |
| `DELETE /blog/{id}/like` | 未点赞 | `changed=false` |
| `PUT /blog/like/{id}?liked=true|false` | 迁移期显式目标状态 | 参数必填；无参数旧请求返回 400 |

单次命令在同一 MySQL 事务内完成：

1. 共享锁确认博客存在，阻断并发删除；
2. 插入或删除 `tb_blog_like(blog_id,user_id)`；
3. 仅当关系真实变化时插入一条不可变的 `tb_blog_like_outbox(delta=+1|-1)`；
4. 任一步失败，关系和 outbox 一起回滚。

worker 通过 `SELECT ... FOR UPDATE` 锁定一个有界前缀，按 blog 聚合 delta，依 blogId
顺序逐条更新 `tb_blog.liked`，再按本批精确 eventId 标记 `processed_time`。聚合和标记
同事务提交，崩溃前回滚、提交后重放不会重复记账。Redisson 锁只用于减少多实例争用；
Redis 不可用时仍由 MySQL 行锁保证正确性并继续消费。

恒等式为：

```text
tb_blog.liked = tb_blog.legacy_liked_offset + COUNT(tb_blog_like)
```

`legacy_liked_offset` 保存无法可靠恢复用户身份的历史数字；完成旧 Redis 身份导入时，
每导入一个新关系就等量减少 offset，总数不变。

## 热榜读模型

V7 增加 `(liked DESC,id DESC)` 与 `(shop_id,liked DESC,id DESC)` 索引，数据库回退固定用
`liked DESC,id DESC`，保证同分分页稳定。Redis key 均使用 `{global}` hash tag：

- `blog:hot:{global}:live`：最多 top-K 个 blogId；成员是 19 位补零十进制字符串；
- `blog:hot:{global}:meta`：`ready/generation/count/capacity/publishedAt`；
- `blog:hot:{global}:generation`：发布 fencing token；
- `blog:hot:{global}:temp:<generation>`：构建候选；
- `blog:hot:{global}:lock`：减少重复构建。

构建器从索引化 MySQL 查询 top-K，写临时 ZSET，再由 Lua 在 generation 未变化时原子
`RENAME`。新博客提交后的 Lua 只对 ready 榜单执行 `ZADD NX`，保留刷新器写入的非零分，
并推进 generation，防止更早取得的 DB 快照覆盖该博客。读取端校验 ready、generation、
capacity、publishedAt、meta count 与 ZCARD；任一缺失、漂移、过期、越过完整缓存页边界或
Redis 异常都整页回退 MySQL，不把坏缓存解释成空榜。

当前请求始终同步回退 MySQL；同时在 refresh 已启用时触发有界异步预热。进程内原子门禁
合并并发 miss，Redisson 构建锁再做集群级 singleflight。Redis 故障日志按时间限频，
上线容量验收仍必须观察回退比例，不能只看 Redis 接口成功率。

## V8 停写迁移步骤

### 1. 冻结旧写入

1. 在网关关闭所有点赞 PUT/DELETE，并停止全部旧版应用实例；只关新节点配置不够。
2. 确认旧 `blog:liked:*` ZSET 不再变化。导入器会检查前后 ZCARD，但同基数换成员无法
   自动识别，所以“旧节点全部停止”是强制门禁。
3. 清理或版本化 nginx 的 7 天静态资源缓存。旧页面仍发送无 `liked` 参数的 toggle URL，
   新后端会有意返回 400。
4. 备份 MySQL 与 Redis，记录回滚点。

### 2. 执行迁移和单实例导入

先以以下配置启动一个不接流量的实例，让 Flyway 执行 V7/V8，并运行一次 legacy importer：

```bash
LOCAL_DEALS_BLOG_LIKE_WRITE_ENABLED=false
LOCAL_DEALS_BLOG_LIKE_WORKER_ENABLED=false
LOCAL_DEALS_BLOG_LIKE_LEGACY_BACKFILL_ON_STARTUP=true
LOCAL_DEALS_BLOG_HOT_RANK_READ_ENABLED=false
LOCAL_DEALS_BLOG_HOT_RANK_REFRESH_ENABLED=false
```

导入器 lazy SCAN `blog:liked:*`，逐页导入用户和原始点赞时间；非法 key、用户、时间、
不存在的博客、身份数超过历史总数、源集合变化或恒等式失败都会拒绝启动。全部成功后才写
`tb_blog_like_cutover(marker_key='V8_LEGACY_REDIS_IMPORT',status='COMPLETED')`。

必须人工保存以下核对结果：

```sql
SELECT * FROM tb_blog_like_cutover
WHERE marker_key = 'V8_LEGACY_REDIS_IMPORT';

SELECT COUNT(*) AS invalid_blogs
FROM tb_blog b
LEFT JOIN (
  SELECT blog_id, COUNT(*) AS active_count
  FROM tb_blog_like GROUP BY blog_id
) l ON l.blog_id = b.id
WHERE b.liked <> b.legacy_liked_offset + COALESCE(l.active_count, 0);

SELECT COUNT(*) AS pending_outbox
FROM tb_blog_like_outbox WHERE processed_time IS NULL;

SELECT COUNT(*) AS pending_outbox,
       TIMESTAMPDIFF(SECOND, MIN(created_time), CURRENT_TIMESTAMP(3)) AS oldest_pending_seconds
FROM tb_blog_like_outbox
WHERE processed_time IS NULL;
```

结果必须分别为一条 `COMPLETED`、`0`、`0`。即使全新环境没有旧 Redis key，也要执行
一次零数据导入来生成持久门禁；生产启动检查会拒绝在缺少 marker 时开启 write/worker。

### 3. 开启新写入，再启热榜读取

重启时关闭一次性 importer，并显式开启新链路：

```bash
LOCAL_DEALS_BLOG_LIKE_LEGACY_BACKFILL_ON_STARTUP=false
LOCAL_DEALS_BLOG_LIKE_WRITE_ENABLED=true
LOCAL_DEALS_BLOG_LIKE_WORKER_ENABLED=true
LOCAL_DEALS_BLOG_HOT_RANK_REFRESH_ENABLED=true
LOCAL_DEALS_BLOG_HOT_RANK_READ_ENABLED=false
```

先等待一个完整热榜 generation，比较 MySQL/Redis top-K 的 ID、顺序、count、capacity 和
publishedAt。通过后 canary 设置 `READ_ENABLED=true`，观察 DB fallback、outbox pending
数量/最老年龄、worker 失败与清理滞后，再逐步全量。

默认 worker 每 200 ms 最多处理 500 条事件，理论调度上限为每秒 2500 条；cleanup 每秒
最多删除 5 批、每批 2000 条已处理事件，理论调度上限为每秒 10000 条。该数字只用于配置
容量初筛，不是生产吞吐结论；上线前仍要以目标写入模型测量实际处理速率，并要求
`oldest_pending_seconds` 能持续回落、清理速率长期高于已处理事件生成速率。若积压年龄持续
增长，应先关闭新写入或降低入口流量，再调整批量/周期，不能依靠缩短保留期掩盖积压。

回滚时可以立即关闭 Redis read/refresh 以及点赞 write/worker；不要重新启动旧写节点，
因为它们会绕过 V8 的关系/outbox 契约。数据库迁移和新表保留，修复后从同一 marker 状态恢复。

## 验收清单

- 同一用户 100 个并发 PUT：最终 1 条关系、1 条有效 outbox delta；DELETE 同理；
- outbox 插入故障：关系、事件和 aggregate 均无变化；
- worker 在 update 后或 mark 前失败：事务整体回滚，重试只应用一次；
- Redis/Redisson 不可用：命令仍以 MySQL 为准，worker 通过 DB 锁继续收敛；
- drain 后所有博客满足 `liked = legacy_offset + active relationships`，且 liked 不为负；
- 热榜相同分数跨页无重复/跳项，坏 metadata、live key 丢失、陈旧榜单全部回退 DB；
- builder 与新博客提交交错时，旧 generation 不能覆盖已提交博客；
- 隔离环境运行 `BlogLikeReliabilityIT` 与 `BlogHotRankRedisIT`；禁止对共享开发库/Redis
  执行，因为它们会消费 outbox 或清理正式热榜 key。

## M4 验证记录（2026-08-20，Asia/Shanghai）

环境：`codex/platform-hardening`，Dragonwell Java `1.8.0_472`，Maven `3.9.9`，MySQL
`8.0` 专用 schema，Redis `6.2` 专用临时实例 `127.0.0.1:16379`。未连接共享开发 schema，
也未清理共享 Redis 热榜 key。

默认编译与测试：

```bash
JAVA_HOME=/home/sd101t/.jdks/dragonwell-ex-1.8.0_472 \
  /opt/idea/plugins/maven/lib/maven3/bin/mvn compile test-compile test
```

结果：154 个主源码、64 个测试源码编译通过；默认单测 `228` 个，failure/error/skip 均为
`0`，完成时间 `2026-08-20 20:01:50 +08:00`。

隔离集成测试：

```bash
LOCAL_DEALS_RUN_ISOLATED_LIKE_IT=true \
LOCAL_DEALS_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/<dedicated-m4-schema>' \
LOCAL_DEALS_REDIS_HOST=127.0.0.1 \
LOCAL_DEALS_REDIS_PORT=16379 \
  /opt/idea/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=BlogLikeReliabilityIT,BlogHotRankRedisIT test
```

结果：MySQL 事务测试 `6` 个、Redis 读模型测试 `5` 个，共 `11` 个，failure/error/skip
均为 `0`，完成时间 `2026-08-20 20:02:10 +08:00`。覆盖 100 个并发 PUT 与 100 个并发
DELETE、marker 更新失败时整批回滚及重放、列表点赞状态回填、generation 竞态、坏 metadata
整页回退与 top-K 裁剪。测试结束后的只读核对结果为 `invalid_blogs=0`、
`pending_outbox=0`、专用 Redis `DBSIZE=0`。

Flyway 使用 `org.flywaydb:flyway-maven-plugin:6.4.4:migrate` 在两个独立空 schema 验证：
一个从 V1 顺序执行 8 个迁移到 V8；另一个先以 `flyway.target=6` 执行 6 个迁移，再执行
V7/V8，最终均校验为 V8。三个用户端 HTML 还使用 Node `vm.Script` 解析全部内联脚本，
每页 1 段、全部通过；交互代码核对为未点赞发送 PUT、已点赞发送 DELETE，并只依据响应中的
`desired/changed` 更新图标与计数。MVC 与隔离 MySQL 测试同时验证了这套重试安全契约；
本阶段未把静态检查冒充完整浏览器 E2E。
