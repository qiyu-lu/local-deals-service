# 店铺读取与搜索：一次请求怎样拿到数据

店铺详情、附近店铺和全文搜索虽然最后都可能返回 `Shop`，实际是三次独立请求，不能理解成一次请求先查详情缓存、再查 GEO、最后查 ES。这几个消费者 GET 接口都允许匿名访问；即使前置拦截器根据 token 恢复了用户，下面三条店铺读取逻辑也不使用当前用户，热榜与 Feed 则是另外的流程。

## 一、用户打开一个店铺

用户请求 `GET /shop/{id}`，带的是路径里的店铺 ID，希望拿到这家店当前可展示的详情。当前入口实际使用带空值缓存的 Cache Aside：先读 Redis，不能使用时合并相同请求，再受控查询 MySQL，并尽力把结果写回缓存。

- 请求到达业务入口前，消费者身份拦截器先处理可选的 `authorization` token，然后公开路径规则允许这个 GET 请求匿名继续。
  - 没有 token，或者 Redis 正常查询但没有对应会话，都不会为本次店铺读取建立用户上下文。
  - 带 token 且查到会话时，拦截器会恢复 `UserHolder` 并刷新会话 TTL；详情 Service 不读取这里的用户 ID，所以不会改变查询条件。
  - 如果带了 token 但恢复会话时 Redis 抛异常，请求会在进入 Controller 前返回 503，而不是把依赖故障当作匿名访问。
- 请求进入 `ShopController.queryShopById` 后，Controller 把路径变量 `id` 交给 `ShopServiceImpl.queryShopById`。Service 随后调用 `CacheClient.queryWithPassThrough`，传入五类关键信息：
  - 资源类型是 `SHOP_DETAIL`，用于区分指标、TTL 和 SingleFlight 身份；key 前缀是 `cache:shop:`，与 ID 拼成这次读取的完整 key。
  - 目标类型是 `Shop`；数据库回调是 `this::getById`；缓存校验条件是“请求 ID 必须等于缓存对象里的 ID”。
  - 当前调用没有走 `queryWithMutex`、`queryWithLogicalExpire` 或 `saveShop2RedisCache`。这些教学或工具方法虽然还在类中，却不是当前详情主路径。
- `CacheClient` 先计算 `cacheKey = cache:shop:{id}`，再用 Redis String GET 读取字符串，并按下面的顺序判断：
  - 返回 Java `null`，说明 key 不存在，记为 MISS，接着调用 `loadObject` 回源。
  - 字符串精确等于 `_NULL_PLACEHOLDER_`，说明上一次数据库查询明确没有这家店，直接返回 Java `null`，不再访问 MySQL。这个占位默认 30 秒过期，只代表上一次查询时不存在。
  - 其他字符串先反序列化成 `Shop`，再执行调用方传入的 ID 校验。解析成功且 ID 相同才作为命中返回；JSON 损坏、对象为空或 ID 不同都记为坏值，随后进入 `loadObject`，而不是把错误对象交给用户。
  - GET 本身抛出 Redis 异常时，缓存内容处于未知状态。方法仍进入 `loadObject`，但同时传入 `skipCacheWrite=true`；如果这个请求成为加载者，就不再尝试写故障中的 Redis。
- `loadObject` 用资源类型和完整 key 调用 `SingleFlightLoader.load`，把后面的数据库读取包装成一次可共享的加载。
  - `SingleFlightLoader` 先组成 `(SHOP_DETAIL, cache:shop:{id})`，为它创建候选 `FutureTask`，再用 `putIfAbsent` 放入当前 JVM 的 `inFlight` Map。
  - 放入成功的请求是加载者，由它在自己的请求线程执行 `FutureTask.run()`；Map 中已经有任务的请求是等待者，直接复用原任务，不会再执行自己的数据库回调。
  - 是否写缓存也由加载者的 `skipCacheWrite` 决定。等待者只取得共享结果，不会执行自己的写缓存逻辑；即使等待者的 GET 抛错，也不会改变已经在执行的加载任务。
  - 加载者没有 SingleFlight 等待时限，会一直执行回调。等待者默认最多对共享任务等 750 毫秒：任务及时完成就取得同一个对象、空结果或异常；等待超时则返回 503 `DATABASE_UNAVAILABLE`。
  - 等待者超时不会调用 `cancel`，也不会删除 `inFlight` 中的任务。加载者仍继续执行，完成后由加载者在 `finally` 中按 key 和任务对象精确移除登记；因此等待超时不等于数据库工作停止。
  - 等待线程被中断时会恢复中断标记并抛出内部中断异常；加载任务抛出的运行时异常则从 `FutureTask` 中取出并继续抛给调用方。
- 真正的加载者接着调用 `LocalReadBulkhead.executeDbRead`，申请当前应用实例的 `DB_READ` Semaphore 许可，然后才执行数据库回调。
  - 默认 `db-max-concurrent=4`、`db-max-wait=20ms`。`tryAcquire` 在等待时间内拿不到许可，就返回 429 `READ_OVERLOADED`，数据库回调根本不会开始；等待许可时被中断则恢复中断标记并返回 503。
  - 拿到许可后，`getById(id)` 通过 MyBatis-Plus 按主键读取 MySQL `tb_shop`。成功、返回空或抛错之后，`finally` 都会归还许可。
  - 数据库回调的普通运行时异常被转换成 503 `DATABASE_UNAVAILABLE`。这里不会生成空值缓存，因为“数据库没有回答”不能解释成“店铺不存在”。
  - 这 4 个许可只限制当前实例中接入 `DB_READ` 的读取，不是整个集群、整个数据库或全部 SQL 的并发上限。
- 数据库回调正常返回后，控制回到 `CacheClient.loadObject`，它根据返回值决定要保存什么。
  - 查到 `Shop` 时，把对象序列化成 JSON，使用 `shop-detail-ttl` 尝试 SET 回原 key，默认 TTL 为 30 秒。
  - 明确查不到时，把 `_NULL_PLACEHOLDER_` 写到同一个 key，使用 `shop-detail-empty-ttl`，默认也是 30 秒。这个空值挡住的是同一个不存在 ID 在 TTL 内不断穿透 MySQL。
  - 如果加载者前面的 Redis GET 已经抛错，`skipCacheWrite=true` 会让这一步直接跳过 SET；如果加载者的 GET 正常但缓存值损坏，则会尝试用数据库结果覆盖坏值。
  - SET 抛异常时只记录失败和限频日志，不覆盖已经得到的数据库结果。随后 `loadObject` 把对象或 Java `null` 返回给加载者，仍在等待的请求也从同一个 `FutureTask` 取得该结果。
- 结果回到 `ShopServiceImpl` 后，非空店铺包装成 `Result.ok(shop)`；空值缓存命中或 MySQL 明确查不到则包装成 `success=false、errorMsg=店铺不存在`。到这里本次详情请求结束，请求结束后拦截器再清理可能存在的 `UserHolder`。

### 分类缓存在哪里复用这套逻辑

- `/shop-type/list` 调用 `ShopTypeServiceImpl.queryTypeList`，再进入 `CacheClient.queryListWithPassThrough`。它使用整张列表 key `cache:shop:type:list:`，数据库回调按 `sort` 升序读取分类。
- Redis 返回数组后，方法先反序列化为 `List<ShopType>`。
  - `[]` 被当作合法空列表命中；非空列表要求每个元素不为 `null`、ID 为正数并且不重复，才会直接返回。
  - 无法解析或 ID 校验失败时，列表也经过同 JVM、同 key 的 SingleFlight 和 `DB_READ` 回源。
- 数据库非空列表序列化后默认缓存 100 分钟；数据库空值或空列表统一变成 `[]`，默认缓存 30 秒。这里的列表校验和 TTL 不能套到店铺详情上。

## 二、用户查某个分类的附近店铺

用户请求 `GET /shop/of/type`，带的是 `typeId`、页码 `current`，还可能带经度 `x`、纬度 `y` 和 `sortBy`，希望拿到这一分类的列表。入口先根据排序方式和坐标决定请求语义；这条路径不读取详情缓存，也不接着调用 ES。

- `ShopController.queryShopByType` 收到参数后，直接把 `typeId、current、x、y、sortBy` 交给 `ShopServiceImpl.queryShopByType`。Service 先检查 `sortBy` 是否精确等于 `comments` 或 `score`。
  - 如果等于其中一个值，就构造 `WHERE type_id = ? ORDER BY comments DESC` 或 `ORDER BY score DESC` 的分页查询，每页 5 条。
  - 即使请求同时带了完整坐标，这个分支也不会访问 Redis GEO。MySQL 返回本页记录后立即包装成 `Result.ok`，到这里结束的是“分类内按评论或评分排序”，不是附近查询。
- 如果没有指定上述两种排序，Service 接着检查经纬度是否完整。
  - `x` 或 `y` 任一个为 `null`，就只构造 `WHERE type_id = ?` 的 MySQL 分页，每页 5 条，没有额外 `ORDER BY`。
  - 只传一个坐标也不会计算距离。数据库记录直接返回，结果里没有本次请求得到的 `distance`；这是普通分类页，到这里请求结束。
- 只有坐标完整，并且没有选择 `comments` 或 `score` 时，Service 才准备 Redis GEO 分页。
  - 页面大小固定为 5，先计算 `from = (current - 1) × 5` 和 `end = current × 5`。例如第 2 页的起点是 5，需要先向 Redis 取前 10 个候选，之后才能跳过前 5 个。
  - 分类 ID 拼成 GEO key `shop:geo:{typeId}`；`x` 是经度、`y` 是纬度，二者组成查询中心。随后调用 GEO SEARCH，半径固定 5000 米，参数包含 `includeDistance()` 和 `limit(end)`。
  - 当前调用没有传 `ASC` 或 `DESC`，所以只能确认 Redis 返回半径内候选、每个候选的距离和一组返回顺序，不能说源码已经显式要求严格由近到远。
- Redis 调用返回后，Service 读取 `GeoResults.getContent()` 并判断结果规模。
  - 返回对象为 `null` 或候选集合为空时，代码无法判断“5 公里内确实没店”还是这个分类的 GEO key 未构建，于是重新执行普通的 `WHERE type_id = ?` MySQL 分页并返回。
  - 这个回退没有半径过滤，也不给店铺填距离，所以只保证有机会返回分类数据，不是等价的附近查询。
  - Redis 已有候选、但 `候选数 <= from` 时，说明当前页起点已经超过这批结果，直接返回空列表，不再查 MySQL。
  - GEO SEARCH 自身抛异常时不会进入空结果分支，也没有详情缓存那样的受控回源；普通 Redis 依赖异常离开 Service 后由统一异常处理返回 500。
- 候选足以到达当前页时，Service 对 Redis 列表执行 `skip(from)`。由于 Redis 最多只返回前 `end` 个成员，跳过前页后最多剩 5 个本页候选。
  - 每个 GEO 成员名被解析成店铺 `Long id`，按 Redis 返回次序加入 `ids`；同时用成员字符串保存 `店铺 ID -> Distance` 映射，供回库后补距离。
  - 假设第 1 页得到成员 `[8, 3, 15]`，中间结果就是有序 ID `[8,3,15]`，以及分别指向三个 Redis 距离对象的 Map。
- Service 拿本页 IDs 查询 MySQL，关键条件是 `WHERE id IN (8,3,15)`，并追加 `ORDER BY FIELD(id, 8,3,15)`。
  - `IN` 只负责找出仍存在的完整 `tb_shop` 行；`ORDER BY FIELD` 再把数据库结果恢复成 Redis 候选顺序。
  - 随后遍历每个 `Shop`，按自身 ID 从距离 Map 取值并写入临时展示字段 `distance`，最后返回 `Result.ok(shops)`。到这里附近请求结束。
  - 如果 GEO 中某个 ID 已经从 MySQL 删除，`IN` 查询只返回其余行，数量会变少；当前没有严格数量校验、补位或顺手删除失效 GEO 成员。

`queryShopByType` 的普通 MySQL 分支、GEO 调用和候选回库都没有进入 `DB_READ` 或 `SEARCH` 并发舱；这也说明详情回源和显式搜索的本实例限制不能扩大成“所有店铺读取都受保护”。这些调用抛出的普通依赖异常会由统一异常处理返回 500；如果异常属于 `IllegalArgumentException`，则按参数错误返回 400，不能把所有运行时异常都算成 500。

当前生产代码没有默认执行的 GEO 全量初始化器。`InfraSetupTool.loadShopData` 只是需要人工单独运行的测试工具：它读取全部店铺、按 `typeId` 分组，再把每个店铺 ID 和 `(x,y)` 批量加入对应 GEO key。后台创建或更新只负责后续增量维护，不能自动补齐已有的全量缺口。

## 三、用户全文搜索店铺

用户请求 `GET /shop/search`，带的是可选关键词 `keyword`、可选分类 `typeId`、可选经纬度和半径，以及页码 `current`，希望按全文检索或地理条件找到店铺。这里由 ES 选择候选及顺序，再由 MySQL 补齐完整店铺行。

- `ShopController.searchShops` 把六个请求参数交给 `ShopServiceImpl.searchShops`。Service 先调用 `requirePositivePage`，要求 `current` 不为 `null` 且大于 0。
  - 页码不合法时抛 `IllegalArgumentException`，统一异常处理返回 400，本次请求不会申请搜索许可。
- 页码通过后，Service 调用 `LocalReadBulkhead.executeSearch`，把后面的 ES 查询和 MySQL 回库都放进当前实例的 `SEARCH` 并发舱。
  - `SEARCH` 与详情回源使用的 `DB_READ` 是两个 Semaphore。默认 `search-max-concurrent=4、search-max-wait=20ms`，拿不到许可就返回 429 `SEARCH_OVERLOADED`，回调不会开始。
  - 拿到许可后才执行 `searchShopsAdmitted`；无论成功或异常，`finally` 都归还许可。回调中的普通运行时异常统一转换成 503 `SEARCH_UNAVAILABLE`，所以 ES 查询失败和此处 MySQL 补全失败对外都属于搜索不可用。
  - 这 4 个许可是每个应用实例自己的搜索入口限制，不是 ES 或 MySQL 集群的全局并发上限。
- `searchShopsAdmitted` 新建 `NativeSearchQueryBuilder`，先决定文本查询和分类过滤。
  - `keyword` 非空白时，对 `name、address` 构造 `multi_match`；两个字段在 `ShopDoc` 中使用 IK 写入和搜索分析器。关键词为空白时使用 `match_all`。
  - `typeId` 非 `null` 时，先用 `withFilter(termQuery("typeId", typeId))` 设置分类精确过滤；没有分类 ID 就不设置它。这里先保存一个过滤条件，后续再次调用 `withFilter` 会替换这个条件。
- 接着根据坐标决定是否加入 ES 地理条件。
  - `x` 和 `y` 同时存在时，半径取请求的 `radius`，没传则用 5000 米。MySQL 与请求中的 `x` 是经度、`y` 是纬度，而 ES geo point 调用按纬度、经度传入，所以代码使用 `.point(y, x)`。
  - 代码对 `location` 再次调用 `withFilter`，设置 `geoDistance` 半径过滤，然后增加以 `(y,x)` 为中心、单位为米的距离升序排序。当前依赖中的 `withFilter` 只保存最后传入的过滤条件，所以完整坐标会覆盖前面设置的分类过滤，文本查询仍保留。
  - 例如同时传分类 1 和完整坐标，当前结果是满足文本查询、位于指定半径内的店铺，并不保证都属于分类 1。这是当前实现的缺口，不能把笔记写成分类和地理过滤已经同时生效。
  - 坐标不完整时不会加入地理过滤，也不会使用 `radius` 或距离排序，前面设置的分类过滤会保留。没有显式地理排序时，带关键词的查询沿用 ES 相关性顺序；空关键词的 `match_all` 没有额外业务排序保证。
- Service 再把业务页码转换成 ES 的零基页码：`PageRequest.of(current - 1, 5)`，然后在 `shop_index` 上执行查询，要求结果映射为 `ShopDoc`。
  - ES 客户端默认连接超时 500 毫秒、socket 读取超时 1 秒。超时、索引不存在或其他 ES 运行时异常都会离开当前方法，再由外层 `SEARCH` 并发舱转换成 503，不会自动调用 LIKE。
- ES 正常返回 `SearchHits<ShopDoc>` 后，Service 按命中顺序取出每个文档的业务 ID，组成 `List<Long> ids`。
  - ID 列表为空时直接返回 `Result.ok(emptyList)`，不会访问 MySQL，本次请求结束。
  - `ShopDoc` 只有名称、地址、分类、价格、评分、销量和位置等搜索字段，没有图片等全部展示字段，所以有 ID 时还不能直接把文档当店铺事实返回。
- Service 把有序 IDs 用逗号连接，再执行 `WHERE id IN (...) ORDER BY FIELD(id, ...)` 查询 MySQL `tb_shop`。
  - `IN` 补出当前仍存在的完整 `Shop` 行，`ORDER BY FIELD` 恢复 ES 命中顺序。数据库查询也仍处于前面的 `SEARCH` 许可内。
  - MySQL 正常返回后包装成 `Result.ok(shops)`，释放搜索许可，到这里店铺搜索结束。
  - 当前没有检查回库行数是否等于 ES ID 数量。假设 ES 命中 `[21,35]`，而 35 已从 MySQL 删除，最终只返回店铺 21；不会报索引不一致、自动补位或当场删除 ES 文档 35。

### 显式名称 LIKE 怎样执行

- 用户请求 `GET /shop/of/name` 时，Controller 带来 `name` 和 `current`，明确选择的是 MySQL 名称查询，不是 ES 的故障回退。
- `ShopServiceImpl.queryShopByName` 先要求页码为正，再申请同一个 `SEARCH` 许可。拿到后构造 MyBatis-Plus 查询：
  - `name` 非空白时增加 `WHERE name LIKE ?`；为空白时不增加 LIKE 条件，实际变成普通店铺分页。
  - 页大小使用 `MAX_PAGE_SIZE=10`。MySQL 返回记录后直接包装成 `Result.ok`，不经过 ES。
- 拿不到许可仍返回 429；查询运行时异常返回 503 `SEARCH_UNAVAILABLE`。它只支持名称 LIKE，没有 ES 的分词、分类过滤或地理过滤排序，ES 失败也不会自动转来调用它。

### Blog 搜索为什么没有回库

- 用户请求 `GET /blog/search` 时，`BlogServiceImpl.searchBlogs` 同样先校验正页码，再申请 `SEARCH` 许可。
- 进入后，关键词非空白就对 `title、content` 构造 `multi_match`，否则使用 `match_all`；随后设置零基页码和每页 5 条，在 `blog_index` 查询 `BlogDoc`。
- Service 直接按命中顺序收集 `SearchHit.getContent()` 并返回 `List<BlogDoc>`，没有提取 ID，也没有查询 `tb_blog` 或用户表。
  - 因此结果不会补最新 Blog 行、作者昵称和头像，也不会计算当前用户点赞状态。不能因为店铺搜索会回库，就推断所有搜索接口都会回库补事实。

## 四、两类写入怎样让读取结果随后变化

### 后台提交店铺修改后，再使详情缓存失效

这条流程由后台创建、更新或归属变更触发，不是用户读取详情时顺带执行。目标是先让 MySQL 修改确定提交，再让之后的详情读取重新加载数据。

- 后台请求经过后台会话与权限拦截后，`AdminCatalogService` 从 `AdminPrincipalHolder` 取得后台主体。没有主体返回 401；商户管理员后续查询和更新都要带自身 `merchantId` 范围。
- 创建店铺时，`createShop` 在事务中校验必填字段、坐标和金额，再决定店铺归属。
  - 平台管理员使用请求里的 `merchantId`，商户管理员只能使用自己上下文里的 `merchantId`；目标商户必须存在且启用。
  - Service 组装 `Shop`，把销量、评论数和评分初始化为 0，然后 INSERT `tb_shop`。影响行数不是 1 就抛错并回滚。
  - INSERT 成功只说明事务内已写入；Service 接着以 `previousTypeId=null` 和当前店铺注册提交后同步，尚不操作 Redis。
- 更新店铺时，`updateShop` 先按权限范围读取并锁定目标行。
  - 平台使用 `SELECT * FROM tb_shop WHERE id = ? FOR UPDATE`；商户使用 `WHERE id = ? AND merchant_id = ? FOR UPDATE`。查不到统一返回 404，既避免泄露别的商户资源，也不会继续更新。
  - Service 保存原 `typeId`，再把请求变成受控 patch：名称、图片或地址一旦传入就不能是空白；区域和营业时间会去掉首尾空白；经纬度必须同时更新且范围合法；金额不能为负；没有任何可更新字段就直接拒绝。
  - UPDATE 的关键条件仍包含店铺 ID，商户管理员还会在同一条 SQL 中加入 `merchant_id`。影响行数必须等于 1，否则返回 404 并回滚。
  - SQL 成功后，Service 把 patch 合并到先前锁定的对象，得到提交后要使用的当前分类、坐标和店铺 ID，再注册提交后同步。
- 注册方法先要求当前确实存在事务同步；不满足就抛错，让这次写入不能假装已经安排了缓存处理。Service 方法返回后，Spring 事务代理才提交 MySQL。
  - 事务回滚时不会运行 `afterCommit`，所以不会删除详情缓存，也不会修改 GEO。
  - 事务提交成功后，回调先 DELETE 精确的 `cache:shop:{shopId}`。创建时这样还能清掉新 ID 可能残留的历史空值；归属变更只执行这一步。
- 创建或普通更新的回调随后维护 GEO。
  - 更新时只要原分类存在，就从 `shop:geo:{previousTypeId}` 删除店铺 ID；创建传入的原分类是 `null`，所以没有删除动作。
  - 当前店铺同时具有 `typeId、x、y` 时，再把成员名 `shopId` 和 Point `(x,y)` 加入 `shop:geo:{currentTypeId}`。这一步会覆盖同 key 中该成员的位置。
- 详情删除、GEO 删除和 GEO 添加各自捕获 Redis 异常，所以前一步失败不会阻止后一步，也不会回滚已经提交的 MySQL。
  - 详情删除成功或失败会记录指标；异常还会写日志。当前没有持久重试任务来补做失败操作。
  - 回调结束后，后台请求按已经提交的 MySQL 结果返回；ES 更新由下面的 Canal 增量链另外发生。

### 启动时怎样准备店铺索引

`ShopIndexInitializer` 与 Canal 消费不是一条连续调用链。它是非 `test` profile 的启动初始化器，依赖 Flyway 初始化完成后执行，没有单独的启用开关。

- `@PostConstruct init` 先取得 `ShopDoc` 对应的索引操作对象，检查 `shop_index` 是否存在。
  - 索引不存在时，先创建索引，再根据 `ShopDoc` 注解生成并写入 mapping，其中名称和地址使用 IK 分析器、位置使用 `geo_point`。
  - 索引已经存在时，代码不会重新校验或更新 mapping。
- 接着调用 `shopService.list()` 一次性读取 MySQL 全部店铺，不分页，也不申请请求侧 `SEARCH` 许可。
- 初始化器逐个把 `Shop` 转成 `ShopDoc`：复制 ID、名称、地址、分类、价格、评分和销量；坐标完整时按 `y + "," + x` 生成 ES 要求的“纬度,经度”位置字符串。
- 每个文档都以店铺业务 ID 作为 ES `_id` 执行 index，全部循环完成后刷新 `shop_index`。
  - 这一步会 upsert 当前 MySQL 中的所有店铺，却不会先清空已有索引，也不会删除“ES 仍有但 MySQL 已无”的旧文档，所以它不是严格的索引重建或一致性校验。
  - 任一步抛异常都会离开 `@PostConstruct`，可能阻止应用启动；代码没有跳过坏行后继续启动的处理。
- 初始化器只处理 `shop_index`，源码中没有 Blog 的对应全量初始化器。应用启动后的 shop/blog 行变化由下面的消费者处理。

### MySQL 变更怎样增量更新 ES

ES 是搜索用的派生数据，不与 MySQL 处在一个事务里。这条流程从已经发生的数据库行变更开始，目的是让搜索索引稍后追上 MySQL，而不是阻塞原写请求等待 ES。

- MySQL `tb_shop` 或 `tb_blog` 发生行变更后，Canal Server 监听 binlog，把 FlatMessage JSON 发到 RocketMQ 的 `mysql-sync-topic`。原数据库事务不会和后面的 MQ、ES 操作共同提交或回滚。
  - 部署配置把 Canal 过滤范围设为 `local_deals.tb_shop` 和 `local_deals.tb_blog`。消费者代码本身只按消息的 `table` 路由，没有再次校验 `database` 字段，因此还依赖上游过滤和 topic 契约。
- `EsSyncConsumer` 以 `es-sync-consumer-group` 接收原始字符串，先用 JSON 转成 `CanalMessage`，再把表名映射成 SHOP、BLOG 或 IGNORED，把操作映射成 INSERT、UPDATE、DELETE 或 OTHER。
  - JSON 无法解析时记录一次失败并抛 `IllegalArgumentException`，让监听失败并交给 MQ 重试。
  - `isDdl=true` 或表名不是 `tb_shop/tb_blog` 时记录忽略并正常返回，不会写 ES。
  - 目标表消息的 `data` 为 `null` 或空列表时不能形成文档，记录失败后抛错，交给 MQ 重试。
- 合法目标消息根据表名进入 `handleShop` 或 `handleBlog`。方法先计算 `isDelete = type equalsIgnoreCase DELETE`，再为这条消息建立成功数、失败数和首个异常记录。
- 消费者按 `data` 顺序逐行处理，每行先从 Map 读取 `id` 并转成字符串；没有 ID 就把这一行记为失败，然后继续下一行。
  - `isDelete=true` 时，直接用 ID 从对应索引删除文档。
  - 其他类型都进入 upsert。店铺行会读取 `id、name、address、type_id、avg_price、score、sold`，坐标 `x、y` 同时存在时组成 `y,x`；Blog 行读取 `id、title、content、user_id、liked`。存在的数字字符串再转成相应数值类型。
  - 转换完成后构造 `IndexQuery`，同时把业务 ID 设为 ES `_id`，再写入 `shop_index` 或 `blog_index`。所以 INSERT、UPDATE 以及未知的非 DELETE 类型在当前代码中都执行目标文档覆盖，未知类型只会在指标中记作 OTHER。
  - 单行转换或 ES 操作抛异常时，内部 `catch` 保存首个异常、增加失败数并继续后续行；成功则增加成功数。因此一条消息可能已经改了一部分文档，另一部分仍失败。
- 所有行都尝试后，控制回到 `onMessage`。它根据计数把整条消息记录为 SUCCESS、FAILURE 或 PARTIAL_FAILURE。
  - 失败数为 0，监听方法正常返回，本次消息消费完成。
  - 只要失败数大于 0，就用首个异常作为 cause 抛 `IllegalStateException`，让整条消息交给 RocketMQ 重试，而不是只重放失败行。
- RocketMQ 再次投递时，消费者从消息第一行重新执行；前一次成功的行也会重复 upsert 或 delete。
  - 固定业务 ID 使相同消息覆盖同一文档，不会因一次重复投递生成另一份 `_id`。但这里没有跨行 ES 事务、成功行检查点、坏行隔离或应用内补偿任务。
  - 消费者也不比较 binlog 位点、业务版本或更新时间，因此相同目标重复执行可以收敛，不代表乱序到达的旧事件一定不能覆盖新内容。

同步停顿时，ES 保留最后一次成功写入的文档，搜索可能暂时使用旧候选。仓库虽然提供 Canal/RocketMQ 部署配置和消费者注册，但 `CanalSyncIT` 在 `test` profile 下先准备索引，再直接调用 `EsSyncConsumer.onMessage` 验证 INSERT、UPDATE、DELETE 和重复调用；它没有让 MySQL 提交真实经过 Canal Server、RocketMQ 再到消费者，因此只能证明 consumer-level 行为，不能证明完整 Canal 链路已经跑通。

## 五、并发与跨存储变化的进一步推演

**同一实例的两个请求同时遇到详情 MISS。** 请求 A 先把 FutureTask 放进 `inFlight`，然后取得一个 `DB_READ` 许可并查库；请求 B 的 Redis GET 也得到 MISS，但 `putIfAbsent` 看到 A 的任务，只在自己的线程等待。A 在 750 毫秒内完成时，A、B 得到同一个结果；A 超过这个时间时，B 返回 503，但 A 继续查库并可能写缓存。其他应用实例拥有各自的 Map 和 Semaphore，仍可能同时产生自己的加载者。

**后台更新与详情回填交错。** 读请求 A 先遇到 MISS，并在后台事务提交前查到旧行；后台请求 B 随后提交新行，并在 `afterCommit` 删除详情 key；如果 A 最后才把旧行 SET 回 Redis，就会重新留下旧缓存。当前没有缓存版本、延迟二次删除或持久修复任务，这个值要等 30 秒 TTL 或下一次明确失效再收敛。

**一条同步消息部分成功。** 假设同一消息的店铺 1 已 upsert 成功，店铺 2 因字段无法转成数字而失败，消费者仍会尝试后续行，最后抛错。重投时店铺 1 也再次覆盖，店铺 2 再次尝试；如果坏行一直不变，消费者没有只隔离这行并确认其余行的逻辑，后续处置取决于 MQ 重试机制。

## 六、放到实际业务情形里理解

**很多用户同时打开同一店铺。** 同一个实例、相同 key 的重叠请求只让加载者申请数据库许可，其余请求共享 FutureTask；这减少了一个热点 key 同时回库的次数。等待者最多等 750 毫秒，部署多个实例时每个实例仍可能各有一个加载者，`DB_READ=4` 也只是各实例自己的许可数。

**用户不断请求不存在的店铺。** 第一次明确查无记录后会写 30 秒空值，随后相同 ID 直接返回不存在。不断更换随机 ID 仍会形成不同 key，只能再由本实例数据库并发舱限制同时回源；当前详情路径没有使用布隆过滤器。

**Redis 故障导致回源增加。** 详情读取把 Redis 异常当作“缓存状态未知”，在 SingleFlight 和 `DB_READ` 内尝试 MySQL；发生 GET 异常的请求如果成为加载者，会跳过缓存写入，如果只是等待者，则共享已有任务的结果。数据库可用时仍能返回详情，但持续故障会增加回源。附近查询没有这套异常回源，GEO 调用抛出普通 Redis 依赖异常时当前返回 500。

**GEO 返回空或索引未构建。** 两种情况无法区分，都会返回普通分类 MySQL 页。用户可能看到没有距离、也不满足 5 公里范围的店铺；当前请求不会触发 GEO 重建。

**ES 不可用。** 已运行应用中的店铺和 Blog 搜索会在 `SEARCH` 舱内返回 503，不会无条件改走 LIKE；显式 `/shop/of/name` 是客户端主动选择的另一接口。非 test 应用启动时，店铺索引初始化也依赖 ES，失败可能让应用无法启动。

**ES 命中 ID，但 MySQL 已没有该行。** 店铺搜索回库后只返回仍存在的行，数量可能少于 ES 命中数，没有自动补位、严格数量校验或当场重建。Blog 搜索不回库，可能直接返回仍留在索引中的旧 `BlogDoc`。

**数据库已更新，而缓存或搜索暂时仍旧。** 详情依靠事务提交后的精确删除和短 TTL 收敛，删除失败只有日志；搜索依靠异步 Canal 消息收敛。店铺搜索虽会回库补字段，却不能修正旧索引的候选判断：改名后的店可能被旧关键词选中但展示新名称，也可能暂时无法被新关键词搜到。MySQL、Redis 与 ES 不会共同提交和回滚。

## 七、源码反查与必要修正

- 消费者读取入口与公开路径：[ShopController](../../../src/main/java/com/localdeals/controller/ShopController.java)、[BlogController](../../../src/main/java/com/localdeals/controller/BlogController.java)、[WebConfig](../../../src/main/java/com/localdeals/config/WebConfig.java)
- 三条店铺路径与 Blog 搜索：[ShopServiceImpl](../../../src/main/java/com/localdeals/service/impl/ShopServiceImpl.java)、[BlogServiceImpl](../../../src/main/java/com/localdeals/service/impl/BlogServiceImpl.java)、[ShopMapper](../../../src/main/java/com/localdeals/mapper/ShopMapper.java)
- 详情和分类缓存：[CacheClient](../../../src/main/java/com/localdeals/utils/CacheClient.java)、[ShopTypeServiceImpl](../../../src/main/java/com/localdeals/service/impl/ShopTypeServiceImpl.java)、[SingleFlightLoader](../../../src/main/java/com/localdeals/utils/SingleFlightLoader.java)
- 本实例并发限制与默认值：[LocalReadBulkhead](../../../src/main/java/com/localdeals/service/LocalReadBulkhead.java)、[TrafficControlProperties](../../../src/main/java/com/localdeals/config/TrafficControlProperties.java)、[BoundedCacheProperties](../../../src/main/java/com/localdeals/config/BoundedCacheProperties.java)、[application.yaml](../../../src/main/resources/application.yaml)
- 后台提交后失效与 GEO 增量：[AdminCatalogService](../../../src/main/java/com/localdeals/service/AdminCatalogService.java)、[人工 GEO 数据工具](../../../src/test/java/com/localdeals/tools/InfraSetupTool.java)
- ES 文档、启动初始化与增量消费：[ShopDoc](../../../src/main/java/com/localdeals/dto/ShopDoc.java)、[BlogDoc](../../../src/main/java/com/localdeals/dto/BlogDoc.java)、[ShopIndexInitializer](../../../src/main/java/com/localdeals/init/ShopIndexInitializer.java)、[EsSyncConsumer](../../../src/main/java/com/localdeals/mq/EsSyncConsumer.java)
- 局部行为测试：[CacheClientTest](../../../src/test/java/com/localdeals/utils/CacheClientTest.java)、[SingleFlightLoaderTest](../../../src/test/java/com/localdeals/utils/SingleFlightLoaderTest.java)、[SearchTrafficContractTest](../../../src/test/java/com/localdeals/service/impl/SearchTrafficContractTest.java)、[AdminCatalogServiceTest](../../../src/test/java/com/localdeals/service/AdminCatalogServiceTest.java)、[CanalSyncIT](../../../src/test/java/com/localdeals/mq/CanalSyncIT.java)

需要纠正的理解主要有六处。第一，店铺详情主路径调用的是 `queryWithPassThrough`；逻辑过期、互斥锁和 `saveShop2RedisCache` 没有被当前入口调用。第二，GEO 空结果会退为普通分类页，不等价于附近查询；GEO 调用也没有显式距离升序参数。第三，ES 失败不会自动调用名称 LIKE，显式 LIKE、店铺 ES 搜索和 Blog ES 搜索各有不同结果语义。第四，启动初始化与 Canal 增量消费是两条独立路径，直接调用消费者的测试不能证明真实 Canal 全链路。第五，`ShopIndexInitializer` 只 upsert 当前 MySQL 店铺，不会先清空索引或删除遗留文档，不能称为严格全量重建。第六，店铺 ES 搜索连续调用 `withFilter` 时会覆盖前一个过滤器，同时传分类和完整坐标并没有实现两种过滤条件共同生效。

这份笔记依据当前源码、默认配置和局部测试源码完成静态阅读；`withFilter` 的覆盖行为也核对了项目当前依赖 `spring-data-elasticsearch 4.0.9.RELEASE` 的字节码。本次没有启动服务、运行迁移、业务测试或真实 Canal 链路。
