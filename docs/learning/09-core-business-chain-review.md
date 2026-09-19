# 09. 三条核心业务面试讲述稿

> 本文是秒杀、点赞与热榜、营销发券三条业务的唯一完整面试版本。后台身份、基于角色的访问控制（RBAC）、商户隔离与实时会话同样属于核心复杂链路，统一放在 [08. 补充模块面试讲述手册](08-interview-playbook.md)。[03](03-seckill-order-chain.md)、[04](04-blog-like-hot-rank-chain.md)、[05](05-marketing-grant-chain.md) 保留源码级底稿，不再与本文各自维护一套重复口述。

## 0. 使用方式、事实依据与术语

### 0.1 三层阅读法

1. 面试开场只使用“模块定位”和“30～60 秒概括版”。
2. 追问设计时使用“2～3 分钟完整口述版”和“核心机制与设计取舍”。
3. 排查或复习源码时，从“端到端主链路”进入异常表、状态与源码索引。

本文件中的正常链路只保留发生顺序；返回码、门禁和失败分类集中在各模块后半部分。事实优先级始终是当前源码、配置和数据库迁移，其次是测试所覆盖的局部行为，最后才是旧笔记与设计材料。

### 0.2 术语

| 术语 | 本文含义 |
| --- | --- |
| 幂等 | 同一业务动作重复执行，结果仍收敛到同一份事实，不重复产生副作用 |
| half message | RocketMQ 事务消息中暂不对消费者可见的半消息，等待本地事务结果后再提交或回滚 |
| Outbox | 与业务事实同一数据库事务写入的持久待办，由后台任务稍后处理 |
| generation fence | 代际栅栏；只允许当前一代构建结果发布，拒绝旧任务晚到覆盖 |
| top-K | 只保存排序最靠前的 K 个对象，而不是完整业务集合 |
| REQUIRES_NEW | Spring 事务传播方式；暂停外层事务并为当前调用开启独立事务 |
| TTL | Time To Live，数据的剩余存活时间 |
| MQ / DLQ / ACK | MQ 是消息队列；DLQ 是死信队列；ACK 表示消费者确认本次消息处理结束 |
| best-effort | 尽力执行但不承诺最终完成；失败不能反向改写已经成立的业务事实 |
| reservation / PROCESSING | reservation 是秒杀用户到 exact orderId 的精确预约；PROCESSING 是 Redis 已接受预约但尚未确认 Redis 终态 |
| Grant | 用户已经取得某个活动券权益的 MySQL 长期事实 |
| ZSET | Redis Sorted Set，按 score 排序的集合 |
| marker | 完成标记；用于证明一条待办或一次切换已经在数据库中确认 |
| exactly-once | 副作用恰好执行一次；仅有消息重试或幂等设计不能直接证明这一语义 |
| P99 / SLA | P99 是 99% 请求不超过的延迟分位；SLA 是对服务水平的约定 |
| Broker / Consumer | Broker 是保存并投递消息的服务端；Consumer 是接收消息并执行后续业务的消费者 |
| Worker / Reconciler | Worker 处理持久待办；Reconciler 按事实重新检查长期未决状态 |
| SingleFlight / bulkhead | SingleFlight 合并单实例同 key 的重复工作；bulkhead 是限制一类依赖并发的并发舱 |

### 0.3 三条链的共同判断顺序

~~~text
确定不可丢的长期事实
        ↓
定义重复请求和重复消息怎样收敛
        ↓
为跨组件步骤留下协议状态或持久待办
        ↓
异常时按事实分类，再决定重试、补偿或隔离
~~~

“抛异常”只表示调用方没有得到确定结果，不等于远端没有执行。补偿也不是失败的默认动作；只有能证明副作用仍归当前操作所有，并确认长期事实没有成立时才允许补偿。

---

## 1. 三种一致性方案为什么不同

| 业务 | 最先成立的关键事实 | 怎样留下后续工作 | 重复怎样收敛 | 异常时先查什么 | 当前主要边界 |
| --- | --- | --- | --- | --- | --- |
| 秒杀 | Redis 精确预约先成立，MySQL 正式订单后成立 | 事务消息、PROCESSING 与待检查索引 | 精确预约、消息校验、数据库唯一约束和状态迁移 | orderId 对应的 MySQL 订单，再核对 Redis 所有权 | 对账默认关闭；Redis 协议证据无法从数据库完整重建 |
| 点赞与热榜 | MySQL 用户—Blog 关系 | 同事务点赞 Outbox | 目标状态、关系唯一键、事件行锁与精确 marker | 点赞关系、聚合字段和待处理事件 | 聚合会滞后；永久坏事件无自动隔离 |
| 营销发券 | MySQL Grant 权益 | 新建 Grant 时同事务写通知 Outbox；批量另有 Job 与 Item | 服务端幂等身份、活动锁、条件更新和唯一约束 | Grant，其次才是 Item 与通知状态 | worker 默认关闭；历史 Grant 无通知回填，通知也无送达或已读保证 |

方案不同是因为事实落点不同。秒杀要先在 Redis 快速准入，因此需要事务消息和跨组件状态；点赞关系与发券权益原本就在 MySQL，关系或 Grant 与 Outbox 同事务更直接。任何方案都不能因为使用了锁、MQ 或 Lua 就被描述成“绝对一致”。

---

## 2. 秒杀：同步准入，异步落单，按事实收敛

### 2.1 模块定位

秒杀链路解决突发流量下的限量抢购，并把低延迟资格判断与数据库正式落单拆开。最值得讲的设计是：每份资格精确归属于一个 orderId，失败后也只有在所有权和数据库事实都明确时才恢复快速库存。

### 2.2 30～60 秒面试概括版

> 秒杀同时面对突发流量、重复请求，以及 Redis 预占后如何形成 MySQL 正式订单的问题。我把它拆成同步准入和异步落单两段：入口先做活动、用户和可信来源的流量保护，通过后生成贯穿全链路的订单号；准入脚本原子检查活动、库存和重复购买，并留下精确预约与 PROCESSING，只有成功后事务消息才可消费。消费端再次核对预约，再由 MySQL 唯一约束和条件扣库存最终仲裁。数据库提交后才标 SUCCESS。网络或数据库结果不确定时不立即恢复库存；只有能证明订单未成立且预约仍属当前订单时才补偿，所有权冲突则暂停并隔离。

### 2.3 2～3 分钟完整口述版

> 秒杀同时有突发流量、重复请求和跨存储一致性三个难点。Redis 适合快速挡流量和预占资格，但正式订单与数据库库存最终还要落到 MySQL。最危险的现场不是明确“库存不足”，而是系统在 Redis、Broker 和数据库之间中断后，不知道请求到底执行到哪一步。
>
> 我的方案分成入口保护、资格预占和正式落单三层。入口按活动、用户和可信 IP 做固定窗口保护。通过后才生成 orderId，它会贯穿 HTTP 响应、Redis 状态、MQ 消息和 MySQL 行。生产者先发 half message，本地事务回调再执行 Redis 准入脚本；脚本在第一次写操作前检查活动、时间、库存和重复购买，通过后一次性扣快速库存、写精确预约、PROCESSING 与待检查索引。
>
> 消费端不会看到消息就直接扣数据库库存。它先取得用户维度的锁，再检查状态中的用户、券、订单号和预约完全匹配。MySQL 事务先插订单，再执行 stock 大于零的条件更新。先插订单让重复消息在再次扣库存前被主键或一人一券约束识别；用户锁只减少竞争，数据库约束才是最终边界。
>
> 事务消息缩小了“Redis 已预占但普通消息丢失”的窗口，却没有把 Redis、MQ 和 MySQL 变成一个全局事务。所以 Broker 仍会回查，Consumer 仍会重试，状态查询和可选 Reconciler 也要按事实修复。PROCESSING 只表示 Redis 已接受预约但尚未确认 Redis 终态；此时 MySQL 可能还没有订单，也可能订单已经提交而 markSuccess 失败。
>
> 明确库存不足或用户—券冲突时，数据库事务已经回滚，Consumer 会先暂停新准入，再按精确预约立即补偿。orderId 已属于其他订单则是所有权冲突，只能隔离，不能自动补偿。数据库查询失败、响应超时或状态缺失都不等于订单不存在。当前定时对账、对账自动补偿和启动回填默认关闭，Redis 全量丢失也无法从 MySQL 重建全部预约与隔离证据，所以我会把它描述为“有受控恢复路径”，而不是自动最终收敛。

### 2.4 端到端主链路

1. 接口接收 voucherId；用户身份来自消费者会话，客户端 IP 按可信代理规则解析。
2. 流量保护同时检查活动、用户和 IP 三类固定窗口；全部通过后才继续。
3. 系统预先生成 orderId。它只是跨阶段关联标识，此时还没有数据库订单。
4. 生产者把携带 userId、voucherId、orderId 的 half message 交给 Broker。
5. 本地事务执行 Redis 准入脚本，校验活动元数据、时间、快速库存和重复购买。
6. 准入成功后，脚本原子扣快速库存，写精确预约、PROCESSING 状态与待检查索引；Broker 随后提交消息。
7. Consumer 取得用户锁，并再次确认隔离状态、三个归属 ID 和精确预约。
8. MySQL 事务先插正式订单，再以 stock 大于零为条件扣减数据库库存。
9. 数据库提交后，成功脚本把 exact PROCESSING 迁移为 SUCCESS，移出待检查索引并保留预约证据。
10. WebSocket 只尽力提示客户端刷新；客户端可使用 orderId 查询当前结果。

### 2.5 核心机制与设计取舍

#### 机制一：三维入口流量保护

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 在订单号、消息和库存预占之前限制热点活动及异常重试 |
| 防止什么竞态或故障 | 多实例采用不同本机时间；一个维度已经计数、另一维度才发现超限 |
| 为什么更直接的方案不够 | 只限活动会让单用户或单来源反复占用入口；应用本地计数无法在多实例间共享 |
| 本身不能解决什么 | 固定窗口边界仍可能突发；阈值不是容量证明，也不负责一人一券 |

脚本使用 Redis TIME 作为共同时间基准。活动、用户和 IP 三个计数只有在全部通过时才一起增加；被拒请求不改变任何一个计数。IP 以摘要进入 key，只有直连来源属于可信代理时才采信 X-Real-IP 或 X-Forwarded-For。无法解析的来源会落到同一个 unknown 桶；代理链的头部清洗仍是部署责任。

旧窗口 key 的 TTL 是两倍窗口再加一秒，只提供清理余量，不会把固定窗口变成滑动窗口。请求一旦通过流量保护，后续准入或 MQ 失败也不会退还计数，因为该计数描述入口请求量，不是库存或用户权益。

#### 机制二：提前生成业务订单号

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 在异步落库前就为本次受理建立稳定关联号 |
| 防止什么竞态或故障 | HTTP 已返回、消息和 Redis 状态却无法与后续数据库行关联 |
| 为什么更直接的方案不够 | 等数据库自增 ID 会让同步响应依赖异步落库完成 |
| 本身不能解决什么 | ID 只证明一次请求有标识，不证明订单已经成立；生成过程仍依赖 Redis 日序列 |

orderId 由应用时间戳和 Redis 日序列组成。HTTP 返回十进制字符串，避免 JavaScript 对 64 位整数的精度损失。客户端由此获得可查询关联号；尤其在生产异常恢复分支中，只看到状态 Hash 不能一概证明 reservation 完整、Broker 已提交或 MySQL 已落单。

#### 机制三：事务消息与精确预约

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 缩小 Redis 已接受资格、普通消息尚未可靠交给 Broker 的异常窗口 |
| 防止什么竞态或故障 | 应用在预占与发送之间退出；旧消息借用“用户买过”记录写入另一订单号 |
| 为什么更直接的方案不够 | Redis 扣库存后发送普通消息没有 Broker 回查依据；已购 Set 只证明历史，不能证明当前 orderId 所有权 |
| 本身不能解决什么 | 它不是 Redis、MQ、MySQL 的全局事务，也无法在 Redis 全量丢失后恢复预约 |

准入脚本维护快速库存、legacy 已购 Set、活动 metadata、userId 到 exact orderId 的 reservation、包含三个归属 ID 的状态 Hash，以及按下次检查时间排序的 PROCESSING ZSET。不同对象分别解决入口容量、历史兼容、活动规则、消息所有权、过程判断与扫描索引，不能合并解释成一份缓存。

#### 机制四：数据库事务与最终约束

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 形成正式订单，并对重复与库存做最终仲裁 |
| 防止什么竞态或故障 | MQ 重投再次扣库存；Redis 快速库存与数据库库存不一致；同用户同券并发落单 |
| 为什么更直接的方案不够 | 只靠用户分布式锁不能覆盖重启、锁服务异常、消息重投或其他写入口 |
| 本身不能解决什么 | MySQL 提交后不能自动更新 Redis 状态或发送 WebSocket |

先插订单再条件扣库存，使主键或用户—券唯一冲突在库存更新前暴露。库存更新要求 stock 大于零；失败会让订单插入随同一事务回滚。DuplicateKey 之后仍需读取现有行，区分 exact replay、用户—券冲突和 orderId 归属冲突。

#### 机制五：按证据对账、精确补偿与隔离

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 让长期 PROCESSING 根据数据库事实继续推进，同时防止错误回库存 |
| 防止什么竞态或故障 | 数据库已提交却因 Redis 未标成功而补偿；重复补偿多加库存；对账与 Consumer 同时处理一名用户 |
| 为什么更直接的方案不够 | “超时就失败”把未知当永久失败；只看状态而不看 reservation 无法确认被补偿资源属于谁 |
| 本身不能解决什么 | 对账不重发 MQ；默认关闭；所有权冲突需要隔离或人工判断；Redis 全量丢失后证据不足 |

Consumer 与 Reconciler 共用用户维度锁。补偿要求未隔离、reservation 仍指向当前 orderId、状态仍为三个 ID 完全匹配的 PROCESSING。orderId 归属冲突进入 quarantine；quarantine 是隔离区，不是新的业务终态。

### 2.6 异常分类与状态收敛

| 异常现场 | 当前能够确认的事实 | 处理方式 | 是否重试 | 是否允许补偿 |
| --- | --- | --- | --- | --- |
| 流量维度超限 | 只知道入口预算已满，尚未生成 orderId | 返回 429 | 窗口后可重试 | 否 |
| 准入明确库存不足、重复、未开始或非 ACTIVE | 守卫在首次写操作前拒绝，本次没有预占 | 回滚 half message 并返回业务拒绝 | 条件变化前通常无意义 | 否 |
| 准入元数据或参数非法 | 无法安全执行准入，且脚本未写入 | 回滚 half message，当前返回 503 | 修复数据后 | 否 |
| 本地事务回调中的 Lua 抛错、返回空值或未知值 | 回调无法确认准入结果 | Producer 向 Broker 返回 UNKNOWN，等待事务回查 | 是 | 否 |
| sendMessageInTransaction 调用抛错或 Producer 结果非法 | Broker 可能尚未收到 half message，也可能已收到但调用方没有拿到可信结果 | HTTP 只按 exact 状态 Hash 尝试恢复，无法恢复时返回 503；不假定 Broker 已进入 UNKNOWN | 客户端可按业务语义重试或查询 | 否 |
| Broker 回查发现 reservation 缺失、不匹配、隔离、归属冲突或 FAILED | 消息没有当前提交资格 | ROLLBACK | 通常否 | 否 |
| Broker 回查状态缺失、status 缺失、未知或 Redis 异常 | 暂时不能确认本地事务结果 | 返回 UNKNOWN | 是 | 否 |
| Consumer 消息缺字段、用户锁忙或数据库暂时异常 | 没有确定的永久失败事实 | 抛错交给 MQ 重投 | 是 | 否 |
| 消费校验状态缺失、字段不完整或未知 | 所有权证据暂时不足 | 保留现场并重投 | 是 | 否 |
| 消费校验完整归属不匹配或已隔离 | 当前消息是 POISONED，不具备 exact 所有权 | Consumer 抛错，进入 retry / DLQ；本身不新建隔离 | 是 | 否 |
| 已是同一归属的 SUCCESS 或 FAILED | 已有终态 | 幂等 ACK，不访问 MySQL | 否 | 否 |
| MySQL exact order 已存在 | 同一消息重放，正式订单已成立 | 不再扣库存，继续尝试 markSuccess | 状态未收敛时是 | 否 |
| 数据库库存耗尽 | 本次订单插入已回滚，是确认的永久失败 | 暂停活动并立即 exact compensate | 补偿失败时重试 | 是 |
| Consumer 的 MySQL 事务确认用户—券已有另一 orderId | 本次插入已回滚，数据库已有冲突订单 | 暂停活动并立即 exact compensate | 补偿失败时重试 | 是 |
| Reconciler 查询到 USER_VOUCHER_CONFLICT | 数据库已有另一 orderId，但当前只是对账分支 | 暂停活动；仅在对账补偿开关开启时 exact compensate，否则保留 PROCESSING | 按对账调度 | 有条件 |
| 当前 orderId 已属于其他用户或券 | Redis 与数据库所有权冲突 | 暂停活动并 quarantine；继续 retry / DLQ | 是 | **否** |
| MySQL 已提交，markSuccess 失败 | 正式订单已成立，Redis 终态未确认 | MQ 重放、状态查询或启用后的对账再修复 | 是 | **否** |
| 长期 PROCESSING，数据库查询失败 | 不能证明订单不存在 | 保留并延后检查 | 是 | 否 |
| 长期 PROCESSING，数据库明确无订单 | 已有缺单证据，但仍需期限和开关判断 | 未到最终期限继续等；超时且开关开启才补偿 | 按配置 | 有条件 |
| 合法 orderId 暂时无法解析可信 userId | 无法取得与 Consumer 相同的用户锁 | 延后检查，不在锁外猜测性隔离 | 是 | 否 |
| 新秒杀券的 MySQL 事务已提交，但 after-commit Redis 预热失败 | 券与数据库库存已成立，在线准入元数据尚未就绪 | 只记录错误；等待非 test 启动初始化器或人工修复 | 当前路径不自动重试 | 不得回滚已提交的券 |
| WebSocket 通知失败 | 订单终态不受影响 | 记录日志，客户端查询状态 | 当前无持久补发 | 否 |

不能立即补偿的根本原因是：超时、网络异常或数据库异常只说明“现在不知道”，不说明订单不存在。MySQL 可能已经提交；此时恢复快速库存会多放进一个买家。安全补偿至少要求 exact reservation 和状态所有权仍成立、订单未隔离，并且写库侧已确认本次事务回滚或数据库长期事实不存在。

准入的 1～5 都在第一次写操作前返回，因此本次不需要恢复库存；这个结论不能套到 Broker transaction check 的 ROLLBACK。后者还可能意味着预约已经被补偿、已隔离或所有权已经冲突。

### 2.7 状态、事实和不变量

最终业务事实位于 MySQL：

- tb_voucher_order 保存正式订单；
- tb_seckill_voucher.stock 保存数据库库存；
- V2 的用户—券唯一约束和订单主键是最终防重边界。

Redis 保存准入和恢复协议：

- 快速库存、活动元数据与 SUSPENDED 状态；
- legacy 已购 Set、精确 reservation；
- 订单状态 Hash、PROCESSING due ZSET；
- quarantine 成员与原因。

~~~text
Redis admission 成功
        ↓
    PROCESSING
      ├─ MySQL exact order 已提交
      │      └─ exact markSuccess → SUCCESS
      ├─ Consumer 确认库存或用户—券永久失败
      │      └─ suspend + exact compensate → FAILED
      ├─ 对账确认长期无订单且授权补偿
      │      └─ exact compensate → FAILED
      ├─ orderId 归属冲突
      │      └─ suspend + quarantine，状态不迁移
      └─ 依赖异常或证据暂缺
             └─ 保留 PROCESSING
~~~

PROCESSING 的准确含义是“Redis 已接受预约，但 Redis 尚未确认成功或失败终态”。数据库订单可能还不存在，也可能已提交而 markSuccess 失败。它不是未支付状态。MySQL 订单表自身的 status 字段描述支付或使用生命周期，也不是这张状态机的一部分。

quarantine 不属于 PROCESSING、SUCCESS、FAILED 状态机。隔离会移出 due 索引并记录原因，但不改业务 status、不删 reservation、不恢复库存；事务回查、消费与补偿都会据此停止危险动作。due ZSET 的 score 是下一次检查时间，不是支付截止时间。

状态查询的实际顺序也要准确描述：

1. Redis 有完整 owner 信息且不是 PROCESSING 时，接口直接按 Redis 状态返回。
2. Redis owner 与当前用户不匹配时，拒绝访问，不再用数据库探测。
3. Redis 是 PROCESSING 时，才查询当前用户拥有的 exact MySQL 订单，并尽力修复成功状态。
4. Redis 缺失或异常时，按 orderId 与当前 userId 回查数据库；即使返回成功，也不会凭空重建缺失的 reservation。

当前状态读取还有一个明确校验边界：SeckillOrderStateService.find() 只要求 status、userId、voucherId、orderId 四个字段非空。它没有再次确认 Hash 内的 orderId 等于当前 key 对应的请求 orderId，也没有把 status 限定在已知枚举内。因此，一个字段完整但 status 非 PROCESSING 的异常 Hash 可能在不查 MySQL 的情况下直接返回；这属于当前实现缺口，不能描述成状态记录已经过完整协议校验。

PROCESSING 状态被 PERSIST，不设短 TTL；SUCCESS 和 FAILED 设置 7 天 TTL。成功后 reservation 仍保留为所有权证据。

系统必须长期守住：

1. 同一用户对同一张秒杀券最多一笔 MySQL 订单。
2. 数据库库存只能在大于零时扣减，不能减成负数。
3. 只有 exact PROCESSING 加精确预约匹配的消息才能尝试写库。
4. 只有预约仍属当前订单且数据库确认未成立时才能恢复快速库存。
5. 隔离订单禁止自动补偿。
6. MySQL 已提交后，Redis 或 WebSocket 失败不能反向撤销正式订单。

Redis 全量丢失是当前明确边界。启动初始化可以从数据库重建活动元数据和快速库存，并为缺失活动写 ACTIVE；它不能重建 reservation、已购 Set、订单状态、due、quarantine 和原 SUSPENDED 证据。启动回填也只能扫描仍存在的状态 Hash。当前没有自动恢复 SUSPENDED → ACTIVE 的业务流程。非 test 环境的活动初始化器会在数据库行非法、数据库读取失败或 Redis 回填失败时阻止启动；PROCESSING 启动回填遇到无法证明安全归属的 canonical 状态，也会汇总为 unsafe 并使启动失败。

### 2.8 面试官最可能追问的 5 个问题

1. **为什么不用 Redis 扣库存后直接发普通消息？**
   - 两步之间存在进程退出窗口。事务消息让 Broker 先保存 half message，再按 Redis 准入结果提交、回滚或回查；MySQL 仍需要消费幂等和最终约束。

2. **为什么 orderId 要提前生成，还要保存精确预约？**
   - 提前 ID 让 HTTP、Redis、消息和数据库共用关联号；精确预约证明当前消息拥有这个 ID。已购 Set 只能证明历史，不能证明当前消息归属。

3. **用户锁、Redis 库存和数据库约束分别做什么？**
   - 用户锁减少 Consumer 与 Reconciler 的竞争；Redis 快速库存承接入口预占；数据库唯一约束和条件更新是最终仲裁。三者不能互相替代。

4. **数据库已提交，但 Redis 仍是 PROCESSING 怎么办？**
   - 不能补偿。MQ 重投会识别 exact replay 并再次标成功；状态查询和启用后的对账也会尝试修复。正式订单仍以 MySQL 为准。

5. **Redis 全量丢失能自动恢复吗？**
   - 不能。数据库可帮助恢复库存和活动基础信息，却没有精确预约、隔离和暂停证据；当前源码没有完整的无损重建闭环。

### 2.9 源码、协议、配置与验证边界

#### HTTP 与查询语义

| 结果 | 当前接口语义 |
| --- | --- |
| 未登录 | HTTP 401 |
| 三维入口流量超限 | HTTP 429 |
| 库存不足、重复、未开始、结束或暂停 | HTTP 200 中的业务失败结果 |
| 元数据、ID、Redis 或 MQ 不可用且无法恢复 | HTTP 503 |
| 返回 orderId | 客户端获得可查询关联号；不能一概证明 reservation 完整、Broker 已提交或 MySQL 已落单 |
| 状态查询失败 | 通常由统一 Result.fail 表达，不等同于提交接口的 HTTP 错误映射 |

#### 关键协议返回

| 协议 | 返回或决策 | 含义 |
| --- | --- | --- |
| TrafficGuard | 0 / 1 / 2 / 3 | 通过 / 活动超限 / 用户超限 / IP 超限；空、异常或其他值失败关闭 |
| admission | 0 / 1 / 2 / 3 / 4 / 5 | 接受 / 库存不足 / 重复 / 未开始 / 结束或非 ACTIVE / 元数据或参数非法 |
| Consumer reservation | 0 / 1 / 2 / 3 / 4 | 状态暂缺 / 可处理 / 已成功 / 已失败 / POISONED |
| compensation | 1 / 2 / 0 | 本次补偿 / 已是 exact FAILED 的幂等成功 / 所有权或状态不允许 |

Broker transaction check 的核心决策：

| 现场 | Broker 决策 |
| --- | --- |
| 消息非法、已隔离、reservation 缺失或不匹配、状态 owner 不匹配、exact FAILED | ROLLBACK |
| exact PROCESSING 或 SUCCESS | COMMIT |
| 状态缺失、status 缺失、未知状态或 Redis 异常 | UNKNOWN |

HTTP 在事务发送异常后的恢复只核对状态 Hash 中的三个 ID 与 PROCESSING 或 SUCCESS，不验证 reservation；Broker 回查才执行完整预约检查。

启用 Reconciler 后，它从 due ZSET 有界读取成员而不先删除。claim 脚本的决策集中如下：

| claim 值 | 含义与动作 |
| --- | --- |
| 1 CLAIMED | 增加尝试信息、后移 due score，再查询 writer MySQL |
| 2 NOT_DUE | 尚未到检查时间，本轮跳过 |
| 3 TERMINAL | 已是终态，清理或跳过 |
| 4 / 5 / 6 | 所有权不匹配、状态非法或预约不匹配；只在共享用户锁内确认后隔离 |
| 7 INDEX_MISSING | 跳过，不凭空重建索引 |
| 8 QUARANTINED | 跳过已隔离订单 |

扫描阶段遇到无法解析的非法原始成员可以隔离；合法 orderId 但暂时无法解析可信 userId 时，只能延后检查，因为此时无法取得与 Consumer 相同的用户锁。

CLAIMED 后的数据库分类决定真正收敛动作：

| writer MySQL 事实 | 对账动作 |
| --- | --- |
| exact order | 再次尝试 markSuccess |
| 明确 ABSENT，尚未到 final timeout | 保持 PROCESSING，等待下轮 |
| 明确 ABSENT，已超时但补偿关闭 | 保持 PROCESSING |
| 明确 ABSENT，已超时且补偿开启 | exact compensate 为 FAILED |
| 同用户—券属于另一 orderId | 暂停活动；只有补偿开关开启才补偿，否则保留 |
| 当前 orderId 属于其他订单 | 始终暂停并 quarantine，不补偿 |
| 数据库查询异常 | 保持 PROCESSING，禁止解释为 ABSENT |

#### 默认配置

| 配置 | 默认值 |
| --- | --- |
| 固定窗口 | 1 秒 |
| 活动 / 用户 / IP 限额 | 300 / 2 / 100 |
| reconciler / 对账补偿 / 启动回填 | false / false / false |
| initial delay / fixed delay | 30 秒 / 10 秒 |
| stale after / retry delay / final timeout | 2 分钟 / 1 分钟 / 15 分钟 |
| batch size / backfill scan count | 100 / 500 |

启动回填与在线 Reconciler 不能在同一进程同时开启。Consumer 对明确库存耗尽和用户—券冲突的即时补偿不受“对账补偿”开关控制。当前 RocketMQ 是正式消费路径；配置对象中仍保留 legacy Redis Stream 字段，但活跃业务代码没有引用，不能说成同时运行两套订单消费链。consumeFromWhere 的 FIRST_OFFSET 只影响没有消费位点的新组初始化，不表示每次重启都重放全部消息。

新建秒杀券时，MySQL 主记录和库存记录先在一个事务中提交；提交后的 Redis 预热只是尽力执行。预热失败只记录日志，接口仍可能已返回创建成功，此时活动会处于“数据库已创建、在线准入元数据尚未就绪”的状态，直到进程重启时初始化器重新回填或人工修复。该 after-commit 路径不是持久待办。

#### 源码索引

| 阶段 | 入口 |
| --- | --- |
| HTTP、可信 IP 与流控 | [VoucherOrderController](../../src/main/java/com/localdeals/controller/VoucherOrderController.java)、[SeckillTrafficGuard](../../src/main/java/com/localdeals/service/SeckillTrafficGuard.java)、[流控脚本](../../src/main/resources/lua/seckill_traffic_guard.lua) |
| ID 与生产 | [RedisIdWorker](../../src/main/java/com/localdeals/utils/RedisIdWorker.java)、[SeckillOrderProducer](../../src/main/java/com/localdeals/mq/SeckillOrderProducer.java)、[准入脚本](../../src/main/resources/lua/seckill_check.lua) |
| 消费与 MySQL | [SeckillOrderConsumer](../../src/main/java/com/localdeals/mq/SeckillOrderConsumer.java)、[VoucherOrderServiceImpl](../../src/main/java/com/localdeals/service/impl/VoucherOrderServiceImpl.java)、[V2 约束](../../src/main/resources/db/migration/V2__voucher_order_constraints.sql) |
| 状态、补偿与隔离 | [SeckillOrderStateService](../../src/main/java/com/localdeals/service/SeckillOrderStateService.java)、[成功脚本](../../src/main/resources/lua/seckill_mark_success.lua)、[补偿脚本](../../src/main/resources/lua/seckill_compensate.lua)、[隔离脚本](../../src/main/resources/lua/seckill_reconcile_quarantine.lua) |
| 活动创建与 Redis 预热 | [VoucherServiceImpl](../../src/main/java/com/localdeals/service/impl/VoucherServiceImpl.java)、[Redis 初始化器](../../src/main/java/com/localdeals/init/SeckillVoucherRedisInitializer.java) |
| 对账与启动恢复 | [SeckillOrderReconciler](../../src/main/java/com/localdeals/service/SeckillOrderReconciler.java)、[PROCESSING 回填](../../src/main/java/com/localdeals/init/SeckillProcessingIndexBackfillRunner.java) |
| 配置 | [SeckillProperties](../../src/main/java/com/localdeals/config/SeckillProperties.java)、[application.yaml](../../src/main/resources/application.yaml) |
| 详细底稿 | [03. 秒杀订单链路](03-seckill-order-chain.md) |

测试源码分别覆盖脚本协议、MySQL 回滚与冲突、部分真实 Redis 行为、生产者或 Consumer 生命周期。SeckillWithRocketMQIT 虽使用真实 Broker 和 Redis，但会替换订单服务与通知，不是 Redis → MQ → MySQL 的完整链路；SeckillOrderRetryIT 也没有证明最终进入 DLQ。本次文档重写不把这些局部测试扩大成生产消息 exactly-once、Redis 高可用、吞吐或恢复时限证明。

---

## 3. 点赞与热榜：关系是事实，聚合可追踪，榜单可重建

### 3.1 模块定位

这条链解决重复点赞、聚合计数异步更新和热榜快照并发发布三个问题。核心设计是把用户—Blog 关系、MySQL 聚合和 Redis top-K 明确分层：关系必须准确，聚合可以重放，榜单可以验证并重建。

### 3.2 30～60 秒面试概括版

> 点赞会遇到重复点击和网络重试，聚合计数又不适合拖慢每次关系写入；热榜重建时还可能出现半成品或旧任务晚到。我把关系、聚合和排行榜分成三层：用户与 Blog 的关系是 MySQL 事实，接口表达“最终已点赞或未点赞”，避免 toggle 重放反转；关系变化时，同事务写正一或负一 Outbox，worker 再批量更新聚合并精确标记事件。Redis 只保存可重建 top-K，读取前后校验代际、数量和时效，不可信就受并发舱保护回退 MySQL；重建先写临时榜，再由 generation fence 原子发布。事务失败可重试、坏榜可重建，但永久坏 Outbox 当前没有自动隔离或 DLQ。

### 3.3 2～3 分钟完整口述版

> 点赞按钮会被重复点击，客户端超时也会重试。如果接口是无条件 toggle，同一个请求执行两次会把结果翻回去；如果关系变化后再单独更新计数，应用可能在两步之间退出；如果重建热榜时直接覆盖正式 ZSET，读者还可能看到半成品或旧任务晚到的结果。
>
> 我的方案分成三层。tb_blog_like 保存谁赞了哪篇 Blog，是关系事实。tb_blog.liked 是用于排序的 MySQL 聚合，允许短暂滞后。Redis 热榜只保存有限 top-K，是可丢失的读模型。写接口使用 PUT 或 DELETE 表达目标状态；事务先锁定父 Blog，再插入或删除关系。只有关系恰好变化一行时，才在同一事务写一条增量 Outbox。
>
> 聚合 worker 先尝试 Redis 锁减少多个实例重复争抢。正常没抢到就跳过本轮；锁服务异常时仍进入数据库，由行锁和事务保证正确性。它按事件 ID 锁定有限批，校验每条增量只能是正一或负一，按 Blog ID 固定顺序聚合并做非负更新，最后精确标记本批原事件。聚合更新和 marker 同事务，所以中途失败会整批回滚。
>
> 热榜读取不能只看 ZSET 是否为空。元数据会记录 ready、generation、count、capacity 和 publishedAt。读前校验完整性和时效，取出成员后再核对代际与数量，避免切版时混读。合法空榜直接返回；非空榜只提供排序 ID，仍需在 DB_READ 并发舱中回 MySQL 补 Blog、作者和当前用户点赞状态。任何不可信状态都按 MISS 回退数据库。
>
> 重建时，builder 在查 MySQL 前先取得新 generation，写这一代的临时榜，校验数量后再通过 Lua 原子发布。临时 key 防半成品可见，generation 防旧 builder 晚到，跨实例锁只减少重复工作。当前点赞新写、聚合 worker、热榜读与刷新、旧数据回填都默认关闭；V8 切换还要求停写导入旧 Redis 身份并记录完成标记。关系可以从 MySQL 保住，但坏 Outbox 目前没有自动隔离，可能反复阻塞前部批次，这也是当前边界。

### 3.4 端到端主链路

1. 客户端提交“最终已点赞”或“最终未点赞”，而不是无条件翻转。
2. 服务从登录上下文取得 userId，并在事务中对目标 Blog 做共享锁存在性检查。
3. “已点赞”尝试插入关系；“未点赞”按双方 ID 删除关系。
4. 唯一键冲突或删除零行表示目标已满足；只有关系变化一行才继续。
5. 关系变化与一条正一或负一的点赞 Outbox 在同一事务提交。
6. worker 锁定有限批待处理事件，按 Blog ID 聚合净增量。
7. MySQL 按固定 Blog ID 顺序更新非负聚合，并精确标记原事件已处理；两者同事务提交。
8. 热榜 builder 从 MySQL 读取 top-K，写当前 generation 的临时榜。
9. 发布脚本校验 generation、capacity 和成员数量，再原子替换正式榜与元数据。
10. 读取侧校验完整快照；非空命中按顺序回库补全，不可信或映射不完整时回退 MySQL。

### 3.5 核心机制与设计取舍

#### 机制一：目标状态命令与关系唯一键

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 让重复点赞、取消和网络重放收敛到用户声明的最终状态 |
| 防止什么竞态或故障 | 同一 toggle 执行两次反转结果；同用户同 Blog 产生多条关系 |
| 为什么更直接的方案不够 | 直接给 liked 加一不知道是谁点过；toggle 无法区分首次请求和重试 |
| 本身不能解决什么 | 关系唯一键不维护聚合数，也不能让 Redis 热榜同步更新 |

父 Blog 的共享锁必须存活在外层事务中，用于缩小“确认 Blog 存在后又被并发删除”的窗口；数据库外键仍是最终关系完整性约束。正常写入影响行数只允许零或一。

#### 机制二：关系与增量 Outbox 同事务

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 在关系成立时持久留下聚合待办 |
| 防止什么竞态或故障 | 关系已经提交，应用在单独更新计数或 after-commit 之前退出 |
| 为什么更直接的方案不够 | 同步维护每个派生对象会放大写路径；普通回调没有可恢复待办 |
| 本身不能解决什么 | Outbox 只保证待办入库，不保证 worker 已开启或事件能被自动隔离 |

关系没有变化时不写 Outbox，避免重试产生虚假增量。关系变化但 Outbox 插入失败时，整个事务回滚。

#### 机制三：有限批、固定顺序与数据库最终锁

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 多个 worker 安全消费事件，并减少同一 Blog 的更新次数 |
| 防止什么竞态或故障 | 同一事件重复应用、聚合变负、不同事务反向加锁增加死锁概率 |
| 为什么更直接的方案不够 | Redis 分布式锁会失效或不可用，不能代替 FOR UPDATE 与同事务 marker |
| 本身不能解决什么 | 永久非法事件当前没有 DLQ 或隔离，可能让前部批次重复回滚 |

worker 在 READ COMMITTED 事务中按事件 ID 升序、LIMIT、FOR UPDATE 取得待办。每条 delta 必须是正一或负一；按 Blog ID 有序聚合。净增量为零时不更新 Blog，但仍精确标记所有原事件。非零更新必须恰好影响一行且结果非负，marker 行数也必须等于选中事件数。

Redis 锁正常返回“未抢到”时，本轮退出；获取锁抛异常时才降级到数据库行锁流程。二者语义不同。

#### 机制四：可验证读取与受控 MySQL 回退

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 区分合法空榜、尚未构建、损坏和切版中的快照 |
| 防止什么竞态或故障 | 把坏榜当空榜；把旧元数据和新成员拼成一页；缓存故障导致无界回库 |
| 为什么更直接的方案不够 | 仅判断 ZSET 存在或设置 TTL，无法证明 generation、数量和配置一致 |
| 本身不能解决什么 | 数据库回退仍有成本；本地并发舱不是集群总保护，也不让榜单强实时 |

元数据必须满足 ready、正 generation、非负 count、当前 capacity 和有效 publishedAt。ZSET 基数要与 count 一致且不超过 top-K；成员必须是合法 Blog ID。读完后再次检查 generation 与 count。合法 ready 且 count 为零可直接返回，不进入 DB_READ；非空命中与 MISS 回退都需要数据库许可。

MISS 或非空榜详情映射不完整时，当前请求直接返回 MySQL 的 liked 降序、id 降序结果。只有 refreshEnabled 开启才在当前 JVM 内异步 SingleFlight 触发重建；请求不会等待重建完成，真正构建仍需跨实例锁和 generation 仲裁。

#### 机制五：临时榜与 generation fence

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 只发布一份构建完整且属于当前代际的热榜 |
| 防止什么竞态或故障 | 读到构建一半的 ZSET；较慢旧 builder 覆盖更新结果 |
| 为什么更直接的方案不够 | 直接清空并重写正式榜会暴露半成品；只用锁无法防锁过期或旧任务晚到 |
| 本身不能解决什么 | 发布正确不代表 MySQL 聚合正确；Redis 仍可能丢失或陈旧 |

builder 在加载 MySQL 前推进 generation。候选按 MySQL 的 liked 降序、id 降序读取并暂存；实际写入数量必须与候选数完全一致，否则构建失败。Blog ID 以补齐到 19 位的字符串作为成员，使 liked 同分时的 Redis 逆序与 MySQL 的 id 降序保持一致。非空榜写临时 key 并设置 TTL；空榜不创建临时 ZSET，而是直接发布 ready、count 为零的元数据。发布脚本检查自己仍是当前 generation，并核对候选数、capacity 和实际成员数。

新 Blog 提交后只会尽力以 NX 方式加入已就绪榜，分数为零，并裁剪到 top-K。只有新成员最终被保留时才推进 generation；失败不影响 Blog 事实，后续全量刷新可以恢复。

### 3.6 异常分类与状态收敛

| 异常现场 | 当前能够确认的事实 | 处理方式 | 是否重试 | 是否允许补偿 |
| --- | --- | --- | --- | --- |
| 重复 PUT 或 DELETE | 目标关系已经满足 | 返回 unchanged，不写 Outbox | 可安全重放 | 不需要 |
| Blog 不存在或关系写失败 | 关系事实未成功提交 | 事务回滚 | 修复输入或依赖后 | 不需要 |
| writeEnabled 为 false | 点赞写入口处于维护门禁，关系没有变化 | 返回 503 | 开关恢复后可重试 | 不需要 |
| 关系变化后 Outbox 插入失败 | 关系与待办不能一起成立 | 整个写事务回滚 | 是 | 不需要 |
| workerEnabled 为 false 且已有 pending Outbox | 关系事实已成立，聚合待办仍在数据库 | 当前不会自动推进，需启用 worker 或人工处理 | 启用后再调度 | 不做业务补偿 |
| worker Redis 锁忙 | 只知道别的实例可能正在处理 | 本轮跳过 | 下个调度周期 | 不需要 |
| worker 锁服务异常 | 无法用 Redis 减少竞争 | 退回 MySQL 行锁继续 | 当前调用继续 | 不需要 |
| delta 非正负一、聚合将为负或更新行数异常 | 本批数据不满足不变量 | 批事务回滚，事件保持 pending | 修复数据后可重试 | **不允许直接标 processed** |
| worker 更新聚合后、marker 前失败 | 两项都没有提交 | 同一事务整体回滚 | 是 | 不需要 |
| marker 数与选中事件数不一致 | 无法证明精确事件都完成 | 整批回滚 | 是 | 不需要 |
| 永久坏事件反复位于待处理前部 | 无法自动安全跳过 | 当前无 DLQ 或隔离，可能持续阻塞 | 会重复遇到 | 需人工核查，不能伪造完成 |
| 榜单未就绪、过期、元数据损坏或读中切版 | Redis 快照不可信 | 视为 MISS，在 DB_READ 内回退 MySQL | 可触发后续刷新 | 重建读模型，不是业务补偿 |
| 热榜 ID 无法完整映射到 MySQL | 该快照已经陈旧或不完整 | 放弃整页，回退 MySQL | 后续刷新 | 不修改点赞关系 |
| 旧 builder 晚到 | 已有更高 generation | 发布脚本拒绝并清理临时数据 | 无需重试旧代 | 不适用 |
| Redis 榜单丢失 | 点赞关系仍在 MySQL | 回退并在开关允许时重建 | 是 | 不影响关系事实 |

这条链几乎不使用“补偿”一词。写事务失败时事实整体回滚；worker 失败时待办仍在；热榜失败时重建派生读模型。直接删除坏事件或手改 ZSET 会掩盖关系、聚合与事件之间的不一致，因此必须先定位事实。

### 3.7 状态、事实和不变量

| 层次 | 保存位置 | 含义 |
| --- | --- | --- |
| 点赞关系 | tb_blog_like | 用户当前是否点赞某篇 Blog 的长期事实 |
| 历史不可归属计数 | tb_blog.legacy_liked_offset | V8 之前无法对应用户身份的点赞数 |
| 聚合值 | tb_blog.liked | 用于排序的 MySQL 派生聚合 |
| 聚合进度 | tb_blog_like_outbox.processed_time | 单条增量是否已被聚合事务处理 |
| 热榜快照 | Redis ZSET 与 ready、generation、count、capacity、publishedAt | 有限 top-K 读模型与发布协议 |

这些不是一条点赞状态机。用户关系只有“存在或不存在”；Outbox 的 pending/processed 是聚合进度；热榜 ready 与 generation 是快照协议。tb_blog.liked 也不能无条件等于关系表 COUNT。允许 Outbox 尚未处理时，目标恒等式是

~~~text
tb_blog.liked + 未处理 Outbox 的 delta 之和
  = tb_blog.legacy_liked_offset
  + 当前 tb_blog_like 关系数
~~~

当没有 pending Outbox 时，它才简化为 liked = legacy_liked_offset + 当前关系数。V8 先把旧 liked 写入 legacy_liked_offset。停写导入能够识别的旧 Redis 用户关系时，每新增一条关系就等量减少 offset，使总聚合不变；记录完成前还要求没有 pending Outbox，并保存可审计 cutover marker。

系统必须长期守住：

1. 同一用户与同一 Blog 最多一条关系。
2. 只有关系恰好变化时才写一条对应正一或负一事件。
3. 聚合结果不能小于零。
4. 聚合更新与精确原事件 processed marker 同事务。
5. 正式热榜只能发布当前 generation 的完整、自洽快照。
6. Redis 榜单丢失不能影响 MySQL 点赞关系。

### 3.8 面试官最可能追问的 5 个问题

1. **为什么 API 表达目标状态，而不是 toggle？**
   - toggle 在超时重试时会再次反转。PUT 与 DELETE 让重复执行收敛；唯一键冲突或删除零行表示目标已经满足。

2. **唯一键已经防重复，为什么还需要 Outbox？**
   - 唯一键保护用户关系；Outbox 负责让 MySQL 聚合能够在崩溃后继续更新。两者保护的事实不同。

3. **Redis worker 锁异常为什么还敢继续？**
   - Redis 锁只减少多实例竞争。真正保证事件归属和原子提交的是 MySQL FOR UPDATE、条件更新和精确 marker。

4. **临时 key 和 generation 为什么都要有？**
   - 临时 key 防半成品可见；generation 防旧任务晚到。单独一项不能解决另一个问题。

5. **永久坏 Outbox 会怎样？**
   - 当前没有自动 DLQ 或隔离。批事务会反复回滚，坏事件可能阻塞前部待办；需要运维核查或后续设计隔离，不能声称已经自动收敛。

### 3.9 源码、配置、迁移与验证边界

#### 默认配置和启用前提

| 配置 | 默认值或要求 |
| --- | --- |
| 新点赞写入 / 聚合 worker | false / false |
| 热榜 Redis 读取 / 刷新 | false / false |
| top-K / page size | 1000 / 10 |
| refresh initial / fixed delay | 10 秒 / 30 秒 |
| max stale | 2 分钟 |
| Outbox initial / fixed delay / batch | 5 秒 / 200 毫秒 / 500 |
| processed retention | 1 天 |
| cleanup fixed delay / batch / max batches | 1 秒 / 2000 / 5 |
| legacy backfill / scan / batch | false / 500 / 500；启动时要求新写与 worker 都关闭 |

新写和 worker 的门禁还依赖 V8 cutover 完成标记。top-K 之外的页不会由 Redis 提供，直接按 MISS 处理；top-K 与时效配置是功能参数，不是吞吐证明。

#### 源码索引

| 阶段 | 入口 |
| --- | --- |
| 写入口与关系事务 | [BlogController](../../src/main/java/com/localdeals/controller/BlogController.java)、[BlogLikeCommandService](../../src/main/java/com/localdeals/service/BlogLikeCommandService.java) |
| Outbox 聚合 | [BlogLikeOutboxWorker](../../src/main/java/com/localdeals/service/BlogLikeOutboxWorker.java)、[BlogLikeOutboxBatchService](../../src/main/java/com/localdeals/service/BlogLikeOutboxBatchService.java) |
| 热榜读取与发布 | [BlogHotRankService](../../src/main/java/com/localdeals/service/BlogHotRankService.java)、[发布脚本](../../src/main/resources/lua/blog_hot_rank_publish.lua)、[新 Blog 脚本](../../src/main/resources/lua/blog_hot_rank_add_new.lua) |
| Blog 补全与回退 | [BlogServiceImpl](../../src/main/java/com/localdeals/service/impl/BlogServiceImpl.java) |
| V8 数据与切换 | [V8 migration](../../src/main/resources/db/migration/V8__durable_blog_likes.sql)、[BlogLikeLegacyImportService](../../src/main/java/com/localdeals/service/BlogLikeLegacyImportService.java)、[BlogLikeCutoverService](../../src/main/java/com/localdeals/service/BlogLikeCutoverService.java) |
| 配置与门禁 | [BlogLikeProperties](../../src/main/java/com/localdeals/config/BlogLikeProperties.java)、[BlogHotRankProperties](../../src/main/java/com/localdeals/config/BlogHotRankProperties.java)、[BlogLikeCutoverGuard](../../src/main/java/com/localdeals/init/BlogLikeCutoverGuard.java)、[application.yaml](../../src/main/resources/application.yaml) |
| 详细底稿 | [04. 点赞和热榜链路](04-blog-like-hot-rank-chain.md) |

BlogLikeReliabilityIT 覆盖目标状态、并发、Outbox 与 marker 回滚等限定行为；BlogHotRankRedisIT 覆盖旧 builder、top-K、空榜和损坏快照等 Redis 协议。BlogLikeCutoverGuard 只存在于非 test profile，因此测试 profile 绕过该启动门禁。测试源码不能证明生产调度长期运行、缓存故障容量、强实时排行或坏事件人工处置已经形成闭环。

---

## 4. 营销发券：多入口共用一笔权益事务

### 4.1 模块定位

营销链路把用户领取、管理员单发、签到奖励和批量发放统一成一套权益规则。最值得讲的设计是：每个入口只构造服务端可信命令；新建权益时，额度占用、Grant 和通知 Outbox 在一笔 MySQL 事务中成立。批量进度和在线通知失败都不能反向撤销权益。

### 4.2 30～60 秒面试概括版

> 营销发券的难点是用户领取、后台单发、签到奖励和批量发放不能各自定义规则，否则会跨入口重复、越权或并发超额。我把四个入口归一到单人发券事务，用户、商户、操作者、业务日期和幂等语义都由服务端决定。事务锁定活动后复核版本、来源、状态、数据库时间、券归属、标签资格和额度，再用条件更新占额，并把 Grant 与通知 Outbox 一起提交；重复请求通过分阶段查重和唯一约束返回已有 Grant。批量任务只保存目标快照与进度，每个用户的权益独立提交。Item 或通知失败不撤销 Grant；当前两个 worker 默认关闭，PUBLISHED 也不表示用户已收到。

### 4.3 2～3 分钟完整口述版

> 一张普通活动券可以由用户主动领取，也可以由后台单发、签到奖励或批量任务发放。如果四个入口各写一套逻辑，就容易出现跨入口重复领券、管理员越权、并发超发或活动改版后仍按旧规则发放。批量任务和通知还会产生两个额外窗口：任务执行一半可能退出；权益已经成立，在线提示却可能失败或重复。
>
> 我的整体方案是把所有入口归一为服务端可信命令，再进入同一个单人 Grant 事务。用户入口从登录上下文取得 userId；后台入口从 principal 取得 merchantId 和 operatorId；批量从持久 Job 恢复；签到奖励使用服务端业务日。普通领取、后台单发和批量共享 ONCE 幂等身份，每日奖励按业务日生成身份。expectedRuleVersion 由请求携带，但必须与数据库当前版本一致。
>
> 为了兼顾常见重复和极端并发，门面在事务外先查一次；REQUIRES_NEW 事务进入后再查；锁定活动、等待其他事务结束后再查一次。活动锁稳定规则与竞争顺序，真正占额使用同时带状态、版本、时间窗、券归属和剩余额度条件的 UPDATE，数据库唯一约束阻止相同业务身份生成两份 Grant。额度、Grant 与 PENDING 通知 Outbox 在同一事务提交。
>
> 外层故意不持有长事务。唯一键竞争时，内部失败事务必须先完整回滚，外层才能读取胜出的 Grant 并返回幂等结果。批量场景也因此让每个用户的 Grant 独立提交；但一批 Item 的锁和结果 marker 仍共享 worker 的外层事务，不能说每个 Item marker 也独立提交。
>
> 批量 HTTP 只创建 SNAPSHOTTING Job。worker 把当时有效的标签成员固化为 Items，再进入 READY；执行时仍复核当前活动、规则、标签和额度。稳定业务拒绝标 SKIPPED，能够提交 marker 的技术异常标 FAILED；FAILED 需要显式 retry。若 Grant 已提交而 Item marker 回滚，下轮会读到已有 Grant 并收敛为 IDEMPOTENT。
>
> 通知 Outbox 失败时保持 PENDING 并退避。即使达到配置尝试上限，也没有 FAILED 或 DLQ，而是次数封顶后继续按最大退避重试。publish 成功、marker 失败会重复通知，因此客户端应按事件或 Grant 去重并刷新券包。Grant 才是权益事实，当前没有撤券式补偿流程，也不能把 PUBLISHED 说成送达或已读。

### 4.4 端到端主链路

1. 用户领取、后台单发、批量 worker 或签到奖励入口构造来源明确的命令。
2. 服务端补齐 userId、merchantId、operatorId、业务日期和幂等身份，拒绝不合法字段组合。
3. 门面事务外快速查找已有 Grant；常见重复直接返回。
4. REQUIRES_NEW 事务进入后再次查重，再按来源和商户范围锁定活动。
5. 等待活动锁后第三次查重，覆盖竞争事务刚提交的权益。
6. 锁内复核 ruleVersion、来源模式、活动状态、数据库时间窗、券归属、标签资格和额度。
7. 条件更新原子占用一份额度，再插入 Grant 与 PENDING 通知 Outbox；三者同事务提交。
8. 批量入口只创建幂等的 SNAPSHOTTING Job；worker 将当时有效标签成员固化成 Items，并把 Job 推进到 READY。
9. 执行 worker 锁定有限批 PENDING Item，逐个调用同一单人 Grant 事务，再记录 GRANTED、IDEMPOTENT、SKIPPED 或 FAILED。
10. 没有待处理 Item 后，Job 根据失败数量进入 COMPLETED 或 PARTIAL_FAILED。
11. 通知 worker 锁定到期 PENDING Outbox，向 Redis Pub/Sub 发布；失败记录次数和下次时间，成功后标 PUBLISHED。
12. 客户端按事件身份去重并重新查询自己的券包；MySQL Grant 是最终权益依据。

### 4.5 核心机制与设计取舍

#### 机制一：服务端可信命令与业务幂等身份

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 让四个入口共享规则，又保留“永久一次”和“每日一次”的业务含义 |
| 防止什么竞态或故障 | 客户端伪造 userId、merchantId、operatorId、任务日期或任意幂等 key |
| 为什么更直接的方案不够 | 接受客户端自定义身份会把权益边界交给不可信端；四套 Service 会逐渐产生规则漂移 |
| 本身不能解决什么 | 命令规范化不替代消费者登录、后台权限与商户范围校验 |

USER_CLAIM、ADMIN_GRANT、BATCH_GRANT 使用 ONCE，因此同活动同用户只形成一份永久权益；TASK_REWARD 使用服务端业务日组成 idempotencyKey。每日奖励还要先确认同用户同业务日的 MySQL 签到事实。

各来源的快速查重条件不同：任务奖励按活动、用户、幂等 key；后台和批量按活动、商户、用户；用户领取按活动、用户。数据库最终唯一键仍统一为 campaignId、userId、idempotencyKey。

#### 机制二：活动行锁、条件占额与唯一约束

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 稳定活动规则并正确争抢最后额度 |
| 防止什么竞态或故障 | 两个用户同时看到剩一份；等待期间规则变化；同一用户跨入口重复 |
| 为什么更直接的方案不够 | Java 中先查状态和额度存在检查到写入窗口；只用锁不能表达最终幂等身份 |
| 本身不能解决什么 | 这些数据库约束不负责批量进度或通知送达 |

活动锁内使用数据库当前时间，按固定顺序检查活动、标签和成员。Java 额度判断只用于提前拒绝；最终 UPDATE 同时复核 ACTIVE、ruleVersion、起止时间、voucherId、商户范围和 grantedCount 小于 quotaTotal。Grant 或 Outbox 插入失败会让额度更新一起回滚。

#### 机制三：三次查重与独立 Grant 事务

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 让常见重复尽早返回，并让唯一键竞争的输家安全读取胜者 |
| 防止什么竞态或故障 | 两次检查之间产生并发 Grant；在 rollback-only 事务里吞掉 DuplicateKey 后误报成功 |
| 为什么更直接的方案不够 | 只查一次无法覆盖后续窗口；把门面也包在同一事务中，内部失败后外层查询仍可能最终回滚 |
| 本身不能解决什么 | 独立 Grant 提交后，外层 Item marker 仍可能失败并暂时落后 |

三次检查分别位于事务外、独立事务内锁前、活动锁后。它们用于减少无谓竞争和尽早收敛；数据库唯一约束仍是极端并发的最后边界。DuplicateKey 发生后，内部事务先回滚额度与写入，门面再在事务外读取胜出的 Grant。

#### 机制四：目标快照、执行时复核与 Item 状态

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 既固定“本批选中了谁”，又尊重长任务执行时的当前规则 |
| 防止什么竞态或故障 | 执行中标签成员、活动版本、状态或额度变化；任务崩溃后不知道处理到哪里 |
| 为什么更直接的方案不够 | 执行时动态扫描无法稳定定义批次；只相信旧快照又可能按过期规则发券 |
| 本身不能解决什么 | 快照成员不保证最终获券；Item marker 与独立 Grant 之间仍有提交窗口 |

创建批量任务只接受当前 ACTIVE、ADMIN 或 BOTH、MANUAL_TAG 活动，并按 merchantId、requestId 幂等。快照只固化当时有效且未过期的成员；执行阶段再次进入完整 Grant 事务。稳定规则拒绝是 SKIPPED，技术失败只有在外层批事务能提交 marker 时才成为 FAILED；若批事务自身失败，Item 可能仍是 PENDING。

#### 机制五：Grant 与通知 Outbox 同事务

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 权益成立时一定留下后续通知待办 |
| 防止什么竞态或故障 | Grant 提交后应用在直接 publish 之前退出，导致永久没有任何通知记录 |
| 为什么更直接的方案不够 | after-commit publish 没有持久待办；同步要求 WebSocket 成功会把权益事务绑在在线连接上 |
| 本身不能解决什么 | Redis Pub/Sub 不持久，WebSocket 不保证在线、展示或已读；发布与 PUBLISHED marker 也不是共同事务 |

通知失败只影响提示，不允许撤销 Grant。publish 成功而 marker 事务失败会重复发布，客户端需要用 eventId 或 grantId 去重，并回查持久券包。这里的“Grant 与通知 Outbox 同事务”只适用于 V11 后经当前事务新建的 Grant；V9、V10 已有的历史 Grant 没有迁移回填，当前幂等返回也会在创建通知记录前直接结束。

### 4.6 异常分类与状态收敛

| 异常现场 | 当前能够确认的事实 | 处理方式 | 是否重试 | 是否允许补偿 |
| --- | --- | --- | --- | --- |
| 请求字段或来源组合非法 | 不能形成可信发券命令 | 返回 400 | 修正输入后 | 否 |
| 后台未认证、无权限或跨商户 | 当前操作者没有合法范围 | 返回 401 或 403 | 重新认证或修复授权后 | 否 |
| 当前 scope 下活动或 Job 不存在 | 不能在当前商户范围证明目标存在 | 返回 404 | 修正目标后 | 否 |
| 版本、模式、状态、时间窗、标签或额度不满足 | 稳定业务条件拒绝，尚未提交权益 | 返回 409；批量 Item 可标 SKIPPED | 同一前提下通常无意义 | 不需要 |
| 同幂等身份重复或并发胜者已提交 | 已有合法 Grant | 返回已有权益；批量可标 IDEMPOTENT | 可安全重放 | 否 |
| 命中历史或既有 Grant，但没有通知 Outbox | 权益已存在，当前调用属于幂等返回 | 返回已有 Grant；当前不会自动补建通知待办 | 不会由通知 worker 自愈 | 不得重复占额或撤销 Grant |
| DuplicateKey 后读取不到胜者 | 当前无法确认哪份权益成立 | 异常继续传播，不猜成功 | 确认数据库后 | 否 |
| Grant 流程发生 DataAccessException | 当前不能确认数据库操作结果 | 门面映射为 503；其他未捕获 RuntimeException 仍可能表现为 500 | 核对事实后再重试 | 否 |
| 条件占额后 Grant 或 Outbox 插入失败 | 内层事务没有成功提交 | 额度、Grant 和待办一起回滚 | 是 | 不需要 |
| Grant 已提交，Item marker 未提交 | 权益已成立，进度仍可能是 PENDING | 下轮命中 Grant，Item 收敛为 IDEMPOTENT | 是 | **不得撤销 Grant** |
| 单 Item 可记录的技术异常 | 本次没有确认该目标完成 | 外层事务提交 FAILED | 需要显式 retry-failures | 不影响其他 Grant |
| 外层批事务数据库故障 | marker 可能整体回滚；内层 Grant 可能已分别提交 | 后续重跑并按 Grant 收敛 | 是 | **不得撤销已提交 Grant** |
| Redis publish 失败 | Grant 与通知待办仍存在 | 保持 PENDING，记录次数并退避 | 自动 | 否 |
| publish 成功、PUBLISHED marker 失败 | 提示可能已经发出，但完成标记未提交 | 重试可能重复发布，客户端去重 | 是 | 无法撤回提示 |
| 通知达到 max attempts | 多次失败，但没有永久失败事实 | attempts 封顶，仍为 PENDING 并按最大退避继续 | 是 | 否 |
| Job worker 关闭 | 已创建的 Job 可能停在 SNAPSHOTTING 或 READY | 不自动推进，需启用 worker 或人工处置 | 启用后再调度 | 不影响已提交 Grant |
| notification worker 关闭 | 通知 Outbox 可能长期保持 PENDING | 不自动发布，需启用 worker | 启用后再调度 | **不得撤销 Grant** |

某些现场不能立即补偿，是因为 Grant 已经通过规则、额度与唯一约束提交，属于合法权益。Item 进度落后或通知失败都不足以证明 Grant 错误；撤券会破坏已经成立的最终事实。当前源码也没有权益撤销、回收或退款式补偿状态机。

### 4.7 状态、事实和不变量

最终权益事实是 tb_voucher_grant。活动 grantedCount 与新 Grant 同事务更新；签到表按 userId 和业务日保存 TASK_REWARD 的前置事实。Job、Item 和通知 Outbox 都是后续过程或派生状态。

四套状态不能画成一条连续状态机：

#### Campaign 状态

~~~text
DRAFT ──→ ACTIVE ──→ PAUSED ──→ ACTIVE
  └──────────────→ CLOSED ←──────┘
                     ↑
          ACTIVE / PAUSED ───────┘
~~~

DRAFT 可以进入 ACTIVE 或 CLOSED；ACTIVE 可以进入 PAUSED 或 CLOSED；PAUSED 可以回到 ACTIVE 或进入 CLOSED。ACTIVE → ACTIVE、PAUSED → PAUSED、CLOSED → CLOSED 也会被当前接口接受，并执行一次 ruleVersion 增量。只有 DRAFT 或 PAUSED 可编辑。

#### Job 状态

~~~text
SNAPSHOTTING → READY → RUNNING → COMPLETED
                 │        └────→ PARTIAL_FAILED ──retry failures──→ READY
                 └─→ PAUSED ←───┘
                        └────────resume────────→ READY
~~~

READY 或 RUNNING 可以暂停为 PAUSED；PAUSED 恢复为 READY。retryFailures 可以在其他 Job 状态下重置其中的 FAILED Item；只有 Job 当前为 PARTIAL_FAILED 时，Job 本身才会随该操作回到 READY。

#### Item 与通知状态

- Item：PENDING → GRANTED、IDEMPOTENT、SKIPPED 或 FAILED。只有 FAILED 会被显式重置为 PENDING；SKIPPED 不会自动重试。
- Notification Outbox：PENDING → PUBLISHED。发布失败仍是 PENDING；没有 FAILED 或 DLQ。

PUBLISHED 只证明 Redis publish 调用成功且数据库 marker 随后提交。它不表示用户在线、WebSocket send 成功、浏览器展示或用户已读。

系统必须长期守住：

1. 同一 campaignId、userId、idempotencyKey 最多一条 Grant。
2. V11 后经当前事务新建的 Grant、额度增量和 PENDING 通知 Outbox 同事务成立；历史或既有 Grant 的幂等返回不会自动补建通知 Outbox。
3. 幂等返回不能再次占额或写第二条通知。
4. 批量 Job 固化一份目标集合，执行仍按当前规则复核。
5. 每个用户的 Grant 可独立提交；Item marker 落后时通过已有 Grant 收敛。
6. 任何通知故障都不能撤销合法 Grant。

### 4.8 面试官最可能追问的 5 个问题

1. **为什么发券要查重三次？**
   - 分别覆盖常见重复、进入事务前的并发提交、等待活动锁期间的提交。三次查询用于缩小竞争窗口，唯一约束才是最终边界。

2. **为什么 VoucherGrantService 外层故意不加事务？**
   - 内层 DuplicateKey 必须先让 REQUIRES_NEW 完整回滚，外层才能安全读取胜者并返回幂等成功。

3. **行锁、条件更新和唯一约束是否重复？**
   - 行锁稳定活动规则与竞争顺序；条件 UPDATE 在写入瞬间复核业务条件；唯一约束防止相同业务身份产生两份权益。

4. **为什么批量既要目标快照，又要执行时复核？**
   - 快照回答“本批是谁”，复核回答“现在还能不能发”。只保留一项都会丢失另一个时间点的业务含义。

5. **Grant 已提交但 Item 或通知失败，为什么不撤券？**
   - Grant 是最终权益事实；Item 是进度，通知是派生待办。后两者失败不证明权益错误，应该重放并按 Grant 收敛。

### 4.9 源码、配置、迁移与验证边界

#### 入口与幂等语义

| 来源 | 服务端身份来源 | 幂等身份 |
| --- | --- | --- |
| USER_CLAIM | 当前消费者 userId | campaignId、userId、ONCE |
| ADMIN_GRANT | 后台 principal 的 merchantId、operatorId | campaignId、userId、ONCE |
| BATCH_GRANT | 持久 Job 中的商户、操作者和捕获版本 | campaignId、userId、ONCE |
| TASK_REWARD | 当前消费者与服务端业务日 | campaignId、userId、业务日 key |

业务日期默认按 Asia/Shanghai 解释。客户端需要提交正数 expectedRuleVersion，但不能自定义任务日期或幂等 key。

#### 默认配置

| 配置 | 默认值 |
| --- | --- |
| Job worker / notification worker | false / false |
| Job batch / notification batch | 50 / 50 |
| worker initial / fixed delay | 5 秒 / 1 秒 |
| notification max attempts | 10 |
| notification max backoff | 5 分钟 |

max attempts 只封顶 attempts 数值，不创建永久失败状态，也不停止后续重试。

#### 源码索引

| 阶段 | 入口 |
| --- | --- |
| 命令与门面 | [VoucherGrantCommand](../../src/main/java/com/localdeals/dto/VoucherGrantCommand.java)、[VoucherGrantService](../../src/main/java/com/localdeals/service/VoucherGrantService.java) |
| 单人权益事务 | [VoucherGrantTransactionService](../../src/main/java/com/localdeals/service/VoucherGrantTransactionService.java)、[VoucherCampaignMapper](../../src/main/java/com/localdeals/mapper/VoucherCampaignMapper.java)、[VoucherGrantMapper](../../src/main/java/com/localdeals/mapper/VoucherGrantMapper.java) |
| 签到与业务日 | [UserServiceImpl](../../src/main/java/com/localdeals/service/impl/UserServiceImpl.java)、[BusinessDateProvider](../../src/main/java/com/localdeals/service/BusinessDateProvider.java) |
| 批量任务 | [VoucherBatchJobService](../../src/main/java/com/localdeals/service/VoucherBatchJobService.java)、[VoucherBatchJobWorker](../../src/main/java/com/localdeals/service/VoucherBatchJobWorker.java)、[VoucherBatchItemMapper](../../src/main/java/com/localdeals/mapper/VoucherBatchItemMapper.java) |
| 通知待办 | [VoucherGrantNotificationOutboxService](../../src/main/java/com/localdeals/service/VoucherGrantNotificationOutboxService.java)、[VoucherGrantNotificationOutboxWorker](../../src/main/java/com/localdeals/service/VoucherGrantNotificationOutboxWorker.java) |
| 配置 | [VoucherBatchProperties](../../src/main/java/com/localdeals/config/VoucherBatchProperties.java)、[application.yaml](../../src/main/resources/application.yaml) |
| 数据定义 | [V9](../../src/main/resources/db/migration/V9__targeted_voucher_campaign.sql)、[V10](../../src/main/resources/db/migration/V10__daily_sign_task_rewards.sql)、[V11](../../src/main/resources/db/migration/V11__batch_grant_notification_outbox.sql) |
| 局部验证 | [M6aBusinessFlowIT](../../src/test/java/com/localdeals/marketing/M6aBusinessFlowIT.java)、[MarketingGrantConcurrencyIT](../../src/test/java/com/localdeals/marketing/MarketingGrantConcurrencyIT.java)、[M6bDailyTaskBusinessIT](../../src/test/java/com/localdeals/marketing/M6bDailyTaskBusinessIT.java)、[M6cBatchBusinessIT](../../src/test/java/com/localdeals/marketing/M6cBatchBusinessIT.java)、[M6cFlywayIT](../../src/test/java/com/localdeals/marketing/M6cFlywayIT.java)、[M6cRedisRecoveryIT](../../src/test/java/com/localdeals/marketing/M6cRedisRecoveryIT.java) |
| 详细底稿 | [05. 营销发券链路](05-marketing-grant-chain.md) |

M6aBusinessFlowIT、MarketingGrantConcurrencyIT、M6bDailyTaskBusinessIT、M6cBatchBusinessIT 与 M6cRedisRecoveryIT 分别覆盖限定的业务、并发、签到、批量和 Redis 恢复场景。M6cFlywayIT 还明确断言从 V10 升级至 V11 时历史 Grant 不会生成通知 Outbox。它们不证明当前部署已开启 worker、任意规模人群处理、通知送达或生产吞吐。仓库没有 Testcontainers 依赖；恢复测试只会在严格所有权检查通过时操作指定 Redis，测试文件存在不表示本轮已执行。

---

## 5. 旧章节迁移、冲突修正与继续追查

### 5.1 旧 09 到新结构的迁移映射

| 旧内容 | 新位置 | 处理 |
| --- | --- | --- |
| 使用方式、三条链共同方法 | §0～§1 | 合并成一次事实优先级、术语和对比，不在文末重复 |
| 三组概括版与完整版 | 各模块 §2～§3 | 重写成自然第一人口述，开场不再罗列返回码与类名 |
| 三组详细技术链路 | 各模块 §4～§7 | 主流程、机制、异常和状态拆开，不再按 Controller 到 Mapper 流水账 |
| Lua 返回、配置与开关 | 各模块 §9 | 集中成表格，避免在主链重复 |
| 三组高频追问 | 各模块 §8 | 保留真实追问，并补充当前局限 |
| 源码反查 | 各模块 §9 | 分发到对应业务，直接链接源码、迁移与底稿 |

### 5.2 本次明确收紧的事实边界

| 容易误解的旧表述 | 当前源码支持的说法 |
| --- | --- |
| PROCESSING 表示数据库还没有订单 | Redis 尚未确认终态；MySQL 可能未落单，也可能已提交但 markSuccess 失败 |
| 秒杀补偿默认关闭 | 定时对账中的自动补偿默认关闭；Consumer 的两类明确永久失败可即时精确补偿 |
| 事务消息保证跨组件一致 | 它只缩小预占与消息提交窗口；消费幂等、数据库约束与恢复仍不可少 |
| quarantine 是第四个订单状态 | 它是独立安全隔离边界，不改 PROCESSING、不回库存 |
| 点赞 worker 失败后总能自动重试收敛 | 暂态错误可重放；永久坏事件当前无 DLQ 或自动隔离，可能阻塞 |
| liked 等于当前关系数 | 没有 pending Outbox 时才有 liked = legacy_liked_offset + 当前关系数；一般情况下还要把 pending delta 加到 liked 一侧 |
| 每个批量 Item 都独立提交 | 每个 Grant 使用独立事务；有限批 Item marker 共享外层批事务 |
| PUBLISHED 表示用户收到通知 | 只表示 publish 返回成功且 marker 提交，不表示 WebSocket 送达或已读 |

### 5.3 三条链的最终复习对照

| 问题 | 秒杀 | 点赞与热榜 | 营销发券 |
| --- | --- | --- | --- |
| 最终事实 | MySQL 正式订单与数据库库存 | MySQL 用户—Blog 关系 | MySQL Grant |
| 关键暂态 | Redis PROCESSING 与 reservation | pending Outbox、热榜 generation | Job、Item、PENDING 通知 |
| 最终防重 | 订单主键、用户—券唯一键 | 关系复合主键 | Grant 幂等唯一键 |
| 主要异步执行者 | RocketMQ Consumer；可选 Reconciler | Outbox worker 与热榜 builder | Batch worker 与通知 worker |
| 允许补偿的条件 | exact 所有权且确认订单未成立 | 通常回滚、重放或重建，不做业务补偿 | 不因进度或通知失败撤销 Grant |
| 证据冲突 | 暂停并 quarantine | 批事务回滚，坏事件需核查 | 不猜成功；按 Grant 与唯一键重读 |
| 默认关闭能力 | 对账、对账补偿、回填 | 新写、worker、热榜读/刷新、回填 | 批量与通知 worker |

### 5.4 文档与验证边界

| 用途 | 权威入口 |
| --- | --- |
| 项目介绍、补充模块、后台安全核心链 | [08. 补充模块面试讲述手册](08-interview-playbook.md) |
| 秒杀源码级底稿 | [03. 秒杀订单链路](03-seckill-order-chain.md) |
| 点赞与热榜源码级底稿 | [04. 点赞和热榜链路](04-blog-like-hot-rank-chain.md) |
| 营销源码级底稿 | [05. 营销发券链路](05-marketing-grant-chain.md) |
| 主张、证据等级与不能扩大之处 | [06. 证据与边界](06-evidence-and-ownership.md) |
| 当前配置与测试 | [application.yaml](../../src/main/resources/application.yaml)、[src/test](../../src/test/) |

当前实现能够说明各链的控制流、数据库约束、协议状态和局部恢复分支。它不能证明全局强事务、消息 exactly-once、WebSocket 必达、Redis 全量丢失无损恢复、生产吞吐、P99、SLA 或故障恢复时间。面试中遇到没有源码或测试证据的运行结论，应明确说“当前未验证”，而不是用架构术语替代证据。
