# 第一组：每个会话负责什么流程

每次只复制下面一个代码块，再接上 `02-writing-style.md` 全文。目标文件不存在时新建，已经生成时在原文件中调整。源码入口是阅读起点，具体行为和调用顺序仍须查当前实现；每个关键方法的内部执行逻辑按通用要求在调用位置展开。

## 会话 A：登录、请求拦截与后台数据权限

```text
项目路径：/home/sd101t/IdeaProjects/hm-dianping。
本会话负责“消费者登录与请求身份恢复，以及平台、商户后台的登录、权限和数据范围”。
只编写或调整：docs/learning/flow-notes/01-login-and-access.md。

请先读 docs/learning/10-seckill.md 了解我的表达习惯，再按后附的通用写作要求编写。
旧笔记主要看 08-interview-playbook.md 的“消费者验证码与 Redis 会话”“后台身份、RBAC、商户隔离与实时会话”；02-admin-rbac-chain.md 只按需反查。

请按彼此独立的请求串起三段流程，不能把它们误写成每次请求都依次执行：
1. 消费者申请验证码、用验证码登录、创建或查到用户、生成会话，随后访问公开或受保护接口。讲清两个消费者拦截器的顺序，身份何时放入上下文、何时续期、何时清理，登出如何生效。
2. 后台账号登录，随后携带后台 token 发起请求。平台账号和商户账号放在同一条后台链中比较，沿 session 恢复、当前权限加载、入口权限检查一路讲到业务方法。
3. 选“商户修改店铺”作为具体操作，把后台账号的商户范围怎样传到资源查询和最终数据库更新讲完。平台账号如何指定目标商户，在相应位置解释。

在各自执行位置展开：验证码签发与消费 Lua 的参数、读写顺序和返回处理；没有 token、token 失效与 Redis 异常分别怎样处理；公开请求如何匹配 method 和 path；身份恢复时怎样检查账号、商户、角色及 authVersion；权限注解与资源归属分别检查什么、最终 SQL 怎样限定范围。不能只列方法名，也不要把这些正常判断全部放到流程后的权限原理一节。撤权时多个请求或连接交错执行的长推演，可以另作补充。

WebSocket 身份按另一次请求触发的独立流程描述：比较消费者 token 握手与后台一次性 ticket，说明 ticket 怎样取得、消费，连接怎样按用户或商户登记，以及发送前何时重验。握手脚本和发送前校验的内部逻辑就在各自调用位置展开，解释连接已建立后撤权与会话过期的处理。这里只讲认证和路由，不重复秒杀或发券的业务流程。

建议从这些源码开始（Java 路径相对 src/main/java/com/localdeals/）：
- config/WebConfig.java、interctptor/ 下的四个拦截器、utils/UserHolder.java。
- controller/UserController.java、service/impl/UserServiceImpl.java，以及 lua/issue_login_code.lua、lua/consume_login_code.lua。
- controller/AdminAuthController.java、service/AdminAuthService.java、service/AdminSessionService.java、service/TrustedClientIpResolver.java。
- service/AdminCatalogService.java、auth/ 下的权限与身份对象，以及相关 Mapper。
- config/WebSocketConfig.java、websocket/ 下的握手与连接实现，以及 lua/consume_admin_ws_ticket.lua。
Lua 路径相对 src/main/resources/；同时核对相关配置、V5/V6 迁移和必要测试。

业务情形优先选：商户 A 把请求中的店铺 ID 换成商户 B 的；账号已撤权但还持有旧 token；没有登录的用户浏览公开店铺；请求线程被复用时上一位用户的身份是否残留。
不要扩展为完整密码学、RBAC 教材或所有后台 CRUD 的清单。
```

## 会话 B：秒杀下单与结果恢复

```text
项目路径：/home/sd101t/IdeaProjects/hm-dianping。
本会话负责“从用户点击秒杀到查到结果，以及未完成订单如何恢复”。
只编写或调整：docs/learning/flow-notes/02-seckill.md。

请完整阅读 docs/learning/10-seckill.md，它是我的表达样本，也是需要纠错补全的草稿。再参考 09-core-business-chain-review.md 的秒杀部分，必要时查 03-seckill-order-chain.md，最终以当前源码为准。

请串起这些流程，但明确它们由不同请求、消息或定时任务触发：
1. 请求已取得消费者身份后，接收券 ID、解析可信客户端 IP，经过三维限流，生成订单号，发送事务半消息，执行 Redis 资格预占，再向 HTTP 调用方返回结果。明确同步请求停在哪里，返回订单号当时能证明什么。
2. 消息提交后，消费者接收消息、取得用户锁、检查当前预占归属，在 MySQL 事务中创建订单并扣库存，提交后尝试标记 Redis 成功并发出在线提示。
3. 用户按订单号查结果：Redis、当前用户归属、MySQL 分别什么时候检查，查到正式订单后怎样修复状态。
4. 事务结果不确定时，在相应位置说明 Broker 回查这个条件分支，展开从消息恢复 ID、检查 Redis 到决定 COMMIT、ROLLBACK 或 UNKNOWN 的逻辑；明确它另行触发，不能写成 HTTP 每次都等待回查。跨多轮的 MQ 重试、定时对账和启动恢复可各作独立小节，触发处要交代接手者和去向。

活动库存与元数据的准备按另一条后台请求说明，别拼成每次秒杀前都创建一次券；在该准备流程中讲完 MySQL 事务、提交后的 Redis 预热及直接失败结果。较长的预热失败恢复可单独展开。
限流、ID 生成、事务消息发送、本地回调、准入 Lua、消费校验、MySQL 落库、成功状态修改，都在各自调用位置展开内部逻辑。尤其要补清：
- 限流参数怎样传入，Redis 时间怎样变成窗口编号和三个计数 key，何时计数或拒绝，TTL 怎样设置，Java 怎样处理返回值。
- 订单号怎样由时间与序列组成；消息与本地上下文分别带什么，上下文怎样把回调的准入结果带回调用方。
- 准入脚本的六个 key 各是什么结构、存什么；参数、元数据、时间、库存和重复判断的实际顺序与返回结果；通过后分别怎样写库存、已购 Set、精确预占、状态 Hash 与待检查集合。
- 消费校验和标成功脚本怎样核对归属、判断状态、处理重复，以及调用方怎样根据返回值继续；不要用“校验预占”“标记成功”概括全部内部步骤。
- MySQL 插入的唯一约束、冲突后查询的顺序、条件扣库存与事务提交点。补偿或隔离若单独展开，调用处也要交代触发事实、处理动作和返回去向。
订单状态字段、TTL、待检查集合在第一次使用时说明。预占 Hash 与状态 Hash 的假设 ID 例子放在准入写入这一步旁边；不要再把正常脚本逻辑和数据结构统一移到文末。

重点核实并写入正确结果：
- 限流计数和库存不是同一件事；准入明确拒绝、本地事务回调 UNKNOWN、事务发送调用异常也不是同一结果。
- 订单主键、用户与券唯一约束，以及“同一订单重放”“同用户同券已有另一订单”“订单号属于其他用户或券”三种情况。
- MySQL 写订单与扣库存的先后和回滚范围；数据库已提交而 Redis 标成功失败时，后续谁继续处理。
- 旧已购 Set 当前是否仍被读写；标成功和补偿是否需要校验三个归属 ID 与预占映射，不能省略这些前提。
- 明确永久失败、暂时查不到结果、数据库查询异常和所有权冲突分别怎样处理；为什么有时补偿、有时保留、有时隔离。
- 消费端即时补偿与定时对账补偿的开关区别；PROCESSING、SUCCESS、FAILED 与隔离区的关系；Redis 全量丢失时恢复能力的实际范围。

建议从 VoucherOrderController、VoucherOrderServiceImpl、SeckillTrafficGuard、SeckillOrderProducer、SeckillOrderConsumer、SeckillOrderStateService、SeckillOrderReconciler 开始追查；核对 RedisIdWorker、相关初始化器、SeckillProperties、V2 迁移及当前调用到的 seckill Lua。不要把未使用的旧脚本或旧消费方案讲成现行链路。

业务情形优先选：重复点击；预占后连接中断；数据库已提交但 Redis 仍处理中；MySQL 确认库存不足；旧消息与当前预占对不上。最后列出原 10 中影响理解的关键错误与遗漏，不要逐字校对错别字。
消费者登录和 WebSocket 如何认证只交代交接，不在这里展开。不要补写项目没有的支付、退款、核销或订单自动取消。
```

## 会话 C：营销活动、领券、签到奖励与批量发券

```text
项目路径：/home/sd101t/IdeaProjects/hm-dianping。
本会话负责“普通活动券如何形成用户权益，单人、多入口和批量场景怎样共用规则”。
只编写或调整：docs/learning/flow-notes/03-voucher-grant.md。

请先读 docs/learning/10-seckill.md 了解我的表达习惯；本主题内容参考 09-core-business-chain-review.md 的营销发券部分，必要时查 05-marketing-grant-chain.md，再核对当前源码。

开头用业务含义简要说明：券定义、发券活动、用户已经领到的一份券权益分别是什么。本流程的额度与 Grant 不等于秒杀库存与秒杀订单。

主流程按业务实际拆开：
1. 把商户建立标签、配置活动、绑定普通券、启用活动作为独立的准备流程，说明关键字段怎样保存，状态与规则版本怎样改变。普通重复 CRUD 可以合并描述，影响后续资格检查的操作就在准备步骤中讲清。
2. 以“用户主动领取一次活动券”为主线，从当前用户与请求参数进入单人发券逻辑，讲到最终保存权益和通知待办、返回领取结果。把查重、取得活动锁、检查资格与版本、占用额度、写权益放回实际发生的顺序。
3. 接着说明管理员单发和签到奖励怎样进入同一核心流程：哪些身份与参数由服务端生成，哪些前置检查不同，怎样确定一次性或每日一次的防重身份。核心事务只解释一次。核实签到本身是否发券，不要把两个请求拼成一次自动动作。
4. 单独从“管理员创建批量任务”重新起笔，经过目标快照、逐批处理、逐个调用单人发券、记录结果，到任务完成或部分失败。批量创建不能接在普通用户已经领券的步骤后面，写成一次请求继续执行。
5. 通知任务从数据库待办继续，经 Redis 发布到在线提示；用户通过券包查询确认权益。在该任务的流程内讲清取得到期待办、锁定记录、构造并发布事件、更新 PUBLISHED 或失败次数与下次时间的逻辑。发布和数据库标记交错失败的完整恢复推演可以另作补充。

在实际执行位置讲清三次查重各查什么、命中后返回什么；活动锁怎样取得、锁内按什么顺序验证版本与资格；最终占额 SQL 包含哪些条件、影响行数怎样处理；额度、Grant 和通知记录怎样提交。单人 Grant 的独立事务与一批 Item 的外层事务分别包含什么，也随调用过程说明。批量快照、暂停、恢复和失败重试按各自入口展开。多个并发事务交错提交的长推演再放到后面，不把正常事务逻辑整体抽离。
Campaign、Grant、Job、Item、通知记录先用中文说清含义，再保留代码名用于反查，不要把它们画成一条连续状态机。

异常恢复必须讲清：Grant 已提交但 Item 进度未提交时下一轮怎样查重；通知发布失败与发布成功但数据库标记失败的区别；PUBLISHED 表示什么；尝试次数到上限之后是否真的停止；已有或历史 Grant 是否会自动补建通知。检查 worker 的默认开关，区分当前实现与实际已启用。

建议从 VoucherCampaignController、VoucherGrantController、MarketingAdminController、MarketingAdminService、VoucherGrantService、VoucherGrantTransactionService、VoucherBatchJobService 及两个相关 worker 开始；继续读对应 Mapper、BusinessDateProvider、签到实现、通知服务、V9～V11 迁移和 VoucherBatchProperties。只按需读取相关测试。

业务情形优先选：同一用户从用户端和后台同时领同一活动券；两个人争最后一个名额；批任务创建后成员资格改变；发到一半进程退出；券已到账但提示没收到。
后台认证沿用已经解析出的身份与商户范围，只讲发券自身的归属检查；不重写登录链，不加入支付、核销或撤券能力。
```

## 会话 D：点赞热榜，以及内容发布与关注流

```text
项目路径：/home/sd101t/IdeaProjects/hm-dianping。
本会话负责 Blog 相关的两组业务，利用共用源码减少重复阅读，只编写或调整：
- docs/learning/flow-notes/04-like-and-hot-rank.md
- docs/learning/flow-notes/05-publish-and-follow-feed.md
先完成 04，再完成较短的 05；每份都应用后附写作要求，不要写成一个庞大的内容平台综述。

请先读 docs/learning/10-seckill.md 了解表达习惯。本主题参考 09-core-business-chain-review.md 的点赞热榜部分，以及 08-interview-playbook.md 的内容、图片与关注流部分；04-blog-like-hot-rank-chain.md 按需反查。

04 要从“用户点了点赞或取消点赞”讲起：
1. 请求如何表达希望得到的状态，当前用户与 Blog 如何确定，怎样改变 MySQL 点赞关系，并在关系确实变化时同事务留下计数增量待办。
2. 写请求提交后在哪里返回。后台 worker 如何取得待办、合并同一 Blog 的增量、修改点赞数并标记本批事件，哪些操作必须同事务。
3. 热榜如何从 MySQL 聚合数构建；用户读取热榜时怎样校验 Redis 结果、回库补全或回退数据库。这些是后续任务和独立读请求，不是每次点赞都同步执行的步骤。
4. 在构建步骤中解释何时取得 generation、怎样查出候选、处理同分排序并写入临时榜；在发布位置展开 Lua 的参数、检查、替换与返回；在读取位置讲清元数据、空榜与坏榜识别及切版复查。这些正常逻辑直接写进对应流程。“较早启动的重建任务反而更晚完成”的完整并发推演可以放到后面。
说明旧点赞数据与切换标记、新写和 worker 的开关，避免把旧的 Redis 点赞方案当成当前实现。明确点赞关系、聚合计数、Redis 热榜分别保存什么，聚合滞后时怎样理解它们的关系。
异常要覆盖：重复请求；Outbox 写失败；worker 更新计数后但标记前失败；Redis 锁忙与锁服务异常的差别；永久坏事件；榜单丢失或切版；旧构建任务晚到。谁重试、谁回退、哪些情况当前需要人工处理，都要明确。

05 分别讲“作者发布”和“粉丝读取”：
1. 图片上传形成当前用户的临时资源；发布 Blog 时怎样验证图片归属并一起改变数据库状态。
2. 数据库提交后怎样尝试加入热榜和向粉丝 Feed 写入内容。热榜机制引用 04 的交接结果即可。
3. 关注、取关的 MySQL 与 Redis 写入关系，随后粉丝怎样按时间游标读 Feed、回库并恢复顺序。关注是另一请求，不是每次发布的必经步骤。
临时图片删除作为独立操作，展开状态抢占、文件删除和数据库记录处理；发布过程就地说明归属查询、条件更新及事务回滚边界；Feed 读取就在查询位置讲清 max、offset、同分计数、回库和返回下一页游标的计算。文件与数据库长期不一致、Feed 丢失及同分翻页的长场景推演可以后置。必须核实哪些失败只有日志、哪些数据丢失没有重建或补发，不要把提交后回调写成持久 Outbox，也不要为关注自动补出不存在的唯一约束。

建议从 BlogController、BlogLikeCommandService、BlogLikeOutboxWorker、BlogLikeOutboxBatchService、BlogHotRankService、BlogServiceImpl 开始；再读 UploadFileService、FollowServiceImpl、相关 Mapper、Lua、V3/V4/V7/V8 迁移与配置。全文搜索交给店铺读取与搜索会话，本会话不展开 ES。

业务情形优先选：网络重试导致连续点赞；点赞成功而计数稍后才变化；热榜重建交错完成；上传他人的图片路径；Blog 已发布而某个粉丝未看到；大量内容具有相同 Feed 时间分数。
不扩展为大 V 推拉混合架构、完整推荐系统或尚未实现的评论业务。
```

## 会话 E：店铺查询、缓存与搜索

```text
项目路径：/home/sd101t/IdeaProjects/hm-dianping。
本会话负责“用户打开店铺、查附近店铺和搜索时，请求怎样拿到数据”。
只编写或调整：docs/learning/flow-notes/06-shop-read-and-search.md。

请先读 docs/learning/10-seckill.md 了解表达习惯，再参考 08-interview-playbook.md 的读取与搜索部分，沿当前源码确认实际使用的路径。

请分别走完三类读请求，不能拼成先缓存、再 GEO、最后 ES 的连续调用链：
1. 用户打开一个店铺：接收 ID、查详情缓存、解释命中与空值、未命中时怎样合并相同请求并限制数据库并发、查 MySQL、尝试回写缓存、返回结果。分类缓存可以在相邻位置简要比较。
2. 用户查某分类的附近店铺：根据坐标和排序参数决定入口分支，从 Redis GEO 取得候选 ID 与距离，再查 MySQL、恢复顺序、返回。说明坐标缺失、指定其他排序或 GEO 空结果时，实际走的是什么语义。
3. 用户全文搜索店铺：ES 怎样筛选和排序，ID 怎样回库补齐并保留顺序。比较显式的名称 LIKE 查询，以及 Blog 搜索是否也回库；不要假设所有搜索接口完全一致。

另起独立流程讲两个写入来源：后台改店铺后何时使详情缓存失效；MySQL 变更经 Canal、RocketMQ、同步消费者更新 ES 的设计与源码边界。区分启动索引初始化与增量同步，在同步消费者的调用位置展开消息解析、表和操作类型判断、逐行转换以及 ES 更新或删除。跨多次重投和部分成功的完整故障推演可以后置。

在读取主流程中就地展开缓存值怎样解析和校验、空值怎样判断；进入 SingleFlight 时怎样确定加载者和等待者、怎样共享结果和处理超时；申请数据库或搜索并发许可后怎样执行与释放；缓存坏值或 Redis 故障时怎样回源和决定是否写回。GEO 的候选、分页、距离、顺序恢复，以及 ES 查询条件和 ID 回库过程也要按执行顺序讲清，不能只写“调用缓存工具”“查询 ES”。核对实际调用的缓存方法，不要因为工具类里保留逻辑过期、互斥锁方法就说主路径都用上了。

异常与业务情形重点：很多用户同时打开同一店铺；不断请求不存在的店铺；Redis 故障导致回源增加；GEO 返回空与索引未构建；ES 不可用；搜索命中 ID 但 MySQL 已无该行；数据库已更新而缓存或搜索暂时仍旧。
不要把单实例并发限制写成整个集群的总上限，不要把 GEO 的普通分类回退写成等价的附近查询，也不要自动补出 ES 故障时无条件 LIKE 降级。若代码未实现补偿、重建或严格数量校验，就说明实际后果。

建议从 ShopController、ShopServiceImpl、ShopTypeServiceImpl、CacheClient、SingleFlightLoader、LocalReadBulkhead、BoundedCacheProperties 开始；再读 AdminCatalogService 的更新后缓存处理、EsSyncConsumer、ShopIndexInitializer、BlogServiceImpl 的搜索方法及相关配置、Mapper 和局部测试。测试若直接调用消费者，不能写成真实 Canal 全链路已经跑通。

身份认证只交代所需上下文，热榜和 Feed 不在这里重复。不要扩展成 Redis、Elasticsearch 的完整原理教材或新的性能优化方案。
```
