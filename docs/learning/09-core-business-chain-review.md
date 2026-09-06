# 09. 三条核心业务面试讲述稿

这份文档只负责三条最能体现设计思路的业务：秒杀、点赞与热榜、营销发券。登录、缓存、GEO、Elasticsearch、后台 RBAC、身份隔离和 WebSocket 鉴权见 [08. 补充模块面试讲述手册](08-interview-playbook.md)。

## 0. 怎样使用这份材料

每条业务的面试回答分成三层：

1. **概括版**：先用 30～60 秒回答“做了什么、为什么这样做、最终事实在哪里”。
2. **完整版**：面试官继续追问时，再按业务问题、整体方案、核心实现、设计原因、异常与一致性展开。
3. **5 个高频追问**：用于准备深挖，不要在第一轮回答里主动把所有细节一次背完。

每组追问之后继续保留原来的**详细技术链路**。概括版和完整版负责形成自然口述，详细层继续保存状态码、Lua / SQL 协议、冲突分类、恢复分支和配置门禁。

完整版不是从 Controller 一路背到 Mapper。它先给出业务闭环，再只展开会影响正确性的实现。面试口述层只解释 Redis 对象承担的业务角色；具体 key、类名、表名和 Lua 返回码留在随后保留的详细技术链路与文末源码反查中。

三条链的共同方法是：

| 业务 | 长期事实 | 允许滞后的部分 | 核心难点 |
| --- | --- | --- | --- |
| 秒杀 | MySQL 正式订单和数据库库存 | Redis 过程状态、在线提示 | Redis、MQ、MySQL 不能共同事务 |
| 点赞与热榜 | MySQL 点赞关系 | 聚合计数和 Redis top-K | 写关系要准确，榜单又要读得快 |
| 营销发券 | MySQL Grant 权益 | 批量进度和在线通知 | 多入口、最后额度、规则变化和任务中断 |

---

## 1. 三条链为什么值得放在一起讲

这三条业务采用的工具不同，但设计顺序相同：

~~~text
先确定不可丢的长期事实
        ↓
让重复请求或重复消息收敛到同一结果
        ↓
跨组件时留下可恢复的状态或持久待办
        ↓
发生异常后根据事实继续，而不是根据异常猜结论
~~~

秒杀先在 Redis 建立资格，再异步形成 MySQL 订单，所以需要事务消息和过程状态；点赞关系本来就在 MySQL，最自然的是把关系和 Outbox 放在一个事务；发券也以 MySQL 权益为事实，因此把额度、Grant 和通知待办一起提交。选择技术的依据是“事实先落在哪里”，不是哪种方案听起来更高级。

---

## 2. 秒杀：同步准入，异步落单，按事实收敛

### 2.1 概括版

我把秒杀拆成同步准入和异步落单两段。入口先按照活动、用户和可信 IP 做三维流量保护，通过后生成贯穿全链路的订单号，再发送 RocketMQ 事务消息。事务回调里的 Redis Lua 会原子校验活动、库存和重复购买，同时写入精确到订单号的预约与 PROCESSING 状态；只有预占成功，消息才对消费者可见。消费端再次确认消息拥有这笔预约，然后在 MySQL 事务里先插订单、再条件扣库存，数据库提交后把 Redis 状态推进到 SUCCESS。MySQL 是正式订单事实，Redis 保存准入和过程证据；遇到不确定结果时保留现场、重试或按事实对账，只有证据充分时才补偿，所有权异常则根据证据进入重试、延后检查或隔离，绝不盲目恢复库存。

### 2.2 完整版

#### 业务场景与要解决的问题

秒杀同时有三个压力：大量请求会在很短时间进入系统；同一用户可能重复提交；Redis 适合快速预占，但正式订单和库存最终还要落到 MySQL。最危险的不是一次明确的“库存不足”，而是系统在 Redis、消息队列和数据库之间中断后，不知道这笔请求究竟做到哪一步。

所以这条链要同时守住四件事：同一用户对同一张券最多一单；数据库库存不能减成负数；只有真正拥有当前预约的消息才能写库；只有能够证明正式订单没有成立时才允许恢复 Redis 库存。

#### 整体方案

我把正常链路分成三层，而不是把所有工作都塞在同步请求里：

~~~text
入口保护
活动、用户、IP 三维限流
        ↓
资格预占
全局订单号 + MQ half message + Redis 原子预约
        ↓
正式落单
Consumer 核对所有权 + MySQL 事务 + Redis 终态

不确定结果 → 事务回查 / MQ 重试 / 状态查询 / 可选对账
所有权异常 → 重试 / 延后检查 / 已确认后隔离，不做猜测性补偿
~~~

Controller 接收秒杀券 ID，并按照可信代理规则解析客户端 IP；用户 ID 来自已恢复的登录上下文。通过流量保护后才生成 orderId。这个 ID 会同时出现在 Redis 状态、MQ 消息、MySQL 订单和结果查询中，让一次尚未落库的请求也有稳定身份。

#### 核心实现与技术亮点

**第一层是三维流量保护。** 活动维度限制整个热点入口，用户维度限制单个账号反复重试，IP 维度限制同一网络来源的集中请求。三个维度由一次 Lua 原子判断和自增，时间使用 Redis 服务端时间，使多个应用实例采用同一窗口基准。任一维度超限时，三个计数都不增加；全部通过后才统一计数。当前是固定窗口，旧 bucket 的 TTL 设为两倍窗口再加一秒，只是清理余量，并不会把它变成滑动窗口。

**第二层是事务消息与 Redis 预约。** 系统先把消费者不可见的 half message 交给 Broker，再在本地事务回调中执行 Redis Lua。Lua 在产生副作用前检查活动元数据、活动状态和时间、快速库存、旧版已购标记以及精确预约；通过后一次性完成库存预占、已购标记、userId 到 orderId 的预约、PROCESSING 状态和待检查索引。

这六类 Redis 对象承担的角色不同：

| 对象 | 作用 |
| --- | --- |
| 快速库存 | 在高并发入口预占有限名额 |
| 旧版已购集合 | 兼容已有的一人一券标记 |
| 活动元数据 | 在写入前判断状态和起止时间 |
| 精确预约 | 证明当前用户的资格属于哪个 orderId |
| 订单状态 | 保存三个所有权 ID 和 PROCESSING / SUCCESS / FAILED |
| 待检查索引 | 用 ZSET 按下一次检查时间找到未决订单 |

PROCESSING 表示“Redis 已经预占，MySQL 还没有确认正式订单”，不是“用户还没有支付”。状态暂时不过期，用来保留故障现场；ZSET 只是到期检查索引，不代表到点就能直接释放库存。

**第三层是消费端的数据库最终仲裁。** Consumer 先校验消息，按用户取得分布式锁，再用只读 Lua 确认隔离状态、PROCESSING 状态、精确预约和三个 ID。只有完全匹配的 PROCESSING 才进入 MySQL 事务。事务先插入订单，再执行库存大于零的条件更新；如果库存更新失败，订单插入一起回滚。

先插订单的好处是重复消息能在再次扣库存前被数据库约束识别。已存在订单与消息三个 ID 完全一致，是同一消息重放，直接幂等返回；同一用户和券对应另一 orderId，或者当前 orderId 已属于其他用户或券，则必须按不同风险分类，不能统称“重复下单”。

#### 为什么这样设计

流量保护放在生成订单号和预占库存之前，是为了让明显无效请求不消耗核心资源。固定窗口实现简单、Redis 操作少，适合入口保护；它在窗口交界处仍可能出现突发，因此默认阈值只是配置，不是容量结论。

事务消息解决的是“Redis 已经接受资格，但普通 MQ 消息没能发出去”的崩溃窗口。只有 Redis 留下完整预约，half message 才能变得可消费；Redis 明确拒绝则丢弃消息；结果未知则由 Broker 后续回查。它没有把 Redis、MQ 和 MySQL 变成全局事务，所以后面仍需要消费幂等、数据库约束和恢复机制。

预约必须精确到 orderId。只记录“用户买过这张券”只能证明历史事实，无法证明一条携带新订单号的异常消息是不是原请求。精确预约与状态中的三个 ID 一起构成消息所有权证据，也为成功确认和补偿限定了精确目标。

数据库继续保留订单主键、一人一券约束和条件扣库存，是因为 MQ 可能重复投递，Redis 也不能代替正式事实仲裁。分布式锁用于减少同一用户并发，不是最终正确性的唯一基础。

#### 异常、一致性与当前边界

Redis Lua 明确返回库存不足、重复购买、活动未开始、活动结束或暂停、活动元数据异常时，拒绝发生在首次写入前，可以直接回滚 half message，本次没有库存需要恢复。Lua 执行抛错或结果不确定时不能当失败，因为可能是执行成功但响应丢失。Broker 回查时，已隔离、预约缺失或不匹配、状态所有权不匹配以及 FAILED 都返回 ROLLBACK；精确 PROCESSING 或 SUCCESS 返回 COMMIT；状态查询无结果、status 缺失、未知状态或 Redis 异常才继续 UNKNOWN。

Consumer 遇到暂时数据库异常会保留 PROCESSING 并让 MQ 重试。“数据库查询失败”绝不等于“订单不存在”。如果 MySQL 已经提交、Redis 的 markSuccess 暂时失败，正式订单仍然成立，状态接口可以按 MySQL 返回成功，后续重放或可选对账会再次尝试修复 Redis；Redis 继续不可用或预约损坏时修复仍可能失败，但绝不能因此补库存。

只有能证明当前精确预约应该撤销时才补偿。Consumer 已明确分类出的数据库库存不足或同用户同券冲突，可以暂停新准入后精确补偿；对账中的长期超时还要再次确认 MySQL 事实，并受独立补偿开关控制。数据库确认 orderId 已属于其他订单时，Consumer 会暂停并隔离；Redis 校验阶段发现所有权不匹配时先作为污染消息走 MQ 重试和死信，对账也只有在可信用户锁内确认冲突后才隔离。合法 orderId 暂时无法解析用户时则保留并延后检查。

数据库提交后，成功脚本会再次确认预约，把 PROCESSING 改成 SUCCESS，移出待检查索引并给终态设置保留时间；成功 reservation 继续保留为归属证据。WebSocket 只提示客户端刷新，失败不会回滚订单。HTTP 返回 orderId 只表示请求已受理并留下可查询状态，不表示 MySQL 订单已经同步创建。

当前实现边界：

| 状态 | 内容 |
| --- | --- |
| 已实现 | 可信 IP、三维限流、事务消息、Redis 原子预约、精确 reservation、事务回查、消费幂等、数据库约束、状态查询、成功迁移、补偿与隔离代码路径 |
| 默认开启 | 秒杀入口流量保护 |
| 已实现但默认关闭 | 定时对账、对账中的自动补偿、启动历史状态回填 |
| 未实现 / 不能声称 | 支付超时取消、MQ exactly-once、全局强事务、WebSocket 必达、生产容量或 SLA |

### 2.3 面试官最可能追问的 5 个问题

1. **为什么不用“Redis 扣库存后发送普通消息”？**
   - 回答要点：两步之间存在进程崩溃窗口；事务消息先保存不可见消息，再由 Redis 预占结果决定提交或回滚；后续 MySQL 仍靠幂等和恢复，它不是全局事务。

2. **为什么预约一定要精确到 orderId？**
   - 回答要点：已购集合只能证明用户过去买过，不能证明当前消息的所有权；用户、券相同但订单号不同的旧消息不能被错误提交；精确预约同时服务回查、消费校验和补偿。

3. **怎样防止超卖和重复下单？**
   - 回答要点：Redis 原子预占减少数据库竞争；MySQL 条件更新阻止库存减成负数；一人一券和订单主键约束最终防重；相同消息重放在再次扣库存前收敛。准确说“由多层边界防护”，不要夸成绝对不会出现任何异常。

4. **MySQL 成功但 Redis 状态没改成功怎么办？**
   - 回答要点：MySQL 正式订单优先；查询可按数据库事实返回成功；MQ 重放或可选对账会再次尝试修复 Redis，但不承诺一定成功；不能恢复库存，也不能因为 WebSocket 没通知就判失败。

5. **为什么看到失败不能立即补库存？**
   - 回答要点：网络或数据库异常不能证明订单不存在；必须同时确认预约归属、数据库没有对应订单且补偿只执行一次；证据不足保留 PROCESSING，污染消息先重试，只有确认后的高风险冲突才隔离；对账补偿当前默认关闭。

### 2.4 详细技术链路：从 HTTP 请求到最终状态（保留原复习层）

#### 请求入口、身份和返回契约

秒杀入口接收的是 voucherId，不是 orderId。登录拦截器先恢复消费者身份，Controller 按可信代理规则解析客户端 IP，只把 voucherId 与 clientIp 交给 Service；userId 由 Service 从 UserHolder 取得，orderId 要在流量保护通过后才生成。

只有直连地址来自配置的可信代理时才采信转发头：优先使用合法的 X-Real-IP，否则使用 X-Forwarded-For 第一项，最后才回到直连地址。这样客户端不能简单伪造转发头绕过 IP 维度限制。

成功返回的 orderId 使用十进制字符串，避免 64 位整数在 JavaScript 客户端丢失精度。几类结果的传输语义不同：

| 结果 | 当前接口语义 |
| --- | --- |
| 未登录 | HTTP 401 |
| TrafficGuard 超限 | HTTP 429 |
| 库存不足、重复下单、未开始、已结束或暂停 | 当前为 HTTP 200 中的业务失败结果 |
| 活动元数据、ID、MQ 或 Redis 不可用且无法恢复 | HTTP 503 |
| 返回 orderId | 请求已受理，不表示 MySQL 已同步创建订单 |

#### TrafficGuard 的完整固定窗口协议

流量保护围绕同一 voucherId 构造三个前缀：

- 活动维度；
- voucherId 与 userId 维度；
- voucherId 与客户端 IP 摘要维度，Redis key 中不直接暴露原始 IP。

三个前缀共享同一券 ID 的 Redis hash tag，Lua 再追加当前时间 bucket。脚本接收四个参数：窗口毫秒数、活动上限、用户上限、IP 上限。

完整判断顺序是：

1. 用 Redis TIME 取得统一服务端时间；
2. 计算当前固定窗口 bucket；
3. 读取三个 bucket key 的计数；
4. 依次判断活动、用户和 IP 是否达到上限；
5. 任一超限立即返回，三个计数都不改变；
6. 全部通过才统一自增，并设置两倍窗口加一秒的 TTL。

TrafficGuard 的返回值是：

| 返回值 | 含义 | 动作 |
| --- | --- | --- |
| 0 | 三个维度都通过 | 继续生成 orderId |
| 1 | 活动维度超限 | HTTP 429 |
| 2 | 用户维度超限 | HTTP 429 |
| 3 | IP 维度超限 | HTTP 429 |
| null、异常或其他值 | 无法确认流控结果 | 失败关闭，HTTP 503 |

当前默认窗口为 1 秒，活动、用户、IP 上限分别是 300、2、100。它们只是配置默认值，不是压测容量或生产 SLA。固定窗口在边界可能出现突发；较长 TTL 只负责回收旧 bucket，不会消除这个特性。

被挡住的请求不会生成订单号、发送消息或触碰库存。已经通过流控、但后续失败的请求也不会回退流控计数，因为计数记录的是入口请求量，不是用户权益。

#### orderId、half message 与 Redis admission

RedisIdWorker 用应用时间戳和 Redis 日序列组合 64 位 ID。它只生成业务关联标识，不创建数据库订单。消息载荷固定包含 voucherId、userId 与 orderId。

生产者先向 Broker 发送消费者不可见的 half message；Broker 接收成功后回调本地事务。当前本地事务是 Redis admission Lua，不是 MySQL 下单事务。Lua 使用六类 Redis 对象：

| Redis 对象 | 准确角色 |
| --- | --- |
| 快速库存 String | Redis 入口库存，不是 MySQL 最终库存 |
| legacy 已购 Set | 兼容历史数据，当前仍检查和写入 |
| 活动 metadata Hash | 保存 status、beginAt、endAt |
| reservation Hash | userId 到 exact orderId |
| 单订单状态 Hash | 保存状态和三个所有权字段 |
| 全局 PROCESSING ZSET | member 是 orderId，score 是下一次检查时间 |

Lua 的四个参数是 userId、voucherId、orderId 与 staleAfterSeconds。脚本先校验 stale 参数和活动 metadata，再要求活动为 ACTIVE，并用 Redis TIME 判断开始和结束时间；随后检查快速库存、legacy Set 和 exact reservation。所有 guard 都通过后才开始写入。

成功时原子完成：

1. Redis 快速库存减一；
2. legacy Set 加入 userId；
3. reservation 写入 userId 到 orderId；
4. 状态 Hash 写入 PROCESSING、三个所有权 ID、创建/更新时间和初始对账次数；
5. PERSIST 状态 Hash，使未决现场暂时没有短 TTL；
6. ZSET 写入 orderId，score 为 Redis 当前时间加 staleAfter。

64 位 orderId 在 Lua 中保持十进制字符串，不能转成可能丢精度的 Lua number。ZSET score 是首次检查时间，不是支付截止时间。

admission 的完整返回和 Broker 决策是：

| Lua 返回 | 业务结果 | 本地事务状态 | HTTP 业务结果 |
| --- | --- | --- | --- |
| 0 | 预约成功 | COMMIT | 返回 orderId |
| 1 | Redis 快速库存不足 | ROLLBACK | 库存不足 |
| 2 | legacy Set 或 reservation 已存在 | ROLLBACK | 重复下单 |
| 3 | 活动未开始 | ROLLBACK | 活动未开始 |
| 4 | 活动已结束、暂停或非 ACTIVE | ROLLBACK | 活动结束或暂停 |
| 5 | metadata 或 stale 参数非法 | ROLLBACK | HTTP 503 |
| null、异常或未知值 | 无法确认 | UNKNOWN | 尝试状态恢复，否则 HTTP 503 |

首次 admission 的 1～5 都发生在第一次写操作之前，所以本次不需要恢复库存。这个解释不能套到 transaction check 的 ROLLBACK；回查回滚还可能表示隔离、预约不匹配或预约此前已经补偿。

sendMessageInTransaction 返回异常或生产者结果为 -1 时，Service 不会立刻断言失败，而会读取当前 orderId 的状态 Hash。如果三个 ID 完整匹配且状态为 PROCESSING 或 SUCCESS，仍可返回原 orderId；否则返回 503。这个 HTTP 恢复分支主要检查状态 Hash，不等同于 Broker 的完整 reservation 回查。

#### transaction check 的精确决策

UNKNOWN 由 Broker 在之后调用 transaction check，不是 HTTP Service 当场主动回查。决策顺序是：

1. 原消息无法解析或三个 ID 缺失：ROLLBACK；
2. quarantine 中存在 orderId：ROLLBACK；
3. reservation 不存在，或不等于消息 orderId：ROLLBACK；
4. 状态不存在、结果结构异常或 status 缺失：UNKNOWN；
5. status 已存在，但所有权字段缺失或三个 ID 不匹配：ROLLBACK；
6. 所有权精确匹配且为 PROCESSING 或 SUCCESS：COMMIT；
7. 精确 FAILED：ROLLBACK；
8. 其他未知状态：UNKNOWN；
9. Redis 查询异常：UNKNOWN。

这里宁可在状态缺失时继续 UNKNOWN，也不能猜测 Lua 没有执行；但 reservation 已明确不存在或不匹配时，消息没有资格所有权，可以回滚。

#### Consumer 前置校验和 MySQL 落单

消息为空、任一 ID 缺失、用户锁依赖异常或未取得锁时，Consumer 都抛异常让 MQ 重试，不直接丢弃。Consumer 与 reconciler 共用用户维度的业务锁，使同一用户的落单与对账尽量串行。

只读 reservation 校验 Lua 返回五种决策：

| Lua 值 | 决策 | 含义 | Consumer 动作 |
| --- | --- | --- | --- |
| 0 | RETRYABLE_STATE_MISSING | 状态缺失、所有权字段不完整，或状态值未知 | 抛错重试 |
| 1 | PROCESS | exact PROCESSING 且 reservation 匹配 | 进入 MySQL |
| 2 | ALREADY_SUCCESS | 已是 exact SUCCESS | 不访问 MySQL，ACK |
| 3 | ALREADY_FAILED | 已是 exact FAILED | 不访问 MySQL，ACK |
| 4 | POISONED | 已隔离、完整字段不匹配或 PROCESSING reservation 不匹配 | 抛错，进入 MQ retry / DLQ 路径 |

字段缺失或状态值未知表示证据暂时无法确认；字段完整但所有权值不匹配才是污染消息。Consumer 遇到 POISONED 不会主动写 quarantine。

MySQL 事务执行顺序是：

1. 尝试插入正式订单；
2. 执行 stock = stock - 1 且 stock > 0 的条件更新；
3. 条件更新影响零行时抛库存耗尽异常；
4. 两步在同一个事务中，库存失败会回滚订单插入。

先插订单使重复消息先触发数据库约束，不会在识别重放前再次扣库存。DuplicateKey 之后必须读取现有事实并分类：

| 数据库现场 | 分类 | 后续动作 |
| --- | --- | --- |
| 相同 orderId，userId 和 voucherId 也相同 | exact replay | 幂等返回，不再次扣库存，随后仍尝试 markSuccess |
| 相同 userId 和 voucherId 已有另一 orderId | reservation conflict | 暂停活动，精确补偿当前预约 |
| 当前 orderId 已属于其他 userId 或 voucherId | orderId ownership conflict | 暂停活动并 quarantine，不自动补偿，继续重试 / DLQ |
| 其他数据库或网络错误 | transient failure | 事务回滚，让 MQ 重试 |

数据库最终边界包括订单主键、userId 与 voucherId 的唯一约束，以及库存大于零的条件更新。用户分布式锁只能减少并发，不能替代这些约束。

#### markSuccess、状态查询和 WebSocket

MySQL 提交后，markSuccess Lua 会再次确认 reservation 仍指向当前 orderId，并核对状态中的三个 ID：

- PROCESSING 精确匹配时改为 SUCCESS，更新时间、移除失败原因、移出 PROCESSING ZSET，并为状态 Hash 设置 7 天 TTL；
- exact SUCCESS 重放时幂等清理 ZSET，并刷新状态 TTL；
- reservation 不匹配或状态所有权不匹配时返回失败；
- 成功 reservation 不删除，继续作为归属证据。

若 MySQL 已提交但 markSuccess 失败，Consumer 会抛异常交给 MQ 重放；状态查询在 Redis 仍为 PROCESSING 且 MySQL 有 exact order 时也会再次尝试修复；reconciler 同样可以再次尝试。即使 Redis 继续不可用或预约损坏，owner 状态查询仍可以依据 MySQL 返回 SUCCESS，但不能承诺 Redis 一定修好，更不能恢复库存。

状态接口按“先保护所有权，再按事实降级”的顺序读取：

1. 先读取 Redis 状态；如果其中的 userId 不是当前用户，直接拒绝，不再用数据库探测订单；
2. Redis 为 PROCESSING 时，按 orderId 查询当前用户拥有的 MySQL 订单，且 voucherId 也一致才返回 SUCCESS，并尽力调用 markSuccess 修复 Redis；
3. 其他合法 Redis 状态直接返回；
4. Redis 状态缺失或 Redis 访问异常时，才按 `orderId + 当前 userId` 回查 MySQL；
5. MySQL 已有 owner 订单就返回 SUCCESS，但不会凭空重建缺失的 Redis reservation 和状态；
6. 两边都没有事实时，Redis 异常返回“状态暂不可用”，正常 MISS 返回“不存在或状态已过期”。

这些失败当前通过 `Result.fail` 表达，通常仍是 HTTP 200；它与提交接口对限流返回 429、对基础设施不确定返回 503 的 HTTP 语义不同。

WebSocket 只在终态后尽力通知。通知异常只记录日志，不回滚数据库或 Redis 状态，也不证明用户在线、收到或读过。状态接口才是查询兜底。

#### 精确补偿和 Consumer 永久失败

补偿 Lua 要求：orderId 不在 quarantine，exact reservation 仍存在，状态仍是完全匹配的 PROCESSING；所有检查先于第一次写操作。首次成功补偿会：

1. Redis 快速库存加一；
2. 删除 exact reservation；
3. 删除 legacy Set 中的用户；
4. 状态改为 FAILED 并记录原因；
5. 移出 PROCESSING ZSET；
6. 为终态设置保留 TTL。

| 补偿返回 | 含义 |
| --- | --- |
| 1 | 本次完成补偿 |
| 2 | 已经是同一 exact FAILED，视为幂等成功，不再加库存 |
| 0 | quarantine、reservation 或状态不匹配，禁止补偿 |

Consumer 的明确永久失败分支还要区分：

- DB_STOCK_EXHAUSTED：MySQL 插入已回滚，先暂停活动，再立即 exact compensate，成功后 ACK；
- DB_ORDER_CONFLICT：同样暂停并立即精确补偿；
- DB_ORDER_ID_CONFLICT：暂停并 quarantine，不补偿，继续抛错进入 retry / DLQ；
- 补偿或隔离本身失败：继续抛异常，由 MQ 重试。

前两类 Consumer 即时补偿不受 reconciler 补偿开关控制；默认关闭的是定时对账中的破坏性补偿。

#### PROCESSING 对账与 quarantine

当前对账配置默认值是：

| 配置 | 默认值 |
| --- | --- |
| reconciler enabled | false |
| compensation enabled | false |
| startup backfill | false |
| initial delay | 30 秒 |
| fixed delay | 10 秒 |
| stale after | 2 分钟 |
| retry delay | 1 分钟 |
| final timeout | 15 分钟 |
| batch size | 100 |
| startup backfill scan count | 500 |

在线 reconciler 与启动时 PROCESSING 索引 backfill 不能在同一进程同时启用，配置校验会直接拒绝这种组合；backfill 是一次性迁移工具，不是另一套在线对账 worker。

启用后，reconciler 使用 Redis TIME 从 ZSET 有界读取 due orderId，不会先删除。非法 raw member 可以在扫描阶段直接隔离；合法 orderId 如果无法解析出可信 userId，就不能取得与 Consumer 相同的业务锁，只能把检查时间后移并保留现场。

对于能够解析用户的订单，对账先取得 scheduler 仲裁锁，再取得共享用户锁，随后用 claim Lua 原子复核状态、reservation、index 与 quarantine。claim 的完整决策是：

| Lua 值 | claim 决策 | 对账动作 |
| --- | --- | --- |
| 1 | CLAIMED | 增加尝试次数、推迟 due score，并查询 writer MySQL |
| 2 | NOT_DUE | 跳过，等待到期 |
| 3 | TERMINAL | 清理或跳过终态 |
| 4 | OWNERSHIP_MISMATCH | 在用户锁内确认后 quarantine |
| 5 | STATE_INVALID | 在用户锁内确认后 quarantine |
| 6 | RESERVATION_MISMATCH | 在用户锁内确认后 quarantine |
| 7 | INDEX_MISSING | 跳过，不凭空重建索引 |
| 8 | QUARANTINED | 跳过已隔离订单 |

只有 CLAIMED 才会记录本次检查时间并进入 writer MySQL 分类：

| writer DB 分类 | 对账动作 |
| --- | --- |
| exact order | 再次尝试 markSuccess |
| ABSENT，尚未到最终超时 | 保持 PROCESSING，等待下轮 |
| ABSENT，已超时但补偿关闭 | 保持 PROCESSING |
| ABSENT，已超时且补偿开启 | exact compensate 为 FAILED |
| user + voucher 属于另一 orderId | 先暂停活动；补偿开关开启才精确补偿，否则保留 |
| orderId 属于其他订单 | 始终暂停并 quarantine，不自动补偿 |
| 数据库查询异常 | 保持 PROCESSING，禁止解释成 ABSENT |

reconciler 不负责重新发送 MQ，消息交付重试仍由 RocketMQ 负责。

quarantine 是独立安全边界，不是 SUCCESS / FAILED 之外的新业务状态：

- ZSET score 记录进入隔离的 Redis 时间，不是过期时间；
- reason Hash 保存隔离原因；
- 隔离会移除 PROCESSING due member，但不修改业务 status；
- 不删除 reservation，也不恢复库存；
- transaction check 遇到隔离返回 ROLLBACK；
- Consumer 校验遇到隔离返回 POISONED；
- compensation 遇到隔离会拒绝执行。

状态机可以概括为：

~~~text
Redis admission 成功
        ↓
    PROCESSING
        ├─ MySQL exact order 已提交
        │      └─ 再次尝试 markSuccess → SUCCESS
        ├─ Consumer 明确 stock / pair 永久失败
        │      └─ suspend + exact compensate → FAILED
        ├─ 对账证明长期 ABSENT 且补偿开关开启
        │      └─ exact compensate → FAILED
        ├─ orderId 已属于其他 DB 订单
        │      └─ suspend + quarantine，不补偿
        ├─ 合法 orderId 但身份暂时无法解析
        │      └─ 保留 PROCESSING，延后检查
        └─ DB / Redis 暂时异常
               └─ 保留现场，MQ 重试或下轮再查
~~~

四个不变量始终不变：

1. 同一用户对同一张秒杀券，MySQL 最多一笔订单；
2. 数据库库存不能减成负数；
3. 只有持有 voucherId、userId、orderId 精确预约的消息才能写 MySQL；
4. 只有证明预约归属且确认数据库没有相应订单时，才允许恢复 Redis 库存。

---

## 3. 点赞与热榜：关系是事实，聚合可追踪，榜单可重建

### 3.1 概括版

点赞链路我没有把“点赞关系、点赞数、排行榜”当成一份数据。用户与 Blog 的点赞关系是 MySQL 长期事实，接口用“最终应该已点赞或未点赞”的目标状态，因此重复请求不会把结果反转；关系真正变化时，在同一事务中写一条增量 Outbox，后台再批量更新博客点赞数。Redis 热榜只是从 MySQL 聚合字段生成的 top-K 读模型，读取时会确认快照完整、版本一致且没有过期，不可信就经过数据库并发保护回退 MySQL；重建则先写完整临时榜，再通过版本栅栏原子发布。

### 3.2 完整版

#### 业务场景与要解决的问题

点赞按钮很容易被重复点击，客户端超时也会重试。关系必须准确，但如果每次关系变化都同步更新聚合数和整个排行榜，写链路会被放大；如果只更新 Redis，又会把可丢的缓存误当成用户点赞事实。

这条链真正需要解决三个问题：重复请求必须幂等；关系变化不能永久丢掉计数增量；读者不能看到尚未完整构建或已经过期的榜单。

#### 整体方案

我把数据拆成三层：

| 层次 | 含义 | 一致性要求 |
| --- | --- | --- |
| 点赞关系 | 谁当前点赞了哪篇 Blog | MySQL 长期事实，写入必须准确 |
| Blog 点赞数 | 对关系变化的 MySQL 聚合 | 允许短暂滞后，但每条增量要可追 |
| Redis 热榜 | 基于聚合数生成的有限 top-K | 派生快照，失效可回退和重建 |

写请求只同步保证“关系和增量事件一起成立”；后台 worker 批量让计数收敛；读请求只接受完整、同一代且未过期的 Redis 快照，否则回退 MySQL，并在重建开关开启时异步触发重建。

#### 核心实现与技术亮点

**目标状态接口。** 点赞和取消点赞分别表达“最终应该已点赞”和“最终应该未点赞”，而不是 toggle。写事务先确认 Blog 存在，并在当前事务中给父 Blog 行加共享锁，防止存在性检查后 Blog 被并发删除。点赞时插入关系，唯一键冲突表示本来就已点赞；取消时删除关系，影响零行表示本来就未点赞。只有关系恰好变化一行时才写正一或负一的 Outbox。

**可重放的聚合 worker。** 后台任务先尝试跨实例 Redis 锁以减少争抢；没抢到锁时本轮退出，锁服务异常时仍可进入数据库，由 MySQL 行锁保证正确性。worker 在一个事务里锁定有限批未处理事件，校验增量只能为正一或负一，按 Blog ID 聚合，并按固定 ID 顺序更新计数，降低反向加锁造成的死锁。计数更新与精确事件的完成标记一起提交，任何异常整批回滚。

**可验证的热榜快照。** 榜单元数据包含 ready、generation、count、capacity 和 publishedAt。读之前检查是否已完整发布、数量是否合法、是否过期，读完 ZSET 后再次核对 generation 和 count，避免在切版期间拼出旧元数据与新榜单。合法的非空命中只提供排序 ID，仍在数据库读取 bulkhead 内批量补齐 Blog、作者和当前用户点赞状态；缓存未命中也在同一保护下查询 MySQL。

**先构建后发布。** builder 在临时 key 中写完 MySQL top-K，再由 Lua 检查 generation 和成员数量，最后原子 rename 为 live 榜并更新元数据。临时 key 隔离半成品，generation 拒绝较早启动却较晚完成的旧 builder 覆盖新版本；跨实例锁只减少重复构建，不能替代版本栅栏。

#### 为什么这样设计

目标状态适合网络重试。toggle 连续执行两次会从未点赞变成已点赞再变回未点赞；PUT 或 DELETE 重复执行只会收敛到同一个目标。

Outbox 解决的是典型双写窗口：如果先提交点赞关系，再单独更新计数或发送事件，应用可能在两步之间崩溃。关系和待办同事务保存后，聚合可以晚一点，但不会因为进程退出而彻底遗忘。

批内聚合减少同一 Blog 的 SQL 次数，固定加锁顺序降低死锁概率。Redis worker 锁是性能优化；真正保证一条事件不会被两笔事务同时提交的，是 MySQL FOR UPDATE，以及“更新计数 + 标记事件”同事务。

榜单使用元数据而不只看 ZSET 是否为空，是为了区分“业务上确实没有 Blog”和“系统尚未构建或缓存已经损坏”。临时榜与 generation 处理的是两个不同问题：前者防半成品可见，后者防旧任务晚到。

#### 异常、一致性与当前边界

关系插入或删除成功但 Outbox 写失败，整个点赞事务回滚。worker 如果在更新计数后、标记事件前失败，两者仍在同一事务中一起回滚，事件下次可以重放。异常增量、计数可能变负、更新行数或 marker 数量不一致都会让整批停止，而不是悄悄跳过。

没有抢到 Redis worker 锁时，本轮直接退出；获取 Redis 锁本身发生异常时，才降级到 MySQL 行锁继续处理，此时数据库竞争会增加。热榜未构建、过期、元数据损坏、成员非法、前后版本不一致或 Redis 异常时，系统不会把“我不知道”伪装成空榜，而是在本地数据库 bulkhead 下回退。拿不到许可则快速失败，避免缓存故障放大为数据库雪崩。

缓存未命中或榜单详情映射不完整时，只有重建开关开启才异步 single-flight 触发；当前请求不等待重建。Redis 榜单完全丢失也不会丢点赞关系，可以从 MySQL 聚合字段重建。关系提交后，点赞数与热榜允许短暂滞后，它不是强实时排行。

当前实现边界：

| 状态 | 内容 |
| --- | --- |
| 已实现 | 目标状态接口、点赞关系唯一约束、关系与 Outbox 同事务、批量聚合与行锁、榜单完整性校验、MySQL 回退、bulkhead、临时榜和 generation 发布 |
| 已实现但默认关闭 | 新点赞写入、点赞 Outbox worker、Redis 热榜读取、定时或异步重建、旧点赞身份回填 |
| 启用前置条件 | 旧点赞数据需要完成受控迁移和切换检查 |
| 未实现 / 不能声称 | 强实时点赞数、Redis 保存点赞事实、每次 MISS 必然重建、生产吞吐或缓存故障容量 |

### 3.3 面试官最可能追问的 5 个问题

1. **为什么不用 toggle 点赞？**
   - 回答要点：超时重试可能把结果反转；PUT / DELETE 表达目标状态；重复插入的唯一键冲突和重复删除的零行都表示目标已经满足，不再写 Outbox。

2. **为什么需要 Outbox，直接更新点赞数不行吗？**
   - 回答要点：关系与聚合是两层数据；跨两次提交会出现关系成功、计数永久漏记；同事务保存关系和事件后，计数可以异步批量收敛。

3. **多个 worker 会不会重复应用同一事件？**
   - 回答要点：Redis 锁只减少竞争；MySQL FOR UPDATE 决定同一批由哪笔事务处理；计数与 marker 同事务，失败后整批仍待处理。

4. **临时 key 和 generation 为什么都需要？**
   - 回答要点：临时 key 防读到半榜；generation 防旧 builder 晚到覆盖新榜；跨实例锁只是减少重复工作，不能替代发布资格校验。

5. **Redis 空了或故障会不会打垮数据库？**
   - 回答要点：先识别合法空榜与不可信缓存；非空榜补详情和 MISS 回退都受本地 bulkhead 保护；拿不到许可快速失败；只有开关开启才异步重建。

### 3.4 详细技术链路：从目标状态到原子发布（保留原复习层）

#### 目标状态写事务

当前接口把点赞和取消点赞表示为明确目标：

- PUT /blog/{id}/like：最终应该已点赞；
- DELETE /blog/{id}/like：最终应该未点赞；
- 兼容入口也必须显式传 desired state，不恢复无参数 toggle。

Service 从当前用户上下文取得 userId，检查点赞写开关和参数，然后在 Spring 事务中执行父 Blog 共享锁查询。共享锁允许其他事务读取或取得共享锁，但会阻止 Blog 在本事务提交前被删除或取得冲突的排他锁。锁必须处在外层事务里；如果 SQL 执行后立即自动提交，锁也会随之释放。数据库外键仍是最终完整性边界。

事务随后按目标状态执行：

| 目标 | 数据库动作 | 重复请求怎样收敛 |
| --- | --- | --- |
| 已点赞 | 插入 user 与 Blog 关系 | 唯一键冲突表示目标已经满足，返回 unchanged |
| 未点赞 | 删除 user 与 Blog 关系 | 影响零行表示目标已经满足，返回 unchanged |

正常影响行数只能是零或一，其他结果视为异常。只有关系恰好变化一行时，才写一条正一或负一的 Outbox；关系变化和 Outbox 在同一事务里一起提交，Outbox 写入失败会让关系变化回滚。

这条写链守住三个不变量：

1. 同一用户和 Blog 最多一条有效关系；
2. 关系真正变化时必须同时留下对应增量，关系未变不能写增量；
3. worker 更新聚合数和标记原事件已处理必须一起提交。

#### Outbox worker 的完整批处理

定时任务先检查 worker 开关，再尝试取得跨实例 Redis 锁：

- tryLock 正常返回未抢到锁：本轮直接退出；
- 获取 Redis 锁发生异常：才降级进入 MySQL 行锁流程；
- Redis 锁只是减少多实例争抢，不是正确性根基。

数据库批处理在 READ COMMITTED 事务中执行：

1. 按事件 ID 升序、LIMIT 有界批次、FOR UPDATE 查询 processed_time 为空的事件；
2. 校验每条 delta 只能是正一或负一，非法值让整批回滚；
3. 用按 Blog ID 排序的聚合结构合并同一 Blog 的增量；
4. 净增量为零时可以不更新 Blog，但原始事件仍要标记；
5. 按 Blog ID 固定顺序更新聚合数，降低不同事务反向加锁的死锁概率；
6. 条件 SQL 要求新点赞数不能小于零，且非零聚合更新必须恰好影响一行；
7. 标记这批仍未处理的原始事件；
8. marker 行数必须与选中事件数一致；
9. 聚合更新和 marker 在同一事务中提交。

如果进程在计数更新后、marker 前失败，数据库事务整体回滚，事件仍保持待处理；如果已经提交，marker 会阻止这条增量再次应用。

#### 热榜读取的完整校验

热榜读取先校验页码为正，并根据 page 和 pageSize 计算 offset 与 end。请求范围超过配置 top-K 时，Redis 不保存这一页，按明确 MISS 处理。

每份快照的元数据都要检查：

| 字段 | 校验目的 |
| --- | --- |
| ready | 必须为 1，区分完整发布与尚未初始化 |
| generation | 必须是合法正数，用来识别同一代快照 |
| count | 必须是合法非负数 |
| capacity | 必须与当前 top-K 配置一致 |
| publishedAt | 不能来自未来，也不能超过允许陈旧时间 |

元数据通过后，还要确认 ZSET 实际数量与 count 一致且不超过 top-K，再按分数倒序读取当前页。任一成员无法解析成合法 Blog ID，整页按 MISS。读取后再次取得 generation 与 count；如果与读取前不同，说明正好发生切版，不能返回混合快照。

合法 ready 且 count 为零的榜单可以直接返回空列表，不进入数据库 bulkhead。非空命中只得到排序 ID，仍需取得 DB_READ 许可，再从 MySQL 批量读取 Blog、作者和当前用户点赞关系，并恢复 Redis 顺序。

如果榜单 ID 无法完整映射到 MySQL，当前热榜代码会把这份快照视为陈旧并转向回退路径。Redis MISS 也必须先取得 DB_READ 许可；拿不到许可快速失败，不让缓存故障产生无界数据库并发。

#### MISS 回退与异步触发

获得数据库许可后：

1. 非空 Redis 命中且详情完整时，补充作者和当前用户点赞状态后返回；
2. Redis 未命中或详情映射不完整时，当前请求直接按 liked 降序、id 降序查询 MySQL；
3. 只有 refreshEnabled 已开启时，MISS 或映射不完整才在当前 JVM 内 single-flight 异步触发重建；
4. 当前请求不等待重建完成；
5. single-flight 只合并本实例的触发，真正构建仍要跨实例协调。

#### 临时榜、generation 和原子发布

builder 先尝试无等待的跨实例构建锁，锁忙时跳过本轮。取得锁后，在加载 MySQL 之前推进 generation，为这一代生成专属临时 ZSET：

1. 从 MySQL 按 liked 降序、id 降序查询有限 top-K；
2. 删除可能残留的同 generation 临时 key；
3. 去重并保持候选原始顺序后批量写入；
4. 校验写入数量；只有非空候选实际创建了临时 key，才给它设置有界 TTL；
5. 发布 Lua 再确认自己仍持有当前 generation；
6. 校验候选数量、配置 capacity 和临时 ZSET 实际数量；
7. 非空榜原子 rename 为 live key，空榜则发布 ready 且 count 为零的元数据；
8. 同时更新 ready、generation、count、capacity 和 publishedAt；
9. 过时 builder 被拒绝并清理临时 key。

临时 key 解决构建一半被读到的问题；generation 解决旧 builder 晚完成后覆盖新榜的问题；跨实例锁主要减少重复构建。三者不能互相替代。

#### 故障、配置与数据层级

| 场景 | 当前处理 |
| --- | --- |
| 重复点赞或重复取消 | 关系不变，不写 Outbox |
| 关系改变但 Outbox 写失败 | 点赞事务整体回滚 |
| worker 更新计数后、marker 前失败 | 批事务整体回滚 |
| Redis worker 锁忙 | 本轮退出 |
| Redis worker 锁服务异常 | 降级到 MySQL 行锁，竞争可能增加 |
| 榜单未构建、过期、元数据损坏、成员非法或 Redis 异常 | 在 DB_READ 保护下回退 MySQL |
| 旧 builder 完成时已有新 generation | 发布 Lua 拒绝旧版本 |
| Redis 榜单丢失 | 从 MySQL 聚合字段重建，不影响点赞关系 |

三层数据不要混淆：

- 用户与 Blog 关系是长期事实；
- Blog liked 字段是 MySQL 中的派生聚合，可以短暂滞后；
- Redis top-K 是可重建快照，不是点赞事实。

当前点赞写入、Outbox worker、热榜 Redis 读取、热榜刷新或异步重建、旧数据回填都默认关闭。启用前需要完成旧点赞身份的受控迁移和切换检查。top-K、页大小和最大陈旧时间都是配置，不是性能证明。

---

## 4. 营销发券：多入口共用一笔权益事务

### 4.1 概括版

用户领取、管理员单发、签到奖励和批量发放虽然入口不同，最终都复用同一套单人发券事务。各入口只在服务端构造可信的来源、用户、商户和操作者，统一服务再生成任务日期和幂等身份；事务内锁定活动，复核版本、时间、券归属、标签资格和额度，并把额度占用、Grant 权益和通知 Outbox 一起提交。并发重复由多次查重、活动锁、条件更新和唯一约束共同收敛。批量任务只保存目标快照和进度，每个用户仍独立发券；通知失败只重试通知，绝不撤销已经成立的权益。

### 4.2 完整版

#### 业务场景与要解决的问题

一张活动券可以由用户主动领取，也可以由后台单发、签到奖励或批量任务发放。如果每个入口各写一套规则，很容易出现用户在不同入口重复拿券、管理员越过商户范围、并发抢最后一个额度、活动改版后仍按旧规则发放等问题。

批量和通知又引入了两个时间窗口：任务可能执行到一半崩溃；权益已经成立后，用户通知可能失败或重复。因此必须区分“用户已经获得券”与“任务处理到哪里、通知有没有尝试”。

#### 整体方案

所有入口先归一成服务端可信的发券命令，再进入同一个独立的单人发券事务。数据也拆成三个角色：

| 对象 | 角色 |
| --- | --- |
| Grant | 用户已经取得该活动权益的 MySQL 长期事实 |
| Job / Item | 批量任务的整体状态与单用户处理进度 |
| 通知 Outbox | 权益提交后仍需尝试提醒用户的持久待办 |

普通领取、管理员单发和批量共享“同活动、同用户一次”的永久幂等身份；每日签到奖励额外包含业务日期。客户端不能自行提供或改变来源、操作者、任务日期和幂等语义。

#### 核心实现与技术亮点

**可信命令与统一校验。** 用户入口从当前登录身份取 userId；后台入口先校验功能权限并解析商户数据范围，再填充商户与操作者，事务内通过活动与商户的组合条件锁定记录并确认资源归属；批量 worker 从持久 Job 恢复这些字段；每日奖励由服务端生成当前业务日。统一服务校验字段组合，例如用户领取不能携带后台操作者，每日奖励不能伪装商户发放。

**覆盖不同窗口的幂等检查。** 事务外先快速查已有 Grant，避免常见重复请求进入锁竞争；进入独立事务后再查一次，覆盖两者之间的并发提交；锁定活动并等待其他事务结束后再查一次，覆盖等待锁期间新产生的权益。三次查询是减少竞争和尽早收敛，最终仍由数据库唯一约束仲裁。

**一笔完整的权益事务。** 活动行锁内复核请求规则版本、允许来源、数据库当前时间、活动状态、券与商户归属以及人工标签资格。Java 层看到“还有额度”不是最终判断，真正写入时还会用带状态、版本、时间窗和剩余额度条件的更新原子占额。随后插入 Grant 和通知 Outbox；额度、权益、通知待办任何一步失败都一起回滚。

**独立事务边界。** 外层发券门面故意不持有长事务，而是调用独立事务服务。发生唯一键竞争时，失败事务先完整回滚，外层才能重新读取并发胜出的 Grant，返回同一个幂等结果。批量任务复用这项能力时，每个用户的 Grant 都通过 REQUIRES_NEW 独立提交；但有限批 Item 的锁定与结果 marker 仍共享 worker 的外层批事务，不能描述成“每个 Item 都独立提交”。

**可恢复的批量任务。** 创建批量请求时只保存 SNAPSHOTTING 状态的 Job，并记录商户、活动、操作者、请求身份和当时规则版本，不会在 HTTP 请求里同步生成全部 Items。后台 worker 先把当时有效的标签成员固化为 Items，再把 Job 推进到 READY；执行时每个 Item 仍调用前面的单人发券事务，并再次复核当前活动、版本、标签和额度。

**与权益解耦的通知。** 通知 worker 扫描到期的 PENDING Outbox，向 Redis Pub/Sub 发布用户事件；失败保持待办并退避重试，成功后标为 PUBLISHED。Grant 已经是事实，通知故障不能撤销它。

#### 为什么这样设计

统一发券事务防止四种入口的规则逐渐漂移。服务端生成幂等身份，是为了让“永久一次”和“每日一次”的业务含义不可由客户端篡改。

活动锁、条件更新和唯一约束看似重复，实际各管一个风险：行锁稳定同一活动的规则与额度竞争顺序；条件更新在写入瞬间再次核对版本、状态、时间和剩余额度；唯一约束阻止同一业务身份落成两份权益。锁后二次查询则让等待期间已经成功的请求尽早返回同一个 Grant。

Grant、Item 和通知 Outbox 分开后，恢复顺序变得清楚：权益优先于任务 marker，任务 marker 又不等于通知送达。如果 Grant 已提交但 Item 更新前崩溃，下轮再次调用同一事务会命中已有 Grant，Item 收敛为 IDEMPOTENT，不会再占额度。

批量任务让每个用户的 Grant 独立提交，所以一个用户的发券失败不会撤销其他用户已经提交的权益；可是一批 Item marker 仍在同一个外层事务里提交，批事务失败时可能留下“Grant 已成立、Item 仍待处理”的恢复窗口。下一轮依靠 Grant 幂等事实收敛，而不是假设每个 Item marker 也独立落库。

#### 异常、一致性与当前边界

两个请求并发给同一用户发同一活动券时，一个创建 Grant，另一个通常在锁后命中已有权益；若仍撞到唯一约束，失败事务完整回滚后读取胜者。不同用户竞争最后额度时，活动锁与条件更新确保只有满足写入条件的请求成功。

规则版本、活动状态、时间窗、券归属或标签资格不再满足时，事务稳定拒绝，不占额度。已经条件占额后若 Grant 或通知 Outbox 插入失败，同一事务整体回滚，额度不会单独漂移。

Item 的结果区分 GRANTED、IDEMPOTENT、SKIPPED 和 FAILED。稳定业务拒绝进入 SKIPPED，暂时技术问题进入 FAILED，避免对不可能成功的请求无限重试，也避免把技术故障永久跳过。Grant 已提交而 Item 仍是 PENDING 时，下轮按既有权益收敛。

Redis publish 与 MySQL 的 PUBLISHED marker 不是同一事务。发布成功后 marker 失败会产生重复通知，因此客户端应按事件或 Grant 身份去重并刷新券包。PUBLISHED 只证明发布调用和数据库标记完成，不证明用户在线、WebSocket 送达或已读；当前通知没有真正 FAILED 终态或 DLQ，达到尝试上限后仍按最大退避继续处理。

当前实现边界：

| 状态 | 内容 |
| --- | --- |
| 已实现 | 四类入口统一 Grant 事务、可信身份字段、版本与资格校验、活动锁、条件占额、Grant 与通知 Outbox 同事务、唯一约束收敛、Job / Item 状态机、通知退避 |
| 已实现但默认关闭 | 批量 Job worker、通知 Outbox worker |
| 当前限制 | 批量发放面向符合条件的人工标签活动；技术失败项需要显式重试 |
| 未实现 / 不能声称 | 任意人群规则引擎、通知必达或已读、通知 DLQ、生产批量规模、发券吞吐或 SLA |

### 4.3 面试官最可能追问的 5 个问题

1. **为什么要做三次幂等查询？**
   - 回答要点：事务外查询优化常见重复；事务内加锁前覆盖进入事务的窗口；拿锁后覆盖等待锁期间的提交；最终仍由唯一约束兜底。

2. **行锁、条件更新和唯一约束是不是重复设计？**
   - 回答要点：行锁稳定竞争顺序，条件更新在写入瞬间复核业务条件，唯一约束防止同一幂等身份产生两份权益；三者分别解决顺序、条件和身份重复。

3. **为什么外层不直接加事务？**
   - 回答要点：唯一键竞争会让内部事务进入回滚状态；先让失败事务结束，外层才能安全读取胜者；独立事务还让批量中每个用户的 Grant 单独提交，Item marker 仍属于外层批事务。

4. **Grant 成功但 Item 仍是 PENDING 怎么恢复？**
   - 回答要点：Grant 是权益事实，Item 只是进度；下一轮重跑会命中已有 Grant 并返回幂等结果，再把 Item 标为 IDEMPOTENT，不会重复占额。

5. **PUBLISHED 是否代表用户已经收到券通知？**
   - 回答要点：只代表 Redis publish 和数据库 marker 完成；用户可能离线，消息也可能重复；客户端重新查询券包，MySQL Grant 才是权威事实。

### 4.4 详细技术链路：从统一命令到批量与通知（保留原复习层）

#### 四个入口和五类业务对象

先分清数据含义：

- **券模板**：描述券的面值、门槛、类型和所属店铺；
- **活动**：描述何时、通过哪种来源、向哪些人、最多发多少份权益；
- **Grant**：某个活动已经给某个用户一份券权益的长期事实；
- **Job / Item**：批量任务整体状态与单用户处理进度；
- **通知 Outbox**：Grant 提交后仍需尝试提醒用户的持久待办，不是权益本身。

四种入口最终共享一套事务，但幂等身份不同：

| 来源 | 入口含义 | 幂等身份 |
| --- | --- | --- |
| USER_CLAIM | 登录用户主动领取 | 同活动、同用户永久一次 |
| ADMIN_GRANT | 后台人员给指定用户发券 | 与主动领取共享永久权益 |
| BATCH_GRANT | Job worker 对每个目标用户调用 | 同样共享永久权益 |
| TASK_REWARD | 用户完成当日签到后领奖 | 同活动、同用户、同业务日一次 |

因此，同一活动下用户先主动领取，管理员或批量任务再次命中时，只返回已有 Grant，不再占一份额度。每日任务可以按不同业务日产生新的权益。

#### 服务端可信命令

各入口只设置自己有权确定的字段：

- 用户领取入口从当前登录身份填充 userId 和来源；
- 管理员入口先检查功能权限并解析商户数据范围，再填充 merchantId 与 operatorId；
- 批量 worker 从持久 Job 恢复商户、操作者、规则版本和来源；
- 每日任务入口使用当前登录用户和服务端业务日期。

统一门面会重新生成任务日期与幂等键，客户端不能自定义“永久一次”或“每日一次”的语义。命令还要校验活动、用户、规则版本和来源：

- 管理员和批量来源必须有商户与操作者；
- 用户主动领取禁止携带后台操作者；
- 每日奖励禁止携带商户与操作者，并要求任务日期和幂等键；
- expected ruleVersion 必须是合法正数。

已有 Grant 的快速查询按来源选择：

- TASK_REWARD：活动、用户、幂等键；
- ADMIN_GRANT / BATCH_GRANT：活动、商户、用户；
- USER_CLAIM：活动、用户。

#### 独立事务和三次幂等检查

外层 VoucherGrantService 故意不持有事务。它先快速查重，再调用使用 REQUIRES_NEW 的事务服务：

1. 事务外查询让常见重复请求直接返回；
2. 进入事务后、加活动锁前再查一次，覆盖进入事务前的并发提交；
3. 取得活动锁后再查一次，覆盖等待锁期间前一事务的提交；
4. 最终仍由数据库唯一约束仲裁极端竞争。

独立事务边界很重要。若 insert 触发 DuplicateKey，内部事务和之前的额度更新必须先完整回滚；异常退出事务后，外层才能重新读取竞争胜出的 Grant，并把结果作为幂等成功返回。如果在已经 rollback-only 的事务里吞掉异常继续查询，看似返回成功，最终仍可能整体回滚。

批量处理中，每个用户的 Grant 因此通过 REQUIRES_NEW 独立提交；有限批 Item 的行锁和 outcome marker 仍共享 `processNextBatch` 的外层事务。一个目标的稳定业务拒绝会被捕获并记录为 SKIPPED，但这不等于每个 Item 自己提交一笔事务。

#### 活动锁内的完整资格和额度复核

每日奖励在事务开头先按 userId 与业务日期检查 MySQL 签到事实。没有签到时直接拒绝，不占额度、不写 Grant、也不写通知待办。签到表还有同用户同日唯一约束，重复签到与重复领奖分别在两个层次收敛。

后台或批量来源使用 campaignId 与 merchantId 的组合条件锁定活动；用户领取和签到按活动 ID 锁定。活动查询同时取得发券所需的券和店铺信息。资源归属是在这里由带商户条件的锁定查询确认，不是只依赖 Controller。

取得活动锁后依次复核：

1. 请求 expectedRuleVersion 与当前活动 ruleVersion 一致；
2. 来源被当前 grantMode 允许；
3. 活动处于 ACTIVE；
4. 使用数据库当前时间判断已经开始且尚未结束；
5. 绑定券仍是有效普通券，并属于正确商户；
6. MANUAL_TAG 活动按活动、标签、成员的固定顺序加锁，检查标签和成员状态、商户范围与有效期；
7. Java 层先检查当前已发数量尚未达到总额度。

Java 中的额度判断只用于尽早拒绝，真正仲裁是带活动状态、版本、时间窗、券归属和剩余额度条件的 UPDATE。执行顺序是：

~~~text
[同一个 MySQL 事务]
条件占用一份额度
        ↓
插入 Grant 权益
        ↓
插入 PENDING 通知 Outbox
        ↓
一起提交或一起回滚
~~~

Grant 或通知 Outbox 任一步失败，前面的额度更新一起回滚，不会留下“占了额度却没有权益”或“有权益却完全没有通知待办”的半成品。

#### 并发争抢最后额度

假设两个请求同时看到只剩一份额度：

1. 两者在事务外都可能没有查到已有 Grant；
2. 同一活动行锁使它们依次进入关键区；
3. 先持锁请求复核规则，条件占用最后额度并提交 Grant；
4. 同一用户的后到请求拿锁后会命中已有 Grant；
5. 不同用户的后到请求会在锁内额度检查或条件更新时被拒绝；
6. 若仍发生同一幂等身份的唯一键竞争，失败事务回滚后读取胜者。

各机制职责不同：

| 机制 | 解决的问题 |
| --- | --- |
| 活动行锁 | 稳定同一活动的规则与额度竞争顺序 |
| 锁后再查 Grant | 覆盖等待锁期间已经提交的权益 |
| 条件更新 | 在写入瞬间复核版本、状态、时间、券和额度 |
| 唯一约束 | 阻止同一业务身份落成两份权益 |
| 事务外重读 | 把并发输家收敛成同一个已有 Grant |

#### 批量 Job / Item 状态机

创建批量请求时，HTTP 事务只保存状态为 SNAPSHOTTING 的 Job，记录 merchantId、campaignId、operatorId、requestId、捕获的规则版本和目标标签；不会同步生成全部 Items。Job 创建本身按 merchantId 与 requestId 幂等。

当前批量只支持 ACTIVE、ADMIN 或 BOTH、MANUAL_TAG 活动。默认关闭的 worker 后续在独立快照事务中，把当时有效且未过期的标签成员固化成 Items；每个 Job 与 userId 最多一个 Item。快照完成后 Job 进入 READY。

执行 worker 每轮只锁定有限批 PENDING Item，并逐个调用同一套独立单人发券事务。快照回答“当时选中了谁”，执行时的事务复核回答“现在还能不能发”。标签可能已经失效，活动也可能改版本、暂停、过期或耗尽额度。

Item 结果分为：

| 状态 | 含义 |
| --- | --- |
| GRANTED | 本轮新建权益 |
| IDEMPOTENT | 权益已经由之前调用创建 |
| SKIPPED | 规则、资格、状态或额度等稳定业务条件不满足 |
| FAILED | 数据库或程序等技术问题，未来可能重试成功 |

SKIPPED 与 FAILED 必须区分：稳定业务拒绝不应无限重试，技术故障也不应永久跳过。FAILED 当前需要显式 retry-failures 才会重置，不是无限自动重试。

Job 的主状态流转是 `SNAPSHOTTING → READY → RUNNING → COMPLETED / PARTIAL_FAILED`。运行中的任务可以暂停为 PAUSED，恢复后回到 READY；只有 FAILED Item 会由显式 retry-failures 重置为 PENDING。最终没有待处理项且没有失败项时完成，有失败项时进入 PARTIAL_FAILED。

批量链路故意允许一个可恢复窗口：

~~~text
某个用户的独立 Grant 已提交
        ↓
外层 Item 状态更新前进程崩溃
        ↓
Item 仍是 PENDING，但权益已经存在
        ↓
下一轮再次调用统一发券事务
        ↓
命中已有 Grant，Item 收敛为 IDEMPOTENT
~~~

这说明 Grant 是权益事实，Item 只是进度标记。

#### 通知 Outbox 的重试边界

每个新 Grant 都在同一事务中插入一条 PENDING 通知。默认关闭的通知 worker 按 nextAttemptTime 和 ID 锁定有限批到期待办，再向 Redis Pub/Sub 发布用户事件：

- Redis 发布失败时保持 PENDING，增加尝试次数并安排下次时间；
- 退避时间逐步增长，但有最大上限；
- 尝试次数达到配置上限后不会进入 FAILED，也不会停止，计数封顶后仍按最大退避继续处理；
- 发布成功后标为 PUBLISHED。

Redis publish 与 MySQL marker 不是一个事务。发布成功后、marker 提交前失败时，下轮会重复发布，因此客户端应按 eventId 或 grantId 去重，并重新查询券包。

PUBLISHED 只表示 publish 调用成功且数据库 marker 随后提交，不表示用户在线、WebSocket 已送达或用户已经阅读。当前没有真正的通知 FAILED 终态或 DLQ；通知失败也绝不撤销 Grant。

#### 关键故障与恢复

| 场景 | 当前结果 |
| --- | --- |
| 两个请求并发给同一用户发同一活动券 | 一个创建 Grant；后请求锁后命中，或唯一键失败后读取胜者 |
| 已条件占额后 Grant 或 Outbox 插入失败 | 同一事务整体回滚，额度不漂移 |
| 活动规则在请求期间变化 | 版本复核或条件更新拒绝 |
| 标签在 Job 快照后失效 | Item 执行时复核为 SKIPPED |
| 单个 Item 技术失败 | 记为 FAILED，其他 Item 继续，之后显式重试 |
| Grant 已提交但 Item marker 失败 | 重跑命中 Grant，Item 变为 IDEMPOTENT |
| Redis 通知故障 | Grant 不回滚，PENDING Outbox 退避重试 |
| publish 成功但 marker 失败 | 可能重复提示，客户端去重并查询 MySQL |

批量 Job worker 与通知 worker 当前都默认关闭。相关状态机是已实现能力，不等于某个部署已启用，更不能据此声称批量规模、发券吞吐或通知 SLA。

---

## 5. 把三条链放在一起回答

### 5.1 三种一致性方案为什么不同

| 对比 | 秒杀事务消息 | 点赞 Outbox | 发券事务与 Outbox |
| --- | --- | --- | --- |
| 最先成立的关键事实 | Redis 资格预约 | MySQL 点赞关系 | MySQL Grant |
| 怎样留下后续工作 | Broker 中的 half message | 与关系同事务的增量行 | 与 Grant 同事务的通知行；批量另有 Item |
| 重复怎样收敛 | exact reservation、状态和数据库约束 | 目标状态、关系唯一键、事件行锁 | 幂等身份、活动锁、条件更新、唯一约束 |
| 异常时查什么 | 预约所有权、状态和 MySQL 订单 | 关系、未处理事件和聚合数 | Grant、Item 和通知 Outbox |

事务消息和 Transactional Outbox 都不提供跨所有组件的 exactly-once。共同目标是允许重复交付，但让每次重复回到同一个业务结果。

### 5.2 回答一条链时的节奏

可以始终使用五段式：

1. 先说业务风险和最终事实。
2. 用三层以内的整体方案建立地图。
3. 只讲三个真正影响正确性的实现点。
4. 解释每个设计具体防住哪个竞态。
5. 推演一个最危险异常，并主动说出默认关闭和未实现边界。

如果面试官还没有追问，不需要主动背 Redis key、表名、类名、每个返回码或所有状态分支。这些内容应该作为证明细节，而不是回答的开场。

---

## 6. 按需反查源码和实现细节

### 6.1 秒杀

| 关注点 | 代表入口 |
| --- | --- |
| HTTP 入口和可信 IP | VoucherOrderController、TrustedClientIpResolver |
| 三维流量保护 | SeckillTrafficGuard、seckill_traffic_guard.lua |
| 事务消息与 Redis 准入 | SeckillOrderProducer、seckill_check.lua |
| 消费、落库与冲突分类 | SeckillOrderConsumer、VoucherOrderServiceImpl |
| 状态迁移与补偿 | SeckillOrderStateService、seckill_validate_reservation.lua、seckill_mark_success.lua、seckill_compensate.lua |
| 过期检查 | SeckillOrderReconciler |

Redis 准入返回值：0 表示预占成功；1 表示快速库存不足；2 表示重复下单；3 表示活动未开始；4 表示活动结束、暂停或未启用；5 表示活动元数据缺失或非法。1～5 都在首次副作用前返回；异常或未知结果不能套用这些业务拒绝语义。

### 6.2 点赞与热榜

| 关注点 | 代表入口 |
| --- | --- |
| 目标状态写入 | BlogController、BlogLikeCommandService |
| 增量消费 | BlogLikeOutboxWorker、BlogLikeOutboxBatchService |
| 榜单读取与重建 | BlogServiceImpl、BlogHotRankService、BlogHotRankWarmupService |
| 原子发布 | blog_hot_rank_publish.lua |
| 关系、Outbox 与迁移边界 | V8__durable_blog_likes.sql |

### 6.3 营销发券

| 关注点 | 代表入口 |
| --- | --- |
| 管理员单发 | MarketingAdminController |
| 统一门面与并发收敛 | VoucherGrantService |
| 单人发券事务 | VoucherGrantTransactionService |
| 活动条件更新与查询 | VoucherCampaignMapper、VoucherGrantMapper |
| 批量任务 | VoucherBatchJobService、VoucherBatchJobWorker |
| 通知重试 | VoucherGrantNotificationOutboxService、VoucherGrantNotificationOutboxWorker |
| 数据库约束 | V9__targeted_voucher_campaign.sql、V10__daily_sign_task_rewards.sql、V11__batch_grant_notification_outbox.sql |

更细的 SQL、Lua、迁移和验证证据分别在 [03. 秒杀订单链路](03-seckill-order-chain.md)、[04. 点赞和热榜链路](04-blog-like-hot-rank-chain.md)、[05. 营销发券链路](05-marketing-grant-chain.md) 和 [06. 证据与边界](06-evidence-and-ownership.md)。

本次只重写学习文档，没有启动 Redis、MySQL、RocketMQ、Elasticsearch，没有执行迁移、故障实验或压测。文中“已实现”来自当前 checkout 的源码与迁移，“默认开启或关闭”来自当前配置；这些都不能自动扩大成生产吞吐、延迟、可用性或消息必达结论。
