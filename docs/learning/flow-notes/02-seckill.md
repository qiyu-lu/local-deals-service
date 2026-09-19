# 02. 从点击秒杀到查到结果，以及未完成订单如何恢复

用户点击秒杀，是想拿一张指定的秒杀券，并尽快知道这次请求有没有被受理。请求带来的是券 ID；当前用户已经由消费者登录链路恢复到 `UserHolder`，这里只接过用户 ID，不展开登录过程。系统不会在这个 HTTP 请求里直接完成 MySQL 下单，而是先限制入口流量、在 Redis 预占资格，再让 RocketMQ 消费者异步创建正式订单。用户拿到订单号后，可以另外发起状态查询；Broker 回查、MQ 重试、定时对账和启动回填也各有自己的触发者，不能把它们理解成同一个请求里的连续步骤。

主流程先假设活动的 Redis 快速库存和元数据已经准备好。新建秒杀券时怎样写 MySQL、怎样在提交后预热 Redis，以及预热失败以后实际能靠什么恢复，放在后面的补充说明。

## 一、点击以后，同步请求只走到“资格已经受理”

用户请求 `POST /voucher-order/seckill/{id}`，带来的是券 ID，希望先知道本次抢券是否被系统受理。当前用户已经由登录链路恢复；这段同步请求只负责入口保护、资格预占和返回查询凭据，不等待 MySQL 正式订单。

- 请求先进入 `VoucherOrderController`，从路径取得券 ID 并解析可信客户端 IP，再把二者交给 Service；Service 从 `UserHolder` 取得当前用户 ID。
  - `TrustedClientIpResolver` 先把 `request.getRemoteAddr()` 当作直连地址，只接受 IPv4 或 IPv6 字面量，再规范化成统一写法；直连地址无效时返回 `unknown`。
  - 只有直连地址落在 `local-deals.client-ip.trusted-proxies` 配置的 IP 或 CIDR 范围内，才依次尝试 `X-Real-IP`、`X-Forwarded-For` 的第一个地址。请求不是从可信代理进来时，这两个可伪造的头会被忽略，直接使用直连地址。
  - 客户端 IP 只参加入口流控，不参与订单归属；消费者登录和身份恢复到这里已经完成交接。
- 接着把券 ID、用户 ID 和客户端 IP 交给 `SeckillTrafficGuard`，在一个 Redis Lua 中检查活动总请求、单用户请求和单 IP 请求三个固定窗口。
  - 方法先读 `local-deals.traffic.seckill.enabled`。关闭时直接记录放行并返回；当前默认开启，因此要求三个输入都不为空，然后准备执行 `seckill_traffic_guard.lua`。默认窗口为 1 秒，活动、用户、IP 上限分别为 300、2、100。
  - Java 先拼三类 key 前缀：`traffic:seckill:{voucherId}:activity:`、`traffic:seckill:{voucherId}:user:{userId}:`、`traffic:seckill:{voucherId}:ip:{sha256(clientIp)}:`。大括号中的券 ID 是 Redis Cluster hash tag，让三个 key 可落在同一槽；IP 用 SHA-256 摘要进入 key。
  - 三个前缀作为 KEYS，窗口毫秒数和三个上限作为 ARGV 传给 Lua。脚本先把四个参数转成数值，要求窗口至少 1000 毫秒、三个上限都大于零；否则返回 Redis 错误，由 Java 按流控依赖不可用处理。
  - 脚本调用 Redis `TIME` 取得秒和微秒，换算成当前毫秒，再计算 `floor(当前毫秒 / 窗口毫秒)` 得到窗口编号，把它接到三个前缀后形成当前窗口的实际计数 key。
  - 接着分别 `GET` 活动、用户和 IP 计数；没有值或不能转成数值时按 0。脚本依次判断活动、用户、IP 是否已达到上限，分别返回 1、2、3。
    - 这三种超限拒绝都发生在写入之前，所以返回 1、2、3 时三个计数都没有增加；这里的结论针对正常超限分支。
  - 三个维度都未超限时，脚本依次 `INCR` 三个 key，把它们的 TTL 都设为 `2 × 窗口毫秒 + 1000 毫秒`，最后返回 0。TTL 只负责清理旧窗口，不会把固定窗口变成滑动窗口。
  - 控制回到 Java：0 正常返回；1、2、3 记录对应超限原因并抛出映射为 HTTP 429 的异常；Redis 执行异常、空结果或约定外返回值都映射为 HTTP 503。限流计数记录的是入口请求量，不是库存或购买资格，后续失败不会退还计数。
- 三维限流通过后，`RedisIdWorker` 生成一个贯穿 Redis、MQ、MySQL 和查询接口的订单号。
  - 外层调用 `nextId("order")`。方法先取 `LocalDateTime.now()`，把这份本地日期时间按 UTC 偏移转成秒，再减去固定起点 `1766432420` 得到相对时间戳；这不是直接读取当前 UTC 时间戳。随后再次取本地日期拼出 `icrorder:yyyy:MM:dd`，对这个 Redis key 执行 `INCR` 得到当日序列号。
  - `INCR` 正常返回后，方法计算 `(相对时间戳 << 32) | 当日序列号` 并返回 `long`。当前实现没有给每日序列 key 设置 TTL；如果 Redis 客户端异常，异常会向外抛出，外层返回 HTTP 503，此时流控可能已经计数，但尚未发送消息或预扣库存。
  - 当前代码把罕见的 `INCR` 空返回按序列号 0 继续拼 ID，而不是当作依赖失败；这属于实现边界，正常 Redis `INCR` 不应返回空值。
  - 对 HTTP 和 WebSocket 输出时使用订单号的十进制字符串，避免 JavaScript 无法精确表示 64 位整数。
- 生产者拿着 `voucherId、userId、orderId` 构造消息和本地事务上下文，调用 RocketMQ 发送事务消息。Broker 先保存消费者不可见的 half message，也就是半消息，并返回发送确认；生产者客户端收到成功确认后，才在生产者端执行本地事务回调。
  - `sendSeckillTransaction` 把三个 ID 同时放进 `SeckillOrderMessage` 和 `LocalTransactionContext`。消息发到配置的 `local-deals.seckill.topic`；上下文中的 `admissionResult` 初始为 `-1`，用于把本地回调的准入结果带回正在等待的 HTTP 线程。
  - `sendMessageInTransaction` 会等待本地事务回调执行。当前方法不使用 RocketMQ 返回的 `SendResult`，而是在调用正常结束后返回上下文中的 `admissionResult`；整个发送调用抛异常时捕获并返回 `-1`。
- RocketMQ 生产者客户端调用 `executeLocalTransaction` 后，生产者从上下文取得三个 ID，再执行现行准入脚本 `seckill_check.lua`。这里的“本地事务”不是 MySQL 事务，而是一个原子 Redis Lua。
  - Java 按顺序传入六个 KEYS：快速库存 String `seckill:stock:{voucherId}`、旧已购 Set `seckill:order:{voucherId}`、活动元数据 Hash `seckill:meta:{voucherId}`、精确预占 Hash `seckill:reservation:{voucherId}`、单笔状态 Hash `seckill:order:status:{orderId}`、全局待检查 ZSET `seckill:order:processing`。
  - ARGV 是用户 ID、券 ID、始终按字符串传递的订单号，以及配置中的 `stale-after` 秒数。订单号不转成 Lua number，避免 64 位整数丢精度。
  - 脚本先要求 `stale-after` 大于零，再从元数据 Hash 一次读取 `status、beginAt、endAt`。字段缺失、不能转成时间或开始时间晚于结束时间返回 5；状态不是 `ACTIVE` 返回 4。
  - 元数据可用后，脚本读取 Redis `TIME` 的秒值。当前时间早于 `beginAt` 返回 3，晚于 `endAt` 返回 4；接着读取快速库存，库存缺失、不能转成数值或不大于零返回 1。
  - 库存可用后，脚本先用 `SISMEMBER` 检查用户是否在旧已购 Set，再用 `HEXISTS` 检查 reservation 中是否已有该用户；任一命中都返回 2。旧 Set 仍参与当前读写，但只能证明用户买过，不能证明某个订单号的所有权。
  - 所有判断通过后，脚本才开始写入，而且这些写入和前面的判断同属一次 Lua：
    - `DECR` 快速库存，并把用户加入旧已购 Set。
    - 在 reservation Hash 中写 `userId -> orderId`，让 Broker 回查、Consumer、成功标记和补偿以后能确认是不是同一次操作。
    - 在单笔状态 Hash 中写 `PROCESSING、orderId、userId、voucherId、createdAt、updatedAt、reason=""、reconcileAttempts=0`，随后执行 `PERSIST`，不让过程状态因旧 TTL 提前消失。
    - 把订单号加入 `seckill:order:processing`，score 为 `Redis 当前秒 + stale-after`，表示最早何时允许 Reconciler 检查，不是支付截止时间；最后返回 0。
  - 假设用户 `42` 抢券 `1001`，订单号为字符串 `570000000000000001`，成功后 reservation 中保存 `42 -> 570000000000000001`，状态 Hash 的 key 是 `seckill:order:status:570000000000000001`，内部也保存同一订单号、用户 42、券 1001 和 `PROCESSING`。后续消息必须同时匹配这两份数据，不能借用别的预占。
- 准入 Lua 返回后，生产者把结果写回上下文，并决定 half message 的去向。
  - 返回 0 时向 Broker 返回 `COMMIT`，消息随后才可被 Consumer 看见。
  - 返回 1～5 时向 Broker 返回 `ROLLBACK`。这些拒绝都在准入脚本第一次写操作前发生，因此没有 Redis 资格需要补偿；外层把 1～4 分别变成库存不足、重复、未开始、结束或暂停的业务失败，把 5 变成 HTTP 503“活动正在初始化”。
  - Lua 抛异常时，回调把上下文写成 `-1`；Lua 返回空值也按 `-1` 处理；约定外返回码则原样写进上下文。三种情况都向 Broker 返回 `UNKNOWN`，由 Broker 后续回查。`sendMessageInTransaction` 调用自身抛异常则只说明调用方没拿到正常结果，不能据此确定 Broker 是否收到半消息；这两种不确定不能混为一谈。
- 事务发送正常返回且准入结果为 0 时，同步接口立即返回订单号字符串，到这里 HTTP 请求结束，后面的 MySQL 落单由 Consumer 另外执行。
  - 正常返回能证明本次获得了关联号、Redis 已写下精确预占和 `PROCESSING`，生产者也作出了 `COMMIT` 决定；它不能证明消息已经消费、MySQL 已落单、Redis 已变成 `SUCCESS` 或在线提示已经送达。
  - 如果生产者返回系统错误，上层只在状态 Hash 的三个 ID 与本次请求一致、状态为 `PROCESSING` 或 `SUCCESS` 时恢复返回该订单号，否则返回 HTTP 503。这个恢复分支没有再次检查 reservation，也不能证明 Broker 已提交，所以订单号始终只是后续查询凭据。

## 二、消息提交后，消费者另外完成正式落单

这一段由 RocketMQ 投递已提交消息触发，不在原 HTTP 线程中执行。Consumer 拿到的是前面同一组 `voucherId、userId、orderId`，希望把 Redis 资格变成 MySQL 正式订单。

- Consumer 先检查消息中的三个 ID 是否齐全，再尝试取得 `lock:order:{userId}`，让同一用户的消费和定时对账串行处理。
  - 消息为空或任一 ID 缺失时，不访问锁、Redis 状态或 MySQL，直接抛出参数异常，让 RocketMQ 重试并最终可能进入 DLQ。
  - 消息完整时，用用户 ID 拼出 Redisson 锁。`tryLock()` 不等待；不同用户使用不同 key，仍可并发。拿不到锁或访问锁服务异常时直接抛错，交给 RocketMQ 重试。
  - 这把锁只让同一用户的 Consumer 与 Reconciler 不同时推进，不能替代后面的 Redis 归属检查和数据库约束；处理结束时由当前线程释放。
- 拿到锁后，Consumer 把状态 Hash、该券的 reservation Hash、全局隔离 ZSET 作为 KEYS，把三个 ID 作为 ARGV，调用只读脚本 `seckill_validate_reservation.lua`。
  - 脚本先用订单号查询 `seckill:order:processing:quarantine`。已经隔离就返回 4，不再相信其余状态。
  - 未隔离时，从 `seckill:order:status:{orderId}` 一次读取 `status、orderId、userId、voucherId`。任一字段缺失返回 0；三个 ID 与消息不完全相同返回 4。
  - 三个 ID 匹配后，`SUCCESS` 返回 2，`FAILED` 返回 3；既不是这两个终态也不是 `PROCESSING`，返回 0。
  - 状态为 `PROCESSING` 时，最后读取 `seckill:reservation:{voucherId}` 中该用户的值；只有它等于消息订单号才返回 1，否则返回 4。旧已购 Set 不参与这个消费门禁，因为它不包含订单号。
  - 控制回到 Consumer：1 表示 `PROCESS`，继续写 MySQL；2 或 3 表示同一归属已经成功或失败，直接正常返回并确认消息；0 表示状态暂缺或未知，抛错等待重试；4 表示 `POISONED`，不碰 MySQL，继续重试并最终可能进入 DLQ。脚本返回约定外值或 Redis 抛错也按异常重试。
- 校验通过后，Consumer 把消息转换为 `VoucherOrder`，进入一个独立的 Spring MySQL 事务。事务先插入 `tb_voucher_order`，再执行条件扣库存：

  ```sql
  UPDATE tb_seckill_voucher
  SET stock = stock - 1
  WHERE voucher_id = ? AND stock > 0;
  ```

  - `msg.toVoucherOrder()` 把消息中的订单号、用户 ID、券 ID 放进订单实体。`save` 必须影响一行，否则抛异常；订单号 `id` 是主键，V2 又增加了 `(user_id, voucher_id)` 唯一约束。
  - 插入成功后，再执行 `stock = stock - 1 WHERE voucher_id = ? AND stock > 0`。条件更新必须命中一行；没有命中就抛 `StockExhaustedException`。
  - 插订单和扣数据库库存属于同一个 MySQL 事务。库存更新失败或方法向外抛异常时，刚插入的订单一起回滚；方法正常返回时事务提交，正式订单与数据库库存扣减一起成立。这里只创建订单，不延伸支付、退款、核销或自动取消。
- 如果订单插入抛 `DuplicateKeyException`，事务方法不会笼统当成成功，而是先按订单主键查写库；主键查不到时，才按用户和券再查一次，区分下面的冲突。
  - 按 `orderId` 查到的行同时属于相同用户和券，说明同一消息此前已经落库。这次直接返回，不再执行库存更新；外层随后继续尝试 Redis `markSuccess`。
  - 按 `orderId` 查到的行属于其他用户或其他券，抛 `OrderIdConflictException`。当前事务没有新增订单或扣库存；Consumer 捕获后先暂停活动，再把订单号移出待检查 ZSET，写入隔离 ZSET 和隔离原因 Hash。它不补偿，并继续抛错，让 MQ 保留这条有毒消息。
  - 订单号不存在时，再按 `(user_id, voucher_id)` 查询已有行；查到另一订单就抛 `OrderReservationConflictException`，表示数据库已有同用户同券正式订单，而当前 Redis 是多余预占。异常离开事务后，Consumer 进入即时补偿。
  - 当前还有一个实现边界：捕获唯一键异常后，如果主键查询和用户—券查询都返回空，代码仍会抛 `OrderReservationConflictException`，异常文案中的 `persistedOrderId` 为 null。这个分支也会触发即时补偿，不能把它描述成已经查到了另一笔订单。
- Consumer 捕获库存不足或预占冲突异常时，本次 MySQL 事务已经回滚。它先把 `seckill:meta:{voucherId}` 的状态写成 `SUSPENDED`，记录原因和更新时间，再调用 `seckill_compensate.lua` 精确释放当前预占。
  - 补偿脚本收到快速库存 String、旧已购 Set、reservation Hash、状态 Hash、待检查 ZSET、隔离 ZSET 六个 KEYS，以及三个 ID、失败原因、7 天 TTL。
  - 它先拒绝已隔离订单，再读取状态的 `status、orderId、userId、voucherId`。如果已经是同一归属的 `FAILED`，就只清理可能残留的 due、刷新 TTL 并返回 2，不再次加库存。
  - 否则脚本要求 `reservation[userId]` 等于当前订单号，并要求状态仍是三个 ID 完全匹配的 `PROCESSING`；任一条件不满足返回 0，不改数据。
  - 条件全部满足后，脚本在一次 Lua 中给快速库存加一、删除 reservation、从旧已购 Set 删除用户、把状态改成 `FAILED` 并写失败原因、移出待检查 ZSET、设置状态 7 天 TTL，最后返回 1。
  - Java 把 1 和 2 都视为补偿成功；其他返回值（包括 0、空值）还会再读取状态 Hash，只有三个 ID 完全匹配且已经是 `FAILED` 才同样视为成功。复查不匹配就返回 false，由 Consumer 抛错重试；脚本或复查抛异常也交给 MQ 重试。补偿成功后 Consumer 尽力提示失败并正常返回，消息得到确认。这个消费端即时补偿不受 Reconciler 的 `compensation-enabled` 开关控制。
- MySQL 事务成功返回后，正式订单和数据库扣库存已经提交。Consumer 接着执行成功 Lua，再次要求 reservation 和状态中的三个 ID 完全匹配，才把 `PROCESSING` 改成 `SUCCESS`、移出待检查 ZSET 并给状态设置 7 天 TTL。
  - `markSuccess` 把状态 Hash、reservation Hash 和待检查 ZSET 作为 KEYS，把三个 ID 与 7 天 TTL 作为 ARGV。脚本先检查 `reservation[userId] = orderId`，再读取状态中的三个归属 ID；任一不匹配就返回 0。
  - 三个 ID 匹配后，状态已经是 `SUCCESS` 时只移出 due、刷新 TTL 并返回 1；状态不是 `PROCESSING` 时返回 0；仍是 `PROCESSING` 时用 Redis TIME 更新时间，改为 `SUCCESS`、删除失败原因、移出 due、设置 TTL，再返回 1。成功后的 reservation 不删除，继续作为归属证据。
  - Java 只有收到 1 才继续。返回 0 或 Redis 异常时，Consumer 抛错等待 MQ 重投；由于 MySQL 已提交，此时绝不能退库存。重投会由数据库主键识别为同一订单，不再扣一次库存，只继续尝试标成功。
- Redis 成功收敛后，Consumer 才经 Redis Pub/Sub 尽力发送用户和后台在线提示，到这里本次消费结束。
  - `WebSocketNotifier` 组装 `SECKILL_RESULT`，其中包含成功标记、字符串订单号、券 ID 和提示文案；先发布到用户频道，再尝试平台和券所属商户频道。
  - 任一步提示失败只记录日志，不撤销订单、不重新投递消费消息，也没有持久补发。用户是否收到提示不是业务成功条件，最终仍以订单查询为准；WebSocket 认证不在这里展开。

## 三、用户拿订单号查询结果，是另一条 GET 请求

用户随后请求 `GET /voucher-order/status/{orderId}`，带来的是前面取得的订单号，希望知道资格仍在处理、已经形成正式订单，还是已经明确失败。这是新的 HTTP 请求，当前用户仍从消费者身份上下文取得。

- 查询先读取 `seckill:order:status:{orderId}`。如果得到字段完整的状态，先比较其中的 `userId` 与当前用户。
  - `SeckillOrderStateService.find(orderId)` 对这个 Hash 执行 `HGETALL`，把 `orderId、userId、voucherId` 解析为 Long，并读取 `status、reason`。Hash 为空，或四个必要字段任一缺失、ID 不能解析，就返回 `null`；Redis 访问异常则由外层单独记录为不可用。
  - 得到 Snapshot 后，Service 先比较其中的 `userId` 与 `UserHolder` 中的当前用户。归属其他用户就返回“订单不存在或无权查看”，不再拿这个订单号探测 MySQL。
- 如果 Redis 状态是 `PROCESSING`，接着按请求订单号查询 MySQL，并要求数据库订单属于当前用户、数据库券 ID 与 Redis 券 ID 一致。
  - `findOwnedPersistedOrder` 先按主键 `getById(orderId)` 查 `tb_voucher_order`，只有查到的行也属于当前用户才返回；随后外层再比较数据库券 ID 与 Redis Snapshot 的券 ID。
  - 查到完全一致的正式订单，就先用 Redis Snapshot 中的三个 ID 尽力调用前面已经展开的 `markSuccess` 修复状态，然后拿 MySQL 行组装 `orderId、voucherId、SUCCESS` 返回值。
  - `markSuccess` 返回 0 或抛异常只记录日志，不覆盖这次由 MySQL 证明的 `SUCCESS`。MySQL 正常返回空时，则继续把原 Redis `PROCESSING` 返回给用户，表示目前还不能判成永久失败。
  - MySQL 查询抛异常不会变成空结果，而是继续交给统一异常处理，当前返回 HTTP 500“服务器异常”。
- 如果完整的 Redis 状态不是 `PROCESSING`，当前实现直接返回 Redis 结果，不再查询 MySQL。
  - 返回 DTO 中的订单号仍转为字符串。`SUCCESS` 表示 Redis 已经收敛成功；`FAILED` 会把 `DB_STOCK_EXHAUSTED、DB_ORDER_CONFLICT、PROCESSING_TIMEOUT` 等内部原因转换为用户文案。
- 如果 Redis Hash 不存在、缺必要字段，或者 Redis 查询异常，就转而按请求订单号查询 MySQL，并只向订单所属用户返回结果。
  - 这里同样按主键查订单，再在 Java 中比较当前用户。查到自己的正式订单就根据 MySQL 行返回 `SUCCESS`，但不会凭空重建缺失的状态 Hash 或 reservation。
  - Redis 正常且 MySQL 也没有当前用户的订单，返回“订单不存在或状态已过期”；Redis 异常且 MySQL 没查到，则返回“订单状态暂不可用，请稍后重试”。
- 查询到这里返回的是当前用户可见的状态，不会启动一条新的下单流程。暂时未查到时由用户以后重新请求；已由 MySQL 证明成功但 Redis 修复失败时，还可由 MQ 重投或启用后的对账继续修复。
  - 修复本身仍要求 reservation 和状态中的三个 ID 精确匹配；Redis 状态完全丢失时，当前查询只能返回 MySQL 结果，不能重建整套协议证据。
  - `find()` 当前只要求 Hash 中四个字段能解析，没有校验其中的 `orderId` 等于请求 key 后缀，也没有限制 `status` 必须属于三个已知值。字段完整但内容异常且非 `PROCESSING` 的 Hash 可能被直接返回，这是现行实现边界。

## 四、主流程中断后，四种机制从不同入口继续

### 1. Broker 事务回查：裁定半消息提交还是回滚

触发者是 RocketMQ Broker。它要判断一条未取得明确结果的半消息能否提交；这不是定时对账，也不查询 MySQL。

- 本地事务回调曾返回 `UNKNOWN`，或者 Broker 长时间没有收到明确的提交或回滚结果时，Broker 拿原消息调用 `checkLocalTransaction`。
- 回查时原来的本地事务上下文已经不在，只能把消息 payload 反序列化为 `SeckillOrderMessage`。消息无法解析或任一 ID 缺失时直接返回 `ROLLBACK`。
- 消息完整时，先以订单号读取隔离 ZSET 的 score；已经隔离就返回 `ROLLBACK`。未隔离时再从该券 reservation Hash 读取用户对应的订单号，缺失或不等于消息订单号也返回 `ROLLBACK`。
- reservation 匹配后，回查从状态 Hash 一次读取 `status、orderId、userId、voucherId`。
  - 读取结果为空、长度不对或 `status` 字段缺失时返回 `UNKNOWN`；`status` 存在但三个归属 ID 有缺失或与消息不完全一致时，返回 `ROLLBACK`。
  - 三个 ID 匹配后，`PROCESSING` 或 `SUCCESS` 返回 `COMMIT`，让消息可消费；`FAILED` 返回 `ROLLBACK`；其他状态返回 `UNKNOWN`。Redis 任一步异常也返回 `UNKNOWN`，让 Broker 稍后再查。
- 回查到这里仅裁定半消息，不执行库存补偿。
  - `ROLLBACK` 可能是因为 reservation 已被补偿，也可能是所有权冲突；它不能一概解释为“回查刚刚退了库存”。

### 2. MQ 消费重试：重复执行同一条已提交消息

触发者是 RocketMQ 的消费失败重投。它重新执行同一条已提交消息，目标是让暂时失败的处理继续完成，而不是创建一笔新业务。

- 消息缺字段、用户锁忙、Redis 状态暂缺、所有权不安全、数据库暂时异常，或者 MySQL 已提交但 Redis 标成功失败时，Consumer 会抛异常。
- RocketMQ 下一次仍投递相同的三个 ID，Consumer 重新取得用户锁并核对精确预占，再决定能否访问 MySQL。
  - Redis exact reservation 限制哪条消息有资格写库；MySQL 主键和用户—券唯一约束识别重复；同一订单已存在时不再扣库存，只继续补做 `SUCCESS`。
- MySQL 明确库存不足或同用户同券已有另一订单时，Consumer 走前面说明的暂停活动和即时补偿；补偿成功就确认消息，失败才继续重试。
  - 即时补偿不受 Reconciler 的 `compensation-enabled` 控制。订单号属于其他用户或券时只隔离、不补偿，并继续抛错。
- 多次重试仍失败的消息最终可能进入 RocketMQ DLQ。当前没有自动消费该 DLQ 并改写订单结果的方案，因此这里留下的是待人工排查的消息现场。

### 3. 定时对账：按长期 MySQL 事实处理超期 PROCESSING

触发者是 Spring 定时调度器，但只有 `local-deals.seckill.reconciliation.enabled=true` 时 Reconciler 才会创建并运行，当前默认关闭。它不重发 MQ，而是按 MySQL 长期事实重新判断超期 `PROCESSING`。

- 每轮先从 `seckill:order:processing` 有界读取已经到期的订单号。默认首次延迟 30 秒、每 10 秒一轮，准入 2 分钟后到期，每次最多处理 100 条。
  - `seckill_reconcile_due.lua` 接收全局待检查 ZSET 和批量上限，读取 Redis `TIME`，执行 `ZRANGEBYSCORE key -inf now LIMIT 0 limit`。它只返回成员，不先删除，避免任务取到以后崩溃就永久丢失待办。
  - Java 把成员解析为不带前导零的正 Long。非法原始成员不能转换成业务订单号，会从 due 移到隔离 ZSET，并在隔离原因 Hash 中记录 `INVALID_PROCESSING_INDEX_MEMBER`；单个坏成员不阻断本批其他订单。
- 对账先取得订单级调度锁，再从状态 Hash 解析用户并取得与 Consumer 共用的用户锁，避免两个执行者同时处理同一用户现场。
  - 先用订单号取得 `lock:seckill:reconcile:{orderId}` 并立即 `tryLock`，只有获胜实例继续；它随后按 key 对应的订单号读取状态 Snapshot，不能改用 Hash 内可能错误的订单号隐藏冲突。
  - Snapshot 无法解析时，当前任务拿不到可信用户 ID，也就不能取得与 Consumer 相同的锁。它调用 defer Lua：若成员仍在 due，就把 score 改成 `Redis 当前秒 + retry-delay`；终态或已隔离则只清掉 due。这里不在锁外猜测性补偿或隔离。
  - Snapshot 可解析时，用其中的用户 ID 取得 `lock:order:{userId}`。锁忙同样只延后 due；拿到锁后才允许 claim、查库和状态迁移。
- 拿到共享用户锁后，`seckill_reconcile_claim.lua` 接收状态 Hash、该券 reservation Hash、due ZSET、隔离 ZSET，以及三个 ID 和 `retry-delay`。
  - 脚本读取 Redis 时间，先处理已隔离；再读取 `status、orderId、userId、voucherId、createdAt、reconcileAttempts`，校验三个 ID、已知状态、合法创建时间和十进制尝试次数，随后检查 reservation 与 due score。
  - 已隔离返回 8；终态返回 3 并清 due；归属、状态、reservation 不安全分别返回 4、5、6；due 已不存在返回 7，尚未到期返回 2。这些情况都不会查询 MySQL，其中 4～6 会在当前用户锁内移入隔离区。
  - 只有 exact `PROCESSING`、精确 reservation 且确实到期时返回 1。脚本同时把 `reconcileAttempts` 加一，写 `lastReconcileAt/updatedAt`，让状态保持无 TTL，并把 due score 后移到 `now + retry-delay`；所以对账随后崩溃，记录以后仍会再次到期。
- claim 成功后，`classifyPersistence(orderId, userId, voucherId)` 在写库的只读 MySQL 事务中分类，不从只读副本猜测结果。
  - 它先按 `orderId` 查订单：三个 ID 完全相同返回 `EXACT_MATCH`，同一订单号属于其他用户或券返回 `ORDER_ID_CONFLICT`。
  - 主键没有订单时，再按 `(user_id, voucher_id)` 查询：存在另一订单返回 `USER_VOUCHER_CONFLICT`，仍不存在才返回 `ABSENT`。查询抛异常会原样传回 Reconciler，绝不转成 `ABSENT`。
- Reconciler 根据分类结果继续：
  - 当前 `orderId/userId/voucherId` 的正式订单存在，就再次 `markSuccess`，成功后尽力提示用户。
  - 当前订单明确不存在，就用 claim 返回的 Redis 当前时间减 `createdAt` 计算年龄；未到最终超时保留 `PROCESSING`，超过期限后仍要看补偿开关。
  - 同用户同券已有另一 `orderId`，先暂停活动；补偿开关关闭时保留 `PROCESSING`，开启时才精确补偿。
  - 当前 `orderId` 属于其他用户或券，始终暂停活动并隔离，永不自动补偿。

- `compensation-enabled` 是 Reconciler 独立的破坏性操作开关，当前也默认关闭。明确无订单超过默认 15 分钟最终期限后，开关关闭仍保留 `PROCESSING`，开启才可精确补偿为 `FAILED`。
  - 数据库查询异常绝不转换为 `ABSENT`，只保留现场等待下一轮；订单号所有权冲突无论开关如何都只隔离，不恢复库存。
- `PROCESSING、SUCCESS、FAILED` 保存在每单状态 Hash 中，隔离区则是独立的安全边界，不是第四种业务状态。
  - 隔离脚本只把订单从 due ZSET 移除，再把订单号和 Redis 当前时间写入 quarantine ZSET、把原因写入单独 Hash；它不改原业务状态、不删 reservation、不恢复库存。Broker 回查、Consumer 和补偿脚本以后都会先看到隔离记录并停止危险动作。

### 4. 启动回填：补现有索引，不替代在线对账

项目有两类容易混淆的启动动作。

- 非 `test` 环境启动时，活动初始化器读取全部 `tb_seckill_voucher`，校验券 ID、库存和时间，再用 Lua 只补 Redis 中缺失的快速库存和元数据字段。
  - 它先把全部数据库行校验完：券 ID 必须存在、库存不能为负、开始和结束时间必须存在且开始早于结束；只要有非法行就不开始逐券写 Redis，并阻止启动。
  - 每张券把数据库库存、上海时区换算出的开始和结束 epoch 秒传给 `seckill_voucher_backfill.lua`。脚本分别检查快速库存 key 以及元数据的 `status、beginAt、endAt`，只写缺失项；缺 `status` 才写 `ACTIVE`，已有 Redis 值不会覆盖。
  - 脚本用 1、2、4、8 的位掩码表示本次补了哪些字段，Java 只统计返回值非 0 的券数。数据库读取失败、Lua 返回空或 Redis 异常都会阻止启动。
- 只有 `backfill-on-startup=true` 时，另一项一次性任务才扫描仍存在的 `seckill:order:status:*`，为精确的旧 `PROCESSING` 补待检查 ZSET，并让该状态保持无 TTL。
  - 这个开关默认关闭，并且配置校验不允许它与在线 Reconciler 在同一次启动中同时开启。任务使用 Redisson 的 SCAN 迭代 `seckill:order:status:*`，每次扫描提示数默认 500，不枚举体积可能更大的 reservation Hash。
  - Java 从状态 key 后缀解析规范正 Long，再用这个 key 中的订单号和 Snapshot 的用户、券构造消息。`seckill_reconcile_backfill.lua` 重新检查隔离、状态中的三个 ID、`PROCESSING`、`createdAt` 和 reservation；不能只相信 Java 读到的字段。
  - exact `PROCESSING` 会先 `PERSIST`，再用 `ZADD NX` 按 `createdAt + stale-after` 补 due；已经有 score 就不覆盖。已有终态只清理可能残留的 due，已隔离也清 due。
  - 规范订单号下的归属、状态或 reservation 不安全时保留原证据并累计为 unsafe，任务结束后使启动失败；无法解析订单号的原始 key 才写入隔离区和原因 Hash。
- PROCESSING 启动回填到这里不会查询 MySQL、创建订单、发送消息或直接改成成功/失败。它只补后续扫描入口；以后是否查库和补偿，要由另一次启动时启用的 Reconciler 及其补偿开关决定。

## 五、活动创建和 Redis 预热为什么放在主流程之外

创建秒杀券是后台的另一条请求，它为前面的用户秒杀准备 MySQL 活动事实和 Redis 准入数据；后台身份和权限检查不在本篇展开。

- 后台创建入口先把店铺 ID 和创建请求交给 `AdminCatalogService.createVoucher`。它完成店铺范围和请求字段校验后，组装 `Voucher`；秒杀券额外带上库存、开始时间和结束时间，再调用 `VoucherServiceImpl.addSeckillVoucher`。
- `addSeckillVoucher` 运行在 MySQL 事务中，先要求请求对象和库存存在、库存不小于零，并要求开始和结束时间都存在且开始时间早于结束时间。
  - 校验通过后，把券状态设为上架 `1`、类型设为秒杀券 `1`，先插入 `tb_voucher` 主记录。保存返回 `false` 就抛异常。
  - 主记录生成券 ID 后，再组装 `SeckillVoucher`：以同一券 ID 为主键，复制库存、开始时间和结束时间，插入 `tb_seckill_voucher`。第二次保存返回 `false` 也抛异常，因此前一条主记录一起回滚。
- 两次数据库保存都成功后，方法准备 Redis 预热所需的数据，但此时还没有写 Redis。
  - 它要求生成后的券 ID、库存和两个时间都不为空，再按 `Asia/Shanghai` 把开始、结束时间转成 epoch 秒。
  - 接着确认当前确实存在活动事务同步，并注册 `afterCommit` 回调；没有活动事务就立即抛异常，避免把“脱离事务保存后无法注册预热”当成正常路径。方法正常结束后，Spring 先提交两张表，秒杀券的 MySQL 事实才成立。
- MySQL 提交成功才触发 `afterCommit`：回调先用 `SET` 把库存写入 `seckill:stock:{voucherId}`，再用 `HSET`/`putAll` 把 `status=ACTIVE、beginAt、endAt` 写入 `seckill:meta:{voucherId}`。
  - 这样不会在数据库回滚时留下 Redis 幽灵库存。两个 Redis 写操作不在同一个 Lua 或 Redis 事务中；若第一步成功、第二步失败，会暂时只有快速库存而没有完整元数据，前面的准入 Lua 最终返回 5，不会只凭库存放行。
- after-commit 预热失败时，当前代码只记录错误，创建请求仍可能已经成功，也没有持久待办在运行中自动重试。
  - 在 Redis 数据补齐前，用户秒杀会因为库存或元数据缺失而拒绝；已经提交的 MySQL 记录不能由这个失败反向回滚。
- 非 `test` 环境下一次启动时，活动初始化器会从数据库补 Redis 中缺失的库存和元数据字段。
  - 它不会覆盖已有错误值或 `SUSPENDED`。不能等待重启时，只能由运维按数据库事实人工修复；当前没有 Outbox 或在线定时预热任务。

## 六、放进具体业务情形里理解

### 1. 用户没看到响应，又重复点击

第一次点击若已经准入，旧 Set 和 reservation 都记录了用户；第二次如果依次通过限流、活动和库存检查，才会在查重处返回重复，新订单号不会形成状态。如果已经限流、活动结束或库存耗尽，会先返回相应拒绝，未必走到查重。若第一次连接是在预占后中断，客户端可能没拿到订单号；服务端仍可由 Broker 和 Consumer 继续落库，但当前秒杀接口没有按“用户 + 券”反查这次订单号的恢复入口，在线提示也不保证送达。用户侧不能假设重复点击会返回第一次的订单号。

### 2. 预占成功后，HTTP 连接中断

断开连接不会撤销 Redis 预占或事务消息。若 Broker 已提交，Consumer 仍会异步落库；若本地事务结果是 `UNKNOWN`，Broker 会按 Redis 精确预约回查。系统不能因为客户端没收到响应就加回库存，因为“客户端不知道结果”与“服务端没有执行”不是同一件事。

### 3. 数据库已提交，但 Redis 仍是 PROCESSING

这通常发生在 MySQL 事务提交后，成功 Lua 返回失败或 Redis 暂时不可用。此时正式订单和数据库库存已经成立，补偿会制造多放库存。MQ 重投会先看到仍是 exact `PROCESSING`，数据库插入撞到相同主键后识别为同一订单重放，不再扣库存，再次执行成功 Lua；用户查询和启用后的 Reconciler 也能查到 exact MySQL 行并尝试修复。

### 4. Redis 放行，但 MySQL 确认库存不足

这说明快速库存与数据库库存已经不一致。MySQL 的条件更新没有命中，刚插入的订单随事务回滚；Consumer 先把活动暂停，再做 exact 补偿，把这次 Redis 快速库存加回、删除两种已购证据并把状态改为 `FAILED`。只有补偿完成才确认消息并提示失败；补偿本身失败则继续抛错等待 MQ 重试。这里可以补偿，是因为数据库事务已明确回滚且 Redis 所有权仍能精确证明。

### 5. 旧消息与当前预占对不上

假设用户 42 当前 reservation 指向订单 570...，Broker 又投递一条同用户同券但订单号为 569...、状态仍是 `PROCESSING` 的旧消息。Consumer 的校验 Lua 会因为状态或 reservation 不匹配而拒绝访问 MySQL，并让消息重试；它不会只看旧 Set 就创建订单。如果旧消息对应的是归属匹配的 `SUCCESS` 或 `FAILED`，则在终态判断处直接确认，不再检查 reservation。

另一个情形是消息已经通过 Redis 校验，进入 MySQL 插入后才发现当前 `orderId` 属于另一用户或券。这时系统暂停活动、隔离这个订单号并保留 reservation/库存现场，绝不自动补偿。它与前面的“Redis 校验失败、不访问 MySQL”是两条分支，不能连成一次执行。

## 七、Redis 全量丢失时，实际只能恢复到哪里

Redis 全量丢失后，非 test 启动初始化器可以从 `tb_seckill_voucher` 恢复当前数据库库存以及缺失的活动开始、结束时间，并把缺失状态写成 `ACTIVE`。已知订单号的用户查询也可以从 MySQL 返回正式订单成功。

但 MySQL 中没有完整保存 Redis 协议现场，因此自动恢复不了旧已购 Set、`userId -> orderId` reservation、每单状态 Hash、待检查 ZSET、隔离区及原因，也恢复不了丢失前的 `SUSPENDED` 证据。PROCESSING 启动回填只能扫描仍存在的状态 Hash；全量丢失时没有可扫描内容。历史成功用户可能再次通过 Redis 准入，最后仍由 MySQL 用户—券唯一约束拒绝并触发暂停、精确补偿；已提交但 Redis 证据全失的旧消息则会在 Consumer 校验处因状态缺失而持续重试，而不会绕过门禁直接查 MySQL。

所以当前能力是“数据库仍保住正式订单和当前数据库库存，已知订单号还能查询；新的准入基础数据可以重建”，不是无损恢复全部进行中订单与恢复协议。上线时不能把 reservation、PROCESSING 或 quarantine 当普通缓存随意清空。

## 八、原 `10-seckill.md` 中影响理解的关键错误与遗漏

- 原稿把旧已购 Set 说成可以忽略。当前 `seckill_check.lua` 仍先读它、准入成功仍写它，补偿仍删除成员；它承担旧数据兼容，但不能替代 exact reservation。
- 原稿把本地事务回调 `UNKNOWN` 和事务发送调用异常混在一起。前者明确由 Broker 后续回查；后者连 Broker 是否可靠收到 half message 都不能由调用方确定，只能先看本次 exact 状态再决定返回订单号还是 503。
- 原稿没有收紧“返回订单号”的含义。同步请求此时没有等待 MySQL；异常恢复分支甚至只检查状态 Hash，不检查 reservation，因此订单号只是查询关联号。
- 原稿把同用户同券冲突后的处理概括成“后面可能暂停、补 Redis、通知失败”。当前 Consumer 的明确顺序是暂停活动后立即 exact compensate，成功即确认消息；Reconciler 遇到同类冲突则受 `compensation-enabled` 控制。两者不能混成同一个补偿任务。
- 原稿没有完整分开三种数据库唯一性结果：同一 `orderId/userId/voucherId` 是幂等重放；同用户同券已有另一订单是可在精确门禁下处理的多余预占；当前订单号属于其他用户或券是所有权冲突，只能暂停并隔离，不能补偿。
- 原稿虽写了“插订单、扣库存”，但没有明确二者属于同一个 MySQL 事务，也没有说明库存条件更新失败会回滚刚插入的订单；MySQL 一旦提交，后续 Redis 标成功失败就只能重试修复，不能退库存。
- 原稿没有讲用户查询的真实分支：Redis owner 冲突时不查 MySQL，只有 `PROCESSING` 才用 exact MySQL 订单修复；Redis 缺失时虽可回库返回成功，却不重建协议状态；数据库查询异常当前是 500，而不是“暂时不存在”。
- 原稿把恢复重点放在 Broker 回查，遗漏了 MQ 消费重投、默认关闭的定时 Reconciler、独立补偿开关和一次性启动索引回填各自的触发条件与能力边界。
- 原稿没有说明 quarantine 与三种业务状态的关系。隔离不等于 `FAILED`：它移出自动对账索引、保留原状态和预占，并禁止 Broker、Consumer、补偿继续做危险动作。
- 原稿没有交代 Redis 全量丢失的上限。活动库存和元数据可从 MySQL 补缺，已知正式订单可查询；精确预约、过程状态、隔离与暂停证据无法完整重建。

## 九、从笔记反查当前源码

| 要确认的判断 | 源码入口 |
| --- | --- |
| HTTP 入口、可信 IP、三维限流 | [VoucherOrderController](../../../src/main/java/com/localdeals/controller/VoucherOrderController.java)、[TrustedClientIpResolver](../../../src/main/java/com/localdeals/service/TrustedClientIpResolver.java)、[SeckillTrafficGuard](../../../src/main/java/com/localdeals/service/SeckillTrafficGuard.java)、[限流 Lua](../../../src/main/resources/lua/seckill_traffic_guard.lua) |
| 订单号、事务消息、Broker 回查 | [RedisIdWorker](../../../src/main/java/com/localdeals/utils/RedisIdWorker.java)、[SeckillOrderProducer](../../../src/main/java/com/localdeals/mq/SeckillOrderProducer.java) |
| 事务消息中 Broker 与生产者的调用顺序 | [RocketMQ 官方事务消息流程](https://rocketmq.apache.org/docs/4.x/producer/06message5/) |
| Redis 准入、消费校验、成功与补偿 | [准入 Lua](../../../src/main/resources/lua/seckill_check.lua)、[SeckillOrderStateService](../../../src/main/java/com/localdeals/service/SeckillOrderStateService.java)、[消费校验 Lua](../../../src/main/resources/lua/seckill_validate_reservation.lua)、[成功 Lua](../../../src/main/resources/lua/seckill_mark_success.lua)、[补偿 Lua](../../../src/main/resources/lua/seckill_compensate.lua) |
| Consumer、MySQL 事务与三类冲突 | [SeckillOrderConsumer](../../../src/main/java/com/localdeals/mq/SeckillOrderConsumer.java)、[VoucherOrderServiceImpl](../../../src/main/java/com/localdeals/service/impl/VoucherOrderServiceImpl.java)、[V1 表结构](../../../src/main/resources/db/migration/V1__baseline_schema.sql)、[V2 唯一约束](../../../src/main/resources/db/migration/V2__voucher_order_constraints.sql) |
| 定时对账、隔离与启动回填 | [SeckillOrderReconciler](../../../src/main/java/com/localdeals/service/SeckillOrderReconciler.java)、[due Lua](../../../src/main/resources/lua/seckill_reconcile_due.lua)、[claim Lua](../../../src/main/resources/lua/seckill_reconcile_claim.lua)、[隔离 Lua](../../../src/main/resources/lua/seckill_reconcile_quarantine.lua)、[SeckillProcessingIndexBackfillRunner](../../../src/main/java/com/localdeals/init/SeckillProcessingIndexBackfillRunner.java)、[PROCESSING 回填 Lua](../../../src/main/resources/lua/seckill_reconcile_backfill.lua) |
| 活动创建、预热和启动初始化 | [AdminCatalogService](../../../src/main/java/com/localdeals/service/AdminCatalogService.java)、[VoucherServiceImpl](../../../src/main/java/com/localdeals/service/impl/VoucherServiceImpl.java)、[SeckillVoucherRedisInitializer](../../../src/main/java/com/localdeals/init/SeckillVoucherRedisInitializer.java)、[活动回填 Lua](../../../src/main/resources/lua/seckill_voucher_backfill.lua) |
| 默认开关与 TTL/key 常量 | [SeckillProperties](../../../src/main/java/com/localdeals/config/SeckillProperties.java)、[application.yaml](../../../src/main/resources/application.yaml)、[RedisConstants](../../../src/main/java/com/localdeals/utils/RedisConstants.java) |

这份笔记以当前 `codex/platform-hardening` 分支源码、配置、V1/V2 迁移、相关 Lua 和测试分支的静态阅读为依据。本次没有启动服务、运行迁移、MQ/Redis/MySQL 业务测试或故障实验；源码中仍保留的 `seckill.lua`、Redis Stream 配置和 `RedisLuaScript.SECKILL_SCRIPT` 没有被当前 HTTP 秒杀链路调用，不能描述成与 RocketMQ Consumer 同时运行的现行方案。
