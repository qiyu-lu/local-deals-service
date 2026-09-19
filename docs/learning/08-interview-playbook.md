# 08. 补充模块面试讲述手册

> 本文负责项目级系统介绍、重要补充模块、简单模块，以及“后台身份、基于角色的访问控制（RBAC）、商户隔离与实时会话”这条核心复杂链路。秒杀、点赞与热榜、营销发券的完整面试版本统一放在 [09. 三条核心业务面试讲述稿](09-core-business-chain-review.md)。源码级细节继续由 [02](02-admin-rbac-chain.md)、[03](03-seckill-order-chain.md)、[04](04-blog-like-hot-rank-chain.md)、[05](05-marketing-grant-chain.md) 承担。

---

## 0. 阅读方式、事实依据与模块分层

### 0.1 怎样使用这份笔记

面试时先说“模块定位”和口述版。面试官继续追问，再进入主链路、机制取舍、异常分类与源码索引。复习源码时顺序相反：先从索引找到入口，再沿业务 ID、数据库事实和状态迁移向前后追查。

事实判断按以下优先级执行：

1. 当前源码、配置和 Flyway 数据库迁移；
2. 当前测试能够覆盖到的局部行为；
3. 02～06 的源码精读和证据记录；
4. 历史设计稿、运行记录与旧面试表述。

测试通过只能说明给定环境和断言范围内的行为。功能已实现、默认已启用、完成端到端验证和达到生产指标是四个不同结论。

### 0.2 本项目的四层内容

| 层次 | 模块 | 本文中的处理方式 |
| --- | --- | --- |
| 项目级系统介绍 | 部署形态、业务角色、数据职责、请求边界、迁移和开关 | 在 §1 建立全局恢复地图 |
| 核心复杂链路 | 后台身份、RBAC、商户隔离与实时会话 | 在 §5 使用完整八段式结构 |
| 核心复杂链路 | 秒杀、点赞与热榜、营销发券 | 只在本文导航，完整版本见 [09](09-core-business-chain-review.md) |
| 重要补充模块 | 消费者登录、读取与搜索、内容发布与关注流 | 保留口述、关键流程、异常边界和源码入口，不机械复制核心模板 |
| 简单模块 | 用户资料、普通目录与券查询、评论骨架等 | 在 §6 一段或一张表讲清 |

### 0.3 本文术语

| 术语 | 本文含义 |
| --- | --- |
| RBAC | Role-Based Access Control，基于角色的访问控制；它回答“能做哪类操作”，不自动证明目标资源归属 |
| MQ | Message Queue，消息队列；本项目的业务消息实现使用 RocketMQ |
| Outbox | 与业务事实同事务写入的持久待办，供后台任务稍后处理 |
| GEO | Redis 的地理位置索引能力，用于半径候选和距离计算 |
| ES | Elasticsearch，本项目中的全文搜索派生索引 |
| TTL | Time To Live，数据的剩余存活时间 |
| JVM | Java Virtual Machine；“JVM 内”表示只约束单个应用实例 |
| SingleFlight | 同一实例内让相同 key 的并发请求共享一次加载 |
| bulkhead | 并发舱；为一类依赖调用设置独立的并发和等待上限 |
| ACK / DLQ | ACK 表示消息已被消费者确认；DLQ 是多次失败后可能进入的死信队列 |
| principal / scope | principal 是当前请求中已验证的身份快照；scope 是该身份允许触达的数据范围 |
| UUID | Universally Unique Identifier，通用唯一标识符；本项目用其随机字符串作为会话凭证 |
| ZSET | Redis Sorted Set，按 score 排序的集合 |
| Pub/Sub | Publish/Subscribe，发布订阅；订阅者离线时不会保存消息 |
| P99 / SLA | P99 是 99% 请求不超过的延迟分位；SLA 是对服务水平的约定 |
| Grant / Job / Item | Grant 是用户已取得券权益的事实；Job 是整体批任务；Item 是其中一个用户目标 |
| Consumer / Worker / Reconciler | Consumer 消费消息；Worker 处理持久待办；Reconciler 按事实重新检查长期未决状态 |
| after-commit | 数据库事务提交后的内存回调；它不等于持久 Outbox |
| generation / marker | generation 是构建代际；marker 是数据库中可审计的完成标记 |

---

## 1. 项目总览：这不是功能拼盘，而是围绕一致性边界做的本地生活平台

### 1.1 项目定位

这是一个同时服务消费者、商户后台账号和平台后台账号的本地生活后端。当前源码是单 Maven 模块、单 Spring Boot 启动入口的单体应用；“模块化”表示按职责进行逻辑组织，不代表已有编译期模块隔离或微服务边界。

### 1.2 30～60 秒面试概括版

> 我做的是一个本地生活平台后端。消费者可以登录、查店、发内容、领券和参加秒杀，商户与平台人员通过独立后台管理资源。难点是订单、点赞和营销都会跨 MySQL、Redis、消息与在线通知，单个数据库事务覆盖不了整条链。系统没有为了形式拆微服务，而是在 Java 8 和 Spring Boot 2.3.12 的单体应用中按身份、内容、订单和营销职责组织。我的核心设计是先确定事实源：订单、点赞关系和券权益以 MySQL 为准；Redis 分别承担会话、流量保护、协议状态和可重建读模型；消息队列负责异步交付；搜索和 WebSocket 分别是派生查询与在线提示。跨组件时再按风险选择幂等、Outbox、回查、补偿或隔离，并明确默认关闭和未验证的边界。

### 1.3 2～3 分钟完整口述版

> 我把项目看成两类入口和四条复杂业务。消费者侧更关注热点读取、重复请求和突发流量，后台侧更关注撤权、商户数据隔离和可恢复批处理。真正难点不是某个 Controller，而是一次业务可能同时经过 MySQL、Redis、MQ、ES 和 WebSocket，这些组件不能被一个普通数据库事务一起提交。
>
> 整体上我采用单应用部署单元，并在源码中按身份、内容、订单和营销职责组织。这样可以继续利用同库事务和数据库约束，同时保持业务边界清楚。MySQL 保存重启后仍需仲裁的长期事实；Redis 中的普通缓存和排行榜可以失效重建，但秒杀预约属于协议证据，不能当普通缓存随意删除；RocketMQ 承接秒杀异步落单和搜索同步消息；ES 只负责检索；WebSocket 只提醒客户端刷新。
>
> 最值得讲的有四点。秒杀先同步准入，再异步落库，并通过精确预约、数据库约束和按事实对账收敛。点赞把关系和 Outbox 同事务保存，聚合异步更新，热榜按完整快照发布。营销把四类发券入口归一为一笔权益事务，再用 Job、Item 和通知 Outbox 表达批量进度。后台安全则把功能权限、商户范围、资源归属和最终写条件分开，并在长连接发送时重新确认权限。
>
> 这些设计不是全局强事务。消息会重复，缓存会陈旧，在线提示会丢，部分恢复任务默认也没有启用。遇到异常时，我先查 MySQL 事实，再看协议状态或持久待办；只有所有权和事实证据充分时才补偿，证据冲突时停止自动动作并隔离。当前仓库能支持源码和局部测试层面的说明，但不能据此给出生产吞吐、P99、消息必达或容灾指标。

### 1.4 系统与数据地图

~~~text
消费者 HTTP / 后台 HTTP / WebSocket 握手
                    ↓
身份恢复、入口授权、输入与流量边界
                    ↓
业务服务：规则、事务、状态迁移、归属判断
                    ↓
      ┌─────────────┼─────────────┐
      ↓             ↓             ↓
 MySQL 长期事实   Redis 在线状态   MQ 异步交付
      ↓             ↓             ↓
 约束与行锁      缓存/协议/读模型  Consumer / Worker
      └─────────────┼─────────────┘
                    ↓
          ES 派生搜索 / WebSocket 提示
~~~

| 数据角色 | 项目中的例子 | 应当怎样判断 |
| --- | --- | --- |
| 最终业务事实 | 用户、店铺、正式订单、点赞关系、Grant | 由 MySQL 事务、唯一约束、行锁和条件更新仲裁 |
| 持久工作流状态或待办 | Job、Item、点赞 Outbox、通知 Outbox | 重启后可继续处理，但它们描述进度或待办，不是订单、关系或权益本身；部分已处理事件还会清理 |
| 协议状态 | 消费者和后台会话、秒杀精确预约与 PROCESSING | 参与后续步骤判断；不能因为位于 Redis 就视为可随意删除 |
| 持久派生聚合 | Blog 点赞数 | 保存于 MySQL，但允许相对关系短暂滞后，需要 Outbox 推进 |
| 可重建读模型 | 详情缓存、GEO、ES 索引、Redis 热榜和 Feed | 允许短暂滞后；必须说明回源、重建或失败边界 |
| 在线提示 | Redis Pub/Sub 与 WebSocket | 只提示刷新；不证明业务成功、送达或已读 |

### 1.5 业务角色、请求边界与部署形态

| 角色 | 主要能力 | 必须守住的边界 |
| --- | --- | --- |
| 消费者 | 浏览、发布、关注点赞、签到领券、秒杀和结果查询 | 身份来自消费者会话，不能自报 userId |
| 商户后台账号 | 管理本商户店铺、券、活动、标签和批量任务 | 功能权限通过后仍需验证商户范围与资源归属 |
| 平台后台账号 | 管理平台资源并跨商户运维 | 操作具体商户数据时仍需显式确定目标范围 |

当前后端是一个 Maven 模块、一个 Spring Boot artifact，不是微服务系统。同一 artifact 可以部署多个实例，但每个实例仍运行同一应用；源码没有强制模块边界。请求层的责任也不能混为一谈：

- 拦截器恢复身份并处理入口授权，不能代替 Service 的资源归属和业务规则。
- Controller 解析 HTTP 输入和组织响应，不承担跨组件恢复协议。
- Service 维护业务不变量、MySQL 事务边界和状态迁移。
- Mapper 形成最终 SQL；Java 中的先查不能替代数据库唯一键、行锁和条件更新。
- Spring 的事务只覆盖进入同一本地事务的数据库操作，不会让 Redis、MQ、ES 或 WebSocket 一起回滚。

### 1.6 数据库演进与默认运行边界

Flyway 在配置中启用并校验迁移。V1～V11 的主要边界如下：

| 迁移 | 新增或强化的业务边界 |
| --- | --- |
| V1 | 基础用户、店铺、内容、关注、券和订单表 |
| V2 | 秒杀订单的用户—券唯一约束 |
| V3～V4 | 上传文件归属，以及 TEMP、PUBLISHED、DELETING 生命周期 |
| V5～V6 | 商户、后台账号、角色权限、店铺商户归属与 authVersion 撤权版本 |
| V7～V8 | 热榜索引、持久点赞关系、聚合偏移、点赞 Outbox 与切换标记 |
| V9～V10 | 标签、活动、Grant、签到事实及不同发券语义的幂等约束 |
| V11 | 批量 Job、Item 与通知 Outbox 状态 |

baseline-on-migrate 允许既有数据库从基线接管，因此不能仅凭迁移目录声称每个环境都实际逐条执行过 V1～V11。目标环境仍要查询 Flyway 历史和真实约束。

重要门禁的配置默认值：

| 能力 | 当前默认 |
| --- | --- |
| 秒杀三维入口流量保护 | 开启 |
| 点赞新写、点赞聚合 worker | 关闭 |
| Redis 热榜读取、刷新与重建 | 关闭 |
| 秒杀定时对账、对账自动补偿、启动回填 | 分别关闭 |
| 批量发券 worker、通知 worker | 关闭 |

这里的“对账自动补偿”不包括 Consumer 已确认的数据库库存耗尽或用户—券冲突。那两类永久失败会走即时精确补偿。点赞旧数据回填要求新写和聚合 worker 停止；秒杀启动回填与在线对账不能在同一进程同时启用。

### 1.7 项目级高频追问

1. **为什么没有拆成微服务？**
   - 当前重点是业务一致性和安全边界。单应用部署降低分布式部署与事务成本；是否拆分应由独立扩缩容、团队所有权和真实容量证据驱动。

2. **MySQL 和 Redis 谁是真相？**
   - 不能按产品名一刀切。订单、点赞关系和权益以 MySQL 为长期事实；普通缓存与热榜可重建；消费者会话和秒杀预约虽在 Redis，却分别是在线身份依赖和关键协议状态。

3. **项目如何处理跨组件一致性？**
   - 根据事实最先落在哪里选择事务消息、Transactional Outbox、幂等重放、状态查询、补偿或 best-effort。当前实现提供恢复路径，但不代表全局强事务。

4. **怎样证明一项能力成立？**
   - 源码证明控制流，迁移证明约束定义，测试只证明具体 fixture 和断言。默认开关、外部依赖和实际部署状态还需单独确认。

5. **当前最大的局限是什么？**
   - 没有支付、退款或核销闭环；部分 worker 默认关闭；GEO 与 Feed 没有完整重建；通知不保证送达；没有从当前仓库得到生产容量、可用性或恢复点目标。

---

## 2. 重要补充模块：消费者验证码与 Redis 会话

### 2.1 模块定位与面试口述

消费者登录把验证码状态、登录建号和请求鉴权拆开。最值得讲的是验证码签发与消费都在 Redis 内原子迁移，而公开接口上的“无会话”和“无法确认会话”采用不同策略。

> 我没有用服务器内存 HttpSession，也没有把 token 做成自包含 JWT。申请验证码时，Redis 会原子建立验证码、发送冷却和失败预算；登录时再原子校验并一次性消费。成功后查询或创建 MySQL 用户，生成随机 token，把裁剪后的用户快照存入 Redis。后续请求先恢复身份并续期，再由第二个拦截器判断该 method 和 path 是否要求登录。正常没有会话时，公开接口可以匿名进入；客户端带了 token 但 Redis 故障时，系统无法确认身份，会失败关闭。这个方案便于主动登出和滑动续期，代价是每次认证依赖 Redis，而且验证码消费与建用户、写会话之间不是共同事务。

JWT 是 JSON Web Token，一种可由服务端验签而不必在线查询会话的凭证。当前项目选择随机 Redis token，是撤销能力与无状态读取之间的具体取舍，不表示它在所有场景都优于 JWT。

### 2.2 端到端流程

1. 入口接收手机号，先执行空值和格式校验。
2. 服务端生成六位数字验证码。
3. 签发脚本检查冷却状态；成功时同时写验证码、冷却状态并清理上一轮失败次数。
4. 登录入口接收手机号与六位验证码；消费脚本比较验证码、累计错误次数，并在成功时删除验证码和失败记录。
5. 验证码成功消费后，服务查询已有用户；不存在时创建用户。
6. 服务生成去连字符的 32 位随机 UUID 十六进制 token，并将裁剪后的 UserDTO 非空字段写入 Redis Hash，再单独设置会话 TTL。
7. 后续请求先由 order 0 的身份恢复拦截器读取 raw authorization 头、恢复用户并刷新 TTL。
8. order 1 的登录拦截器按 method 与 path 判断是否必须登录；消费者链整体排除后台路径，请求结束时清理 ThreadLocal 用户上下文。

### 2.3 原子协议、会话参数与设计边界

| 项目 | 当前实现 | 解决的问题 | 不能解决的问题 |
| --- | --- | --- | --- |
| 验证码有效期 | 默认 2 分钟 | 限制验证码暴露窗口 | 不证明短信已经送达 |
| 发送冷却 | 默认 60 秒 | 限制同手机号重复签发 | 不是完整反滥用系统 |
| 失败预算 | 默认 5 次 | 限制同一验证码暴力尝试 | 不覆盖跨手机号攻击 |
| 签发 Lua | 冷却不存在时才一起写 code、cooldown 并删旧 failure | 避免并发请求都越过冷却判断 | 不会让短信发送和 Redis 成为一个事务 |
| 消费 Lua | 比较、计数、锁定和成功消费一次完成 | 防止同一验证码并发成功两次 | 不覆盖后续 MySQL 与 session 写入 |
| 消费返回 | 1 为成功，0 为普通失败，2 为锁定 | 内部精确处理，外部保持统一失败语义 | 不向客户端暴露具体验证码状态 |
| 消费者会话 | Redis Hash，默认 30 分钟滑动 TTL，无绝对最长时限 | 多实例共享、主动删除、按访问续期 | Redis 故障时不能离线验证 |

验证码输错时，failure 的 TTL 跟随 code 的剩余 TTL。达到第 5 次错误会删除 code；正确时 code 与 failure 一起删除。这里的原子性只覆盖一次 Redis Lua 执行，不等于 Redis 和 MySQL 共同回滚。

登录写会话的 Hash 写入与 expire 是两个 Redis 命令，不是原子提交；两步之间失败可能留下没有 TTL 的 Hash。当前登录与续期时长在实现中使用 30 分钟，不能用未参与该路径的常量或配置推导其他期限。

手机号校验还存在一个源码级缺口：当前正则的字符类 5[0-3,5-9] 意外包含逗号。它只能视为当前格式实现，不能表述成运营商级号码真实性校验。

### 2.4 异常与状态边界

| 现场 | 当前事实 | 处理 | 重试边界 |
| --- | --- | --- | --- |
| 冷却期内重复申请 | 已有签发处于冷却窗口 | 拒绝且不改 code 和 failure | 冷却结束后重新申请 |
| 验证码错误或过期 | 身份尚未成立 | 返回统一校验失败；错误受失败预算约束 | 可在预算内重试或重新申请 |
| 验证码已消费，建用户或写 session 失败 | 验证码成功资格已被消费，但会话未成立 | 不恢复验证码，用户重新申请 | 不能假装是跨存储事务 |
| session Hash 已写、TTL 设置失败 | 会话内容可能存在但没有预期过期时间 | 当前无原子回滚 | 需按 Redis 事实检查并清理 |
| 没带 token 或 Redis 正常 MISS | 当前没有可用会话 | 公开接口匿名继续，受保护接口 401 | 登录后重试 |
| 带 token 但 Redis 访问异常 | 无法判断 token 真伪 | 失败关闭，当前映射 503 | 依赖恢复后重试 |
| 请求线程结束 | ThreadLocal 可能仍持有用户 | afterCompletion 强制清理 | 不是长期会话状态 |

消费者 token 与后台 token 都是随机的 32 位无连字符 UUID 字符串，不能说“格式不同”。二者真正分离在请求头规则、Redis 命名空间、上下文对象和拦截器链：消费者使用 raw authorization；后台要求 Bearer。

### 2.5 源码、配置和测试边界

| 主题 | 入口 |
| --- | --- |
| 验证码与登录 | [UserServiceImpl](../../src/main/java/com/localdeals/service/impl/UserServiceImpl.java)、[签发脚本](../../src/main/resources/lua/issue_login_code.lua)、[消费脚本](../../src/main/resources/lua/consume_login_code.lua) |
| 请求恢复与授权 | [RefreshTokenInterceptor](../../src/main/java/com/localdeals/interctptor/RefreshTokenInterceptor.java)、[LoginInterceptor](../../src/main/java/com/localdeals/interctptor/LoginInterceptor.java)、[WebConfig](../../src/main/java/com/localdeals/config/WebConfig.java) |
| 格式与常量 | [RegexPatterns](../../src/main/java/com/localdeals/utils/RegexPatterns.java)、[RedisConstants](../../src/main/java/com/localdeals/utils/RedisConstants.java) |
| 局部验证 | [UserServiceImplTest](../../src/test/java/com/localdeals/service/impl/UserServiceImplTest.java)、[UserServiceIT](../../src/test/java/com/localdeals/service/UserServiceIT.java)、[拦截器测试](../../src/test/java/com/localdeals/interctptor/) |

当前没有接入真实短信供应商。验证码日志默认关闭，只适用于显式开启的隔离开发环境。现有测试不能证明短信送达、真实攻击防护能力或多机生产会话可用性。

---

## 3. 重要补充模块：按查询语义设计读取与回退

### 3.1 模块定位与面试口述

店铺详情、附近店铺和全文搜索不是同一种读取问题。该模块的重点是让每种索引只承担适合它的职责，并在依赖异常时避免无界回源或语义错误的“降级”。

> 我把详情、地理查询和全文搜索分开设计。详情使用 Cache Aside，也就是先读缓存、未命中再查事实，并用空值缓存、SingleFlight 和数据库并发舱控制穿透与回源；附近店铺由 Redis GEO 找半径候选和距离，再回 MySQL 补完整数据；店铺全文搜索由 ES 排序后返回 ID，再回库读取事实。缓存损坏可以受控回源，但 GEO 空结果当前会退成普通分类分页，这不是等价结果；ES 失败也不会自动打开无界 LIKE。当前 GEO 代码没有显式声明距离升序，因此我只说它完成了半径候选和顺序保持，不声称严格“最近优先”已经验证。

Cache Aside 是应用先查缓存，未命中时从事实源加载并尽力回写的模式。

### 3.2 四类读取路径对比

| 场景 | 候选或加速层 | 事实补全 | 故障策略 | 当前边界 |
| --- | --- | --- | --- | --- |
| 店铺详情、店铺分类 | Redis 普通缓存 | MISS 时查 MySQL | DB_READ 并发舱内回源，写回尽力而为 | 只保护接入 CacheClient 的回源 |
| 附近店铺 | Redis GEO | 按候选 ID 回 MySQL 并恢复顺序 | 空结果当前退普通分类页 | 回退不保留半径和距离语义 |
| 店铺全文搜索 | ES | 按 ES ID 回 MySQL 并恢复顺序 | SEARCH 并发舱；ES 失败明确报错 | 未严格检查回库数量等于 ES ID 数 |
| Blog 全文搜索 | ES | 当前直接返回 BlogDoc | SEARCH 并发舱；不自动 LIKE | 不补最新作者和当前用户点赞状态 |

### 3.3 详情缓存：空值、SingleFlight 与并发舱

~~~text
读取详情缓存
  ├─ 合法对象：直接返回
  ├─ 合法空占位：返回不存在
  └─ MISS、坏 JSON、对象 ID 不匹配或 Redis 异常
         ↓
     同 JVM、同资源、同 key 的 SingleFlight
         ↓
     DB_READ 并发舱
         ↓
     查询 MySQL，并尽力回写对象或空占位
~~~

| 机制 | 当前事实 | 解决的问题 | 不能解决的问题 |
| --- | --- | --- | --- |
| 空值缓存 | 店铺对象与空占位默认均为 30 秒 | 减少热点无效 ID 反复穿透 | 不能证明记录永远不存在 |
| 分类缓存 | 非空列表默认 100 分钟，空列表 30 秒 | 降低稳定目录读取 | 只校验成员 ID 合法且不重复，不替代数据库 |
| SingleFlight | follower 默认最多等 750 毫秒；超时不取消 leader | 合并单实例同 key 的并发加载 | 不合并不同 key，也不是跨实例锁 |
| DB_READ | 单实例默认 4 个许可、最多等待 20 毫秒 | 限制缓存故障时的数据库并发 | 不是全系统或集群数据库上限 |
| SEARCH | 与 DB_READ 独立，默认也是 4 个许可、20 毫秒 | 隔离显式搜索压力 | 不保护所有普通查询 |

拿不到许可时返回 429；依赖调用失败或等待中断当前映射 503。Redis 读失败时允许在 DB_READ 保护下查 MySQL，并跳过对故障 Redis 的写回；写回失败不覆盖已经得到的数据库结果。

CacheClient 还保留逻辑过期和互斥锁方法，但它们不是当前店铺详情主路径，不能当作现行方案口述。

### 3.4 GEO：候选顺序不等于完整事实

附近查询先看排序语义。sortBy 为 comments 或 score 时，即使请求带经纬度也直接按 MySQL 排序并忽略坐标；没有完整经纬度时同样走普通数据库分页。只有其他排序或默认排序且坐标齐全时，才按分类访问 5 公里 GEO。Redis 返回店铺 ID、距离和顺序；MySQL 的 IN 查询不保证输入顺序，所以后续通过 ORDER BY FIELD 恢复 Redis 顺序。

当前调用包含 includeDistance 和 limit，但没有显式调用 sortAscending。因而可以确认半径过滤、距离返回与后续顺序保持，不能确认结果严格按距离升序。

GEO 返回 null 或空集合时，代码会走普通 MySQL 分类分页。这一分支无法区分“半径内确实无店”和“索引没有构建”，也不再包含半径和距离语义。当前没有默认运行的 GEO 全量重建闭环，也没有专项 GEO 测试。Redis 异常在此路径没有显式映射为 503，当前可能由统一异常处理返回 500。

### 3.5 ES 搜索与同步边界

店铺搜索在 ES 中做全文匹配、精确过滤和可选地理排序，再取 ID 回 MySQL 补完整行并恢复 ES 顺序。回库减少了把搜索文档当业务事实的风险，但当前没有严格校验所有 ES ID 都映射到 MySQL 行；索引刚好陈旧时可能返回不完整列表。

Blog 搜索当前直接返回 BlogDoc。它不会回库补充最新 Blog、作者资料或当前用户点赞状态，因此不能套用店铺搜索的事实边界。

显式的 MySQL 名称 LIKE 是另一条受 SEARCH 保护的接口，不是 ES 的自动 fallback。二者在分词、过滤、地理语义和数据库压力上都不等价。

~~~text
MySQL 店铺或 Blog 变化
        ↓ binlog
      Canal
        ↓
    RocketMQ
        ↓
 EsSyncConsumer
        ↓
按固定业务 ID upsert 或 delete ES 文档
~~~

固定文档 ID 让重复消息覆盖同一文档。消息 JSON 无法解析、目标表消息没有数据、目标行缺 ID 或 ES 写入失败时会抛出异常供 MQ 重试；已完成的前序行可能在整条消息重试时再次执行，但固定 ID 使重复 upsert 或 delete 收敛。DDL 或无关表会被忽略并确认。当前除 DELETE 外的未知操作也会进入 upsert 分支，只在指标中记为 OTHER，这是需要上游消息契约约束的边界。

ShopIndexInitializer 在非测试启动时从 MySQL 全量写 shop_index。它只覆盖店铺、不分页、不经过请求 SEARCH 并发舱，失败还可能阻止应用启动。ES 文档没有普通缓存 TTL；同步链停止时会保留最后一次应用的旧数据。

### 3.6 典型异常与口述边界

| 现场 | 可确认事实 | 当前处理 | 不应声称 |
| --- | --- | --- | --- |
| 缓存坏值或 ID 不匹配 | 缓存不可信 | 受控回源并尝试修复 | 坏缓存仍可直接返回 |
| Redis 详情读取异常 | 不知道缓存值 | DB_READ 内查 MySQL，跳过写回 | 所有数据库查询都受保护 |
| SingleFlight follower 超时 | leader 可能仍在加载 | follower 失败，leader 不被取消 | 请求超时会停止数据库工作 |
| GEO 空结果 | 无法区分合法空与未构建 | 当前退普通分类分页 | 返回仍是“附近店铺”语义 |
| ES 不可用 | 搜索索引不可查询 | 明确失败，不自动 LIKE | 已实现等价降级 |
| ES 与 MySQL 数量不一致 | 索引或事实正在变化 | 店铺回库可能返回较少数据 | 当前会自动识别并重建 |
| 同步消息部分行已应用后失败 | ES 可能已有部分新文档 | 整条消息重试，固定 ID 收敛 | MQ 与 ES 是共同事务 |

### 3.7 源码、配置和测试边界

| 主题 | 入口 |
| --- | --- |
| 缓存与合并加载 | [CacheClient](../../src/main/java/com/localdeals/utils/CacheClient.java)、[SingleFlightLoader](../../src/main/java/com/localdeals/utils/SingleFlightLoader.java) |
| 并发舱 | [LocalReadBulkhead](../../src/main/java/com/localdeals/service/LocalReadBulkhead.java)、[BoundedCacheProperties](../../src/main/java/com/localdeals/config/BoundedCacheProperties.java) |
| 详情、GEO、店铺搜索 | [ShopServiceImpl](../../src/main/java/com/localdeals/service/impl/ShopServiceImpl.java) |
| Blog 搜索 | [BlogServiceImpl](../../src/main/java/com/localdeals/service/impl/BlogServiceImpl.java) |
| 索引写入 | [EsSyncConsumer](../../src/main/java/com/localdeals/mq/EsSyncConsumer.java)、[ShopIndexInitializer](../../src/main/java/com/localdeals/init/ShopIndexInitializer.java) |
| 配置 | [application.yaml](../../src/main/resources/application.yaml) |
| 局部验证 | [CacheClientTest](../../src/test/java/com/localdeals/utils/CacheClientTest.java)、[BoundedCacheMySqlRedisIT](../../src/test/java/com/localdeals/service/BoundedCacheMySqlRedisIT.java)、[ShopServiceIT](../../src/test/java/com/localdeals/service/ShopServiceIT.java)、[CanalSyncIT](../../src/test/java/com/localdeals/mq/CanalSyncIT.java) |

CanalSyncIT 直接调用同步 Consumer，并未经过真实 Canal Server 或 RocketMQ。当前证据不能证明完整变更数据捕获链、生产同步时延或缓存故障容量。

---

## 4. 重要补充模块：内容事实、图片归属与关注流

### 4.1 模块定位与面试口述

内容发布需要保证正文和图片归属共同成立；粉丝 Feed 只要求在事实提交后尽力扩散。该模块最值得讲的是“事务内事实”和“事务后派生展示”的明确分界。

> 我把 Blog 和图片归属放在同一个 MySQL 事务中。上传后的图片先是属于当前用户的 TEMP 临时资源；发布时校验最多 9 张、不重复且都归当前用户，再插 Blog，并用条件更新把每张图转成 PUBLISHED。提交成功后才尝试更新热榜和向粉丝 Feed 扩散，避免数据库回滚但 Redis 已经展示内容。Feed 使用推模式和时间游标，普通用户读取成本较低，但 after-commit 回调不是持久待办：提交后进程退出、单个粉丝写入失败或大 V 写放大，目前都没有自动补发闭环。

### 4.2 图片生命周期

上传同时校验大小、扩展名、Content-Type 和文件头签名。当前只接受 JPG、JPEG、PNG，默认最大 5 MB。服务端生成 UUID 文件名与两级分片目录，不采用客户端文件名。

文件写入前会规范化根目录，并验证真实父目录仍位于配置根目录下，减少目录穿越与已有符号链接绕过。文件落盘后才插入 ownerUserId、路径和 TEMP 状态；记录失败时会尝试删除文件。

删除流程先用条件更新抢占 TEMP → DELETING，再删除文件和数据库记录。删除文件或后续数据库操作抛错时会尝试退回 TEMP。完成删除记录的影响行数当前没有校验，零行不会触发该回退。文件系统与 MySQL 也不共享事务。

### 4.3 Blog 发布主链路

1. 请求身份从 UserHolder 取得，忽略客户端自报作者。
2. 图片路径被规范化；最多 9 张且不能重复。
3. 每张图片必须是当前用户拥有的 TEMP 记录。
4. MySQL 插入 Blog。
5. 每张图片按 path、ownerUserId 和原状态条件更新为 PUBLISHED，并关联 blogId；任一影响行数不是 1，整个事务回滚。
6. 事务内查询当前作者的 follower IDs，并注册提交后回调。
7. 数据库提交成功后，回调尝试加入已就绪热榜并向各粉丝 Feed 写 blogId。
8. Feed 查询按 Redis 顺序批量回查 MySQL，再补作者和当前用户点赞状态。

### 4.4 Feed 推模式与游标

每个粉丝的有序集合以 blogId 为成员、注册事务回调前取得的 Feed 排序时间戳为 score。当前每次最多读取 2 条，并携带上一页最小时间 max 和同分 offset 做倒序滚动。

当前 offset 只统计本批结果末尾相同 score 的数量。如果本页末尾仍与传入 max 同分，它没有把旧 offset 累加进去；同一毫秒的内容超过一页时可能重复读取。因此这里只能说明现有游标协议，不能声称同分场景已经完全避免重复或遗漏。

推模式把成本放在相对低频的发布侧，使读取只访问自己的收件箱。代价是高粉作者会产生写放大。当前没有冷热粉丝分层、推拉结合或持久消息扇出。

事务提交后回调只保证派生写不会早于 MySQL 提交。它没有持久待办：提交后、回调前崩溃，或某个粉丝 Redis 写失败时，只记录日志；Feed 丢失后也没有从 Blog 和关注关系自动重建的闭环。

### 4.5 关注关系与派生 Set

关注时先插入 MySQL 关系，再把 followUserId 加入 Redis Set；取关时先按双方 ID 删除 MySQL，再移除 Set。isFollow 查询 MySQL，“我的关注”和“共同关注”读取 Redis Set 后回查用户。

这组双写没有 Outbox 或共同事务，tb_follow 也没有 userId、followUserId 复合唯一约束。并发重复关注可能落下多行；MySQL 成功而 Redis 失败时，不同读接口也可能暂时矛盾。关注 CRUD 本身属于简单模块，复杂性主要来自派生 Feed 与跨存储边界。

### 4.6 异常与实现边界

| 现场 | 当前事实 | 当前处理 | 恢复边界 |
| --- | --- | --- | --- |
| 文件已写但 TEMP 记录失败 | 文件不是已登记业务资源 | 尝试删除文件 | 删除失败可能留下孤儿文件 |
| TEMP 删除中断 | 状态可能停在 DELETING | 异常时尝试退回 TEMP | 没有定时扫描闭环 |
| 任一图片不属本人或已发布 | 发布前置条件不成立 | Blog 事务整体拒绝或回滚 | 不允许部分图片成功 |
| Blog 已提交，after-commit 未执行 | 内容事实已经成立 | 当前没有持久补发 | 不能回滚 Blog 来掩盖 Feed 缺失 |
| 单个粉丝 Feed 写失败 | Blog 与关注事实仍存在 | 记录日志，其他写入继续 | 当前不会自动重放 |
| 关注 MySQL 成功、Redis Set 失败 | 关系事实与派生查询可能不一致 | 无自动 Outbox 收敛 | 以具体接口的数据源解释结果 |
| Feed key 丢失 | 不能推出没有关注内容 | 当前返回空 | 无历史重建闭环 |

当前也没有长期 TEMP、DELETING、孤儿文件或缺失文件的定时清理与对账。恢复前应同时保留文件与数据库归属证据。

### 4.7 源码与测试入口

| 主题 | 入口 |
| --- | --- |
| 上传与归属 | [UploadFileService](../../src/main/java/com/localdeals/service/UploadFileService.java)、[V3](../../src/main/resources/db/migration/V3__upload_file_ownership.sql)、[V4](../../src/main/resources/db/migration/V4__expand_upload_file_status.sql) |
| Blog 发布与 Feed | [BlogServiceImpl](../../src/main/java/com/localdeals/service/impl/BlogServiceImpl.java) |
| 关注关系 | [FollowServiceImpl](../../src/main/java/com/localdeals/service/impl/FollowServiceImpl.java) |
| 局部验证 | [UploadFileServiceIT](../../src/test/java/com/localdeals/service/UploadFileServiceIT.java)、[BlogServiceIT](../../src/test/java/com/localdeals/service/BlogServiceIT.java)、[BlogServiceAfterCommitTest](../../src/test/java/com/localdeals/service/impl/BlogServiceAfterCommitTest.java) |

现有测试没有证明 Feed 丢失后恢复、关注并发幂等或文件系统与数据库的自动对账。

---

## 5. 核心复杂链路：后台身份、RBAC、商户隔离与实时会话

### 5.1 模块定位

这条链解决“账号有功能权限，但只能操作自己商户的数据，并且长连接期间仍可能被撤权”的问题。它位于所有后台管理和后台实时通知之前，核心设计是把身份、权限、数据范围、资源归属和最终写条件分层验证。

### 5.2 30～60 秒面试概括版

> 后台的难点不只是登录，而是有功能权限的商户账号仍不能访问其他商户，长连接期间还可能发生停用或撤权。我把消费者和后台做成独立身份域；后台 Redis 会话只关联账号和 authVersion，每次请求都回 MySQL 重建当前账号、商户、角色和权限。具体业务继续校验 scope、资源归属和适用的数据库条件，所以 RBAC 通过不等于可以操作任意 ID。WebSocket 用 30 秒一次性票据代替 URL 中的长期 Bearer token，并在每次尝试发送前重验权限。依赖异常或归属不明时失败关闭；通知只做在线提示，不承诺送达，也不能靠撤销业务事实来“补偿”。

### 5.3 2～3 分钟完整口述版

> 后台同时服务平台账号和商户账号。最大的风险不是“没登录”，而是账号本来有管理店铺或营销的权限，却通过替换资源 ID 访问另一商户；WebSocket 连接还可能在握手后长期存在，期间账号可能被停用、改密或撤权。
>
> 我的整体方案分成 HTTP 身份、业务授权和长连接三个阶段。后台只接受 Bearer token，Redis 保存 accountId 与签发版本。每次请求都回库加载账号、商户、角色和权限，并比较 authVersion。接口上的权限注解只判断能否执行某类功能；平台或商户 scope 再限定数据范围；Service 验证店铺、券、活动之间的真实归属；最终查询或写入在适用表结构上继续携带商户或父资源条件。
>
> 关键机制有三组。登录使用 BCrypt，并对不存在的用户名计算 dummy hash，配合用户名加 IP 和单 IP 两层失败计数，降低枚举与暴力尝试。authVersion 让改密或停用后的旧 token 在下次使用时失效，而每次回库也能让角色权限变化生效。WebSocket 则由已认证 HTTP 请求签发一次性短票据，握手时原子消费；连接按平台通道或 merchantId 分区，跨实例只通过 Redis Pub/Sub 传播事件，真正发送前再次加载当前权限。
>
> 我没有把“所有 SQL 都带 merchantId”当成口号。有些资源直接按商户条件查询，有些写入通过已经按商户锁定的父店铺限定。正确性来自整条归属链，而不是某一个注解或对象关系映射（ORM）自动插件。身份无效返回 401，权限或 scope 不允许返回 403，当前范围内找不到资源通常返回 404；普通 session 解析中的部分依赖异常目前仍可能落到 500。发送前重验只在尝试发送时发生，空闲连接不会被即时踢下线。Pub/Sub 和 WebSocket 都不证明送达，最终结果仍查订单或 Grant。

### 5.4 端到端主链路

1. 后台登录接收用户名、密码和连接信息，解析受信任的客户端 IP。
2. 登录服务检查用户名加 IP 的失败预算和单 IP 总尝试预算。
3. MySQL 加载账号；BCrypt 验证密码，并检查账号、商户、角色和权限组合。
4. 登录成功后生成随机 token，Redis 保存 accountId 与当前 authVersion，并设置会话 TTL。
5. 后台 HTTP 请求携带 Bearer token；session 拦截器读取 Redis 后回 MySQL 重建当前 principal。
6. 授权拦截器读取方法或类上的权限要求，决定能否进入接口；没有权限声明的后台 Handler 默认拒绝。
7. 业务服务根据 principal 的平台或商户 scope 确定目标范围，并校验资源关系。
8. 查询或写入使用适用的商户条件、父资源关系或条件影响行数作为最后边界。
9. 需要后台长连接时，已认证请求申请 30 秒一次性 ticket；握手脚本读取并删除它，再次恢复后台身份。
10. 连接以 sessionId 登记，商户连接额外按 merchantId 分组；事件经 Pub/Sub 到达各实例，持有目标连接的实例在发送前再次检查会话、账号、scope 和实时权限。

### 5.5 核心机制与设计取舍

#### 机制一：消费者与后台身份域分离

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 防止消费者凭证被后台链误解，或后台身份污染消费者上下文 |
| 防止什么竞态或故障 | 拦截器重叠、Redis key 空间碰撞和 ThreadLocal 身份串用 |
| 为什么更直接的方案不够 | 一枚 token 加角色字段会让两套入口规则、撤权方式和上下文耦合；仅靠 URL 前缀也不能建立不同会话语义 |
| 本身不能解决什么 | 身份域分开不证明账号有权限，也不证明目标资源属于其商户 |

两个域的随机 token 字符串格式实际相同。隔离来自 Header 约定、Redis 命名空间、principal 类型和拦截器范围，而不是 token 长相。

#### 机制二：在线重建 principal 与 authVersion

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 让每个请求使用当前账号、商户、角色和权限，而不是长期相信登录时快照 |
| 防止什么竞态或故障 | 改密、停用后旧 token 继续使用；角色或权限变化长期不生效 |
| 为什么更直接的方案不够 | 只把权限写进 token 会延长撤权窗口；仅删除单个已知 token 不能覆盖账号的其他会话 |
| 本身不能解决什么 | 它增加每次请求的数据库依赖；空闲 WebSocket 也不会仅因版本变化被主动扫描和立即断开 |

改密或账号状态变化会推进 authVersion。角色权限变化即使不推进版本，也会因每次回库在下一次请求或发送尝试时生效。

后台 token 同样是 32 位无连字符随机 UUID，Redis session 默认 30 分钟并随有效 HTTP 请求续期。会话值只保存 accountId 与 issuedAuthVersion，不缓存完整权限集合。

#### 机制三：权限、scope、ownership 与最终写条件

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 把“能做哪类操作”和“能操作哪一条数据”分开 |
| 防止什么竞态或故障 | 商户替换资源 ID 越权；Controller 漏传范围；先查归属后资源变化 |
| 为什么更直接的方案不够 | 权限注解不知道资源归属；数据库外键只证明商户存在；前端隐藏菜单没有安全意义 |
| 本身不能解决什么 | 项目没有全局 ORM 租户插件；新增后台 SQL 仍需人工审计，且并非每张业务表都直接拥有 merchantId |

可将防线记成五问：是谁、能做什么、允许触达哪个范围、目标资源属于谁、最终数据库动作是否仍受该范围约束。第五层可能是直接 merchantId 条件，也可能是已经按商户锁定的父店铺或活动关系，不能机械描述成“所有 SQL 都带 merchantId”。

权限注解先看方法，再看 Controller 类。两处都没有声明的后台 Handler 默认返回 403；声明为空权限码只表示“需要有效后台身份”。项目没有全局 ORM 租户插件，因此新增接口仍需逐条审核权限与数据范围。

#### 机制四：登录防枚举与可信客户端 IP

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 控制后台密码尝试，并避免根据响应时间或文案枚举账号 |
| 防止什么竞态或故障 | 并发计数丢失、伪造转发头绕过 IP 限制、未知用户名快速返回造成时间差 |
| 为什么更直接的方案不够 | 只按用户名限制可被分散账号绕过；无条件相信 X-Forwarded-For 可由客户端伪造 |
| 本身不能解决什么 | 当前只是局部入口保护，不是验证码、设备风控或完整入侵检测系统 |

用户名规范化为小写，允许 4～64 位受限字符。登录密码输入最长 256 字符；新密码至少 12 位，并受 BCrypt 72 字节边界限制。默认用户名加 IP 最多失败 5 次，单 IP 在 15 分钟内最多尝试 30 次。成功登录只清用户名加 IP 的失败数，不提前清 IP 总计数。

#### 机制五：一次性后台票据与发送前重验

| 问题 | 回答 |
| --- | --- |
| 解决什么具体问题 | 避免长期后台 Bearer token 出现在 WebSocket URL，并缩短长连接使用旧权限的窗口 |
| 防止什么竞态或故障 | ticket 重放、握手后账号停用或撤权、向错误商户广播 |
| 为什么更直接的方案不够 | 只在握手校验无法覆盖连接生命周期；本机连接表无法独自处理多实例路由 |
| 本身不能解决什么 | Pub/Sub 不保存离线事件；发送成功不等于浏览器展示或用户已读；空闲连接不会即时触发重验 |

ticket 默认 30 秒且只能原子消费一次。后台握手与发送前 resolve 不刷新原 session TTL，所以仅保持 WebSocket 不能让后台会话永久有效。消费者 WebSocket 是另一条实现：URL 仍直接携带长期 consumer token，新连接替换同用户旧连接，发送前确认该 token 仍属于该用户。

四种凭证的边界不能混淆：

| 凭证 | 用途 | 当前约定 |
| --- | --- | --- |
| consumer raw token | 消费者 HTTP | authorization 头直接携带，Redis Hash 会话 |
| admin Bearer token | 后台 HTTP | Authorization 头使用 Bearer 方案，每次回库重建 principal |
| consumer WS token | 消费者 WebSocket | URL 查询参数携带长期消费者会话 token |
| admin WS ticket | 后台 WebSocket | 已认证 HTTP 请求换取，30 秒且一次性 |

两个 WebSocket 握手端点都使用显式 Origin 允许列表。registry 只存在当前 JVM，实例退出会丢失连接；Pub/Sub 只负责把事件扩散到各实例。单连接发送当前有 10 秒时限和 256 KiB 缓冲上限，用于限制慢连接影响，不是送达保证。负载均衡器还需正确支持 WebSocket Upgrade 与长连接超时。

### 5.6 异常分类与状态收敛

| 异常现场 | 当前能够确认的事实 | 处理方式 | 是否重试 | 是否允许补偿 |
| --- | --- | --- | --- | --- |
| 输入结构不合法或平台请求缺少目标 merchantId | 请求无法形成合法业务范围 | 返回 400 | 修正输入后 | 不适用 |
| 无 Bearer、token 缺失或已失效 | 没有可用后台身份 | 返回 401 | 重新登录后可重试 | 不适用 |
| 身份有效但缺权限或 scope 不允许 | 账号存在，但当前操作不被授权 | 返回 403 | 权限未变化前无意义 | 不适用 |
| 当前商户范围找不到目标资源 | 不能在该范围内证明资源存在 | 通常返回 404，避免泄露外商户资源 | 修正 ID 后可重试 | 不适用 |
| 账号版本或资源状态发生竞争变化 | 旧前提不再成立 | 部分写路径返回 409 | 重新读取后决定 | 不适用 |
| 登录尝试超过预算 | 入口风险过高 | 返回 429，等待窗口 | 窗口后重试 | 不适用 |
| 登录时 Redis 限流依赖故障 | 无法安全执行失败预算 | 明确失败关闭，当前映射 503 | 依赖恢复后重试 | 不适用 |
| 普通后台 session 的 Redis 或 MySQL 异常 | 无法确认身份当前有效 | 拒绝请求；部分路径由统一异常处理落到 500 | 可重试 | 不适用 |
| Redis 会话值格式损坏 | 会话不可安全解析 | 删除损坏值，不构造 principal | 重新登录 | 不适用 |
| ticket 过期、重复使用或依赖异常 | 不能证明本次握手持有有效短票据 | 拒绝握手，当前为 401 | 重新申请 ticket | 不适用 |
| WebSocket 身份有效但缺实时权限或 scope 非法 | 连接不满足授权要求 | 拒绝握手，当前为 403 | 权限修复后重连 | 不适用 |
| 发送前发现账号、scope 或权限已变化 | 连接身份已不再满足发送条件 | 不发送并关闭连接 | 恢复权限并新建连接 | 不适用 |
| 秒杀商户事件无法解析 merchantId | 用户与平台频道已经先尝试，但无法确认目标商户 | 跳过商户频道，不退化成全商户广播 | 当前不自动重试；只能回查事实，除非外部重新触发 | 不适用 |
| 秒杀 Pub/Sub 或 WebSocket 发送失败 | 订单事实可能已成立，只是提示失败 | 记录失败或关闭连接；客户端查订单 | 当前无持久补发 | 不允许回滚订单 |
| Grant 在 Redis publish 前失败 | 权益与通知 Outbox 已成立 | Outbox 保持 PENDING 并退避 | worker 开启时自动重试 | 不允许回滚 Grant |
| Grant 已 publish，但 Pub/Sub 或 WebSocket 下游丢失 | PUBLISHED 也不能证明客户端收到 | 当前无 ACK、离线补发或送达修复 | 客户端回查券包 | 不允许回滚 Grant |

这条安全链没有“先扣一份业务资源、失败后归还”的动作，因此不应套用库存式补偿。认证或归属证据不足时，应拒绝、关闭或重新登录；业务事实已经成立但通知失败时，也不能通过撤销订单或 Grant 来补偿。

### 5.7 状态、事实和不变量

这里至少有五组相互独立的状态：

| 状态集合 | 保存位置 | 含义 |
| --- | --- | --- |
| 账号、商户、角色、权限和资源归属 | MySQL | 后台安全的当前事实 |
| authVersion | MySQL 账号字段 | 凭据代际，不是账号业务状态 |
| 后台 session | Redis | token 到 accountId、issuedAuthVersion 的在线会话 |
| WebSocket ticket | Redis | 只保存尚未消费且未过期的短期票据；key 缺失无法区分已消费、已过期或从未存在 |
| socket registry | 当前 JVM | 以 sessionId 登记；商户连接再按 merchantId 分组 |

它们不是一条连续状态机。ticket 被消费不表示 socket 一定建立；socket 已连接不表示 session 永远有效；Pub/Sub 发布也不是连接状态。

系统需要长期守住的不变量：

1. 消费者凭证不能恢复为后台 principal，后台凭证也不能恢复为消费者身份。
2. 没有有效后台身份的请求不能进入后台业务。
3. 功能权限不能替代商户范围和资源归属判断。
4. 商户账号不能通过替换资源 ID 触达其他商户数据。
5. 没有权限声明的后台 Handler 默认拒绝。
6. ticket 最多成功消费一次。
7. 无法确认 merchantId 的事件不能退化成更宽范围广播。
8. 在线提示失败不能覆盖或撤销已经成立的订单、权益等事实。

### 5.8 面试官最可能追问的 5 个问题

1. **RBAC 为什么不能单独防止越权？**
   - RBAC 只回答功能权限。资源 ID 的商户归属还要由 scope、Service 关系校验和最终数据库条件共同约束。

2. **怎样阻止商户 A 修改商户 B 的店铺或券？**
   - principal 给出 A 的范围；接口权限只放行功能；资源查询在 A 的范围内定位；写入继续依赖带范围的资源或已锁定父资源；影响行数不符合预期就不能返回成功。

3. **为什么后台 token 每次请求还要回 MySQL？**
   - 为了使用当前账号、商户、角色和权限，并缩短撤权窗口。代价是多一次在线数据库依赖，不应描述为无成本。

4. **为什么后台 WebSocket 使用一次性 ticket？**
   - 避免长期 Bearer token 暴露在 URL；原子消费防重放；发送前重验覆盖握手后的撤权。它不提供离线消息或送达保证。

5. **权限变化后已有连接会立刻断开吗？**
   - 不会做全量主动扫描。当前是在下一次尝试发送时重新 resolve 并关闭不再合格的连接；空闲连接可能暂时仍保持打开。

### 5.9 源码、配置、迁移与测试边界

| 主题 | 入口 |
| --- | --- |
| HTTP 身份与授权 | [AdminSessionInterceptor](../../src/main/java/com/localdeals/interctptor/AdminSessionInterceptor.java)、[AdminAuthorizationInterceptor](../../src/main/java/com/localdeals/interctptor/AdminAuthorizationInterceptor.java)、[RequireAdminPermission](../../src/main/java/com/localdeals/auth/RequireAdminPermission.java) |
| 登录和会话 | [AdminAuthService](../../src/main/java/com/localdeals/service/AdminAuthService.java)、[AdminSessionService](../../src/main/java/com/localdeals/service/AdminSessionService.java)、[登录失败脚本](../../src/main/resources/lua/record_admin_login_failure.lua) |
| 商户归属 | [AdminCatalogService](../../src/main/java/com/localdeals/service/AdminCatalogService.java)、[MarketingAdminService](../../src/main/java/com/localdeals/service/MarketingAdminService.java) |
| WebSocket | [WebSocketConfig](../../src/main/java/com/localdeals/config/WebSocketConfig.java)、[WebSocketProperties](../../src/main/java/com/localdeals/config/WebSocketProperties.java)、[AdminWebSocketAuthInterceptor](../../src/main/java/com/localdeals/websocket/AdminWebSocketAuthInterceptor.java)、[SeckillWebSocketHandler](../../src/main/java/com/localdeals/websocket/SeckillWebSocketHandler.java)、[ticket 脚本](../../src/main/resources/lua/consume_admin_ws_ticket.lua)、[WebSocketNotifier](../../src/main/java/com/localdeals/websocket/WebSocketNotifier.java) |
| 数据定义 | [V5](../../src/main/resources/db/migration/V5__merchant_admin_rbac.sql)、[V6](../../src/main/resources/db/migration/V6__admin_auth_version.sql)、[application.yaml](../../src/main/resources/application.yaml) |
| 局部验证 | [AdminMvcSecurityTest](../../src/test/java/com/localdeals/controller/AdminMvcSecurityTest.java)、[AdminRbacIT](../../src/test/java/com/localdeals/service/AdminRbacIT.java)、[AdminWebSocketAuthInterceptorTest](../../src/test/java/com/localdeals/websocket/AdminWebSocketAuthInterceptorTest.java)、[WebSocketSessionIsolationTest](../../src/test/java/com/localdeals/websocket/WebSocketSessionIsolationTest.java) |

测试覆盖了给定账号、权限、商户和连接 fixture 下的局部行为。它不能证明公网代理配置正确、所有新增 SQL 都已通过租户审计、多实例通知必达或撤权达到某个生产时延。

后台 bootstrap 是仅创建且失败关闭的初始化能力，配置为空时不存在默认管理账号或默认密码。不能把本地显式提供的引导凭据写进面试材料或生产配置。

---

## 6. 简单模块：一段话能够讲清的部分

| 模块 | 当前实现 | 面试边界与源码入口 |
| --- | --- | --- |
| 当前用户、登出与公开资料 | me 读取当前上下文；logout 删除 Redis session；按用户 ID 返回裁剪的 UserDTO，UserInfo 查询会隐藏创建与更新时间，缺失时可返回成功空值 | 属于会话周边和薄查询，不单独包装复杂状态机；见 [UserController](../../src/main/java/com/localdeals/controller/UserController.java) |
| 店铺类型与普通店铺读取 | 类型列表走 §3 的列表缓存；无地理语义的店铺列表为普通 MySQL 查询 | 复杂点在通用缓存与搜索模块，不重复讲 CRUD；见 [ShopTypeServiceImpl](../../src/main/java/com/localdeals/service/impl/ShopTypeServiceImpl.java)、[ShopServiceImpl](../../src/main/java/com/localdeals/service/impl/ShopServiceImpl.java) |
| 店铺上架券列表 | 查询 status=1 的上架券，并通过 LEFT JOIN 按需带出秒杀券扩展字段 | 它既可能包含普通券也可能包含秒杀券，且没有按活动时间再判“有效”；不等于领券、发券或秒杀落单。见 [VoucherServiceImpl](../../src/main/java/com/localdeals/service/impl/VoucherServiceImpl.java)、[VoucherMapper.xml](../../src/main/resources/mapper/VoucherMapper.xml) |
| 关注与取关 CRUD | MySQL 关系加 Redis Set 派生查询 | 单次 CRUD 简单，双写与 Feed 边界已在 §4 集中说明 |
| 每日签到和连续天数 | MySQL 保存 userId 与业务日唯一事实并支持连续签到查询 | 签到只形成资格事实，不会自动发券；用户还需单独调用 task-reward 领取，Grant 事务会再次校验当日签到。见 [09 §4](09-core-business-chain-review.md#4-营销发券多入口共用一笔权益事务) |
| Blog 评论 | Controller 当前为空，Service 只是 MyBatis-Plus 基础实现 | 这是代码骨架，不应口述成已完成评论业务链；见 [BlogCommentsController](../../src/main/java/com/localdeals/controller/BlogCommentsController.java) |

订单表虽然有订单状态字段，当前项目仍没有支付、支付超时取消、退款和核销业务闭环。

---

## 7. 横向判断、故障排查与恢复

### 7.1 同名技术在不同链路中的责任

| 机制 | 当前用途 | 不能提供的保证 |
| --- | --- | --- |
| Redis Lua | 在同一个 Redis 内原子检查并迁移状态 | 不能让 Redis、MySQL 和 MQ 一起回滚 |
| MySQL 行锁 | 稳定活动规则、额度竞争或待办批次 | 不能协调数据库外部组件 |
| Redisson 锁 | 减少多实例对同一业务并发操作 | 不能替代唯一约束、条件更新或版本栅栏 |
| JVM bulkhead | 限制单实例某类依赖并发 | 不是集群总限流，也不覆盖所有 SQL |
| generation fence | generation 是构建代际；栅栏拒绝旧热榜构建器晚到发布 | 不能证明 MySQL 点赞聚合本身完全正确 |

### 7.2 六种异常动作

| 动作 | 适用条件 |
| --- | --- |
| 重试 | 相同动作重放可能成功，而且副作用可幂等收敛 |
| 补偿 | 能证明当前操作拥有被撤销的资源，并能确认长期事实没有成立 |
| 回退 | 存在语义正确、压力受控的权威读取路径 |
| 失败关闭 | 身份、资格或归属无法安全判断 |
| best-effort | 失败不能反向撤销业务事实，例如普通缓存写回和在线通知 |
| 隔离 | 证据互相冲突时停止自动动作并保留现场 |

某一步抛异常只证明调用方没有得到确定结果，不证明远端没有执行。尤其是数据库查询失败、Redis 响应丢失和通知未到，都不能直接解释为业务事实不存在。

### 7.3 排查顺序

1. 确认身份域和业务标识：userId、accountId、merchantId、voucherId、orderId、requestId 或 idempotencyKey。
2. 同时记录 HTTP status 与统一响应体中的 success、code、data；HTTP 200 也可能是业务拒绝。
3. 先查 MySQL 长期事实。查询异常不能当作“不存在”。
4. 再查 session、秒杀预约、PROCESSING、Outbox、Job/Item 等协议或持久进度。
5. 最后检查普通缓存、GEO、ES、热榜、Feed、Pub/Sub 和 WebSocket 等派生层。
6. 检查 MQ retry 或 DLQ、待办数量、最老年龄、失败分类与配置门禁。
7. 执行恢复前写清重试幂等性、补偿所有权和隔离条件。

| 现象 | 第一事实检查 | 下一步 | 不应直接做 |
| --- | --- | --- | --- |
| 后台突然 401、403 或 500 | token、账号、authVersion、权限和商户状态 | 区分缺身份、缺权限与 session 依赖异常 | 绕过拦截器放行 |
| 商户看到外商户数据 | 账号范围和目标资源归属 | 审核 Service 关系与最终 SQL 条件 | 只隐藏前端菜单 |
| 店铺详情导致数据库压力 | MySQL 和连接池 | 缓存状态、SingleFlight、DB_READ 拒绝 | 无界增加线程或等待 |
| 附近店铺为空 | MySQL 分类和坐标 | GEO key、成员、半径与非等价回退 | 当成严格附近结果 |
| 搜索结果旧 | MySQL 当前行 | ES 文档、同步消息和 Consumer | 把 ES 当写入事实 |
| 关注流漏内容 | MySQL Blog 与关注关系 | after-commit 日志与目标 Feed | 声称当前一定会补发 |
| 秒杀长期处理中 | orderId 对应 MySQL 订单 | 预约、状态、due、quarantine 和 MQ | 查询失败时补库存 |
| 点赞数或热榜异常 | 点赞关系和 MySQL 聚合 | 精确 Outbox 事件和榜单 generation | 直接手改 ZSET 当修复 |
| 发券通知未收到 | MySQL Grant | 通知 Outbox、Pub/Sub 和在线连接 | 撤销 Grant 或把 PUBLISHED 当已读 |

### 7.4 后台任务术语与恢复完成标准

| 术语 | 准确定义 |
| --- | --- |
| Scheduler | 按时间触发一次尝试，本身不保存业务完成事实 |
| Worker | 每轮取得有限待办并执行 |
| Job | 一项持久的整体批量任务 |
| Item | Job 中可独立分类的最小目标 |
| State machine | 规定一组状态与允许的迁移 |
| Reconciler | 正常链未及时到终态时，根据事实重新分类并安全推进 |

有限批次用于限制锁范围、事务时长、内存和回滚成本，不自动等于高吞吐。恢复完成也不能只看进程存活、HTTP 200 或队列 lag 为零：

- 身份问题要确认当前凭证、账号状态、permission、scope 和资源归属共同成立。
- 读取问题要确认返回语义正确，派生索引可以继续收敛，而不只是异常消失。
- 核心业务要回到订单、点赞关系或 Grant 等长期事实和不变量。
- 自动处理停在隔离区时，应保留证据供人工判断，不能为了“状态结束”伪造终态。

### 7.5 可观测性只能提供线索

管理端口默认监听 127.0.0.1:18084，只暴露 health 与 Prometheus 指标。liveness 只检查进程 ping，readiness 组合数据库与 Redis 健康。可靠性积压采样默认关闭；采集失败时部分 gauge 会记录 NaN，表示本轮未知，而不是零积压。指标标签使用有限枚举，避免把 orderId、userId 等高基数业务值放入标签。

健康检查通过、平均延迟正常或积压数为零，都不能替代业务事实验证。指标适合判断范围、趋势和最老待办年龄，最终仍需回到订单、点赞关系、Grant 及其不变量。实现入口见 [LocalDealsMetrics](../../src/main/java/com/localdeals/observability/LocalDealsMetrics.java)、[ReliabilityBacklogCollector](../../src/main/java/com/localdeals/observability/ReliabilityBacklogCollector.java) 和 [application.yaml](../../src/main/resources/application.yaml)。

---

## 8. 旧内容迁移、权威版本与源码反查

### 8.1 旧章节到新章节的迁移映射

| 旧 08 内容 | 新位置 | 处理 |
| --- | --- | --- |
| 项目概括、完整版、详细边界与默认开关 | §1 | 合并重复叙述，保留口述、数据地图、迁移和门禁 |
| 消费者登录的概括、完整版和详细链路 | §2 | 按流程、原子协议、异常和证据重组 |
| 缓存、GEO、ES 与同步 | §3 | 先按查询语义对比，再集中说明非等价回退 |
| 图片、Blog 发布、Feed 与关注双写 | §4 | 拆开长期事实、文件状态和派生扩散 |
| 后台安全与 WebSocket | §5 | 提升为核心复杂链路并采用统一八段式 |
| 三条核心业务导航 | §0 与 §8.2 | 保留链接，不复制主链 |
| 技术职责与异常策略 | §7.1～§7.2 | 去重后保留 |
| 排障矩阵与后台任务解释 | §7.3～§7.4 | 保留独有运维信息 |
| 实现边界、源码反查与自检 | §0、各模块索引、§8 | 分散到最接近事实的位置 |

本次重组还明确修正了四类旧表述：

| 旧表述风险 | 新边界 |
| --- | --- |
| 把所有秒杀补偿都说成默认关闭 | 只有定时对账中的自动补偿受该门禁；Consumer 的两类明确永久失败可即时精确补偿 |
| 把消费者和后台说成不同 token 格式 | 字符串格式相同，身份域由协议、命名空间、上下文与拦截器分开 |
| 把第五层隔离说成所有 SQL 都直接带 merchantId | 最终约束可以是商户条件，也可以是已按商户锁定的父资源关系 |
| 把 WebSocket 撤权说成立即断开所有旧连接 | 当前在下次发送尝试时重验；空闲连接不会主动即时扫描 |

### 8.2 文档权威关系

| 主题 | 面试权威版本 | 源码级底稿 |
| --- | --- | --- |
| 项目总览、消费者登录、读取搜索、内容与关注流 | 本文 §1～§4 | [01. 系统地图](01-system-map.md) 与本文源码索引 |
| 后台身份、RBAC、商户隔离与 WebSocket | 本文 §5 | [02. 后台与商户隔离](02-admin-rbac-chain.md) |
| 秒杀 | [09 §2](09-core-business-chain-review.md#2-秒杀同步准入异步落单按事实收敛) | [03. 秒杀订单链路](03-seckill-order-chain.md) |
| 点赞与热榜 | [09 §3](09-core-business-chain-review.md#3-点赞与热榜关系是事实聚合可追踪榜单可重建) | [04. 点赞和热榜链路](04-blog-like-hot-rank-chain.md) |
| 营销发券 | [09 §4](09-core-business-chain-review.md#4-营销发券多入口共用一笔权益事务) | [05. 营销发券链路](05-marketing-grant-chain.md) |
| 主张、测试证据与不能扩大之处 | 各模块“实现边界” | [06. 证据与边界](06-evidence-and-ownership.md) |

### 8.3 总体源码入口

| 主题 | 入口 |
| --- | --- |
| 依赖和运行配置 | [pom.xml](../../pom.xml)、[application.yaml](../../src/main/resources/application.yaml) |
| 请求入口 | [controllers](../../src/main/java/com/localdeals/controller/)、[WebConfig](../../src/main/java/com/localdeals/config/WebConfig.java) |
| 数据库演进 | [Flyway migrations](../../src/main/resources/db/migration/) |
| Lua 协议 | [Lua scripts](../../src/main/resources/lua/) |
| 消息消费者 | [mq](../../src/main/java/com/localdeals/mq/) |
| 后台任务与业务服务 | [services](../../src/main/java/com/localdeals/service/) |
| 当前测试 | [src/test](../../src/test/) |

当前源码可确认实现结构与局部恢复路径，但不能推出 MQ exactly-once、WebSocket 必达、Redis 全量丢失自动恢复、真实 Canal 全链已验证，或任何生产吞吐、P99、SLA 与容灾恢复点目标。项目没有 Testcontainers 依赖；部分集成测试需要外部服务或隔离数据源，测试文件存在不表示目标环境已执行。

### 8.4 面试前最后检查

1. 开场是否先说业务问题、整体方案与最终事实，而不是先背技术名词。
2. 每个机制是否能回答它防住的具体竞态，以及它不能解决什么。
3. 异常是否区分明确拒绝、暂时未知、重复执行、永久失败和所有权冲突。
4. 是否区分长期事实、过程状态、派生读模型与在线提示。
5. 是否主动说明默认关闭、未实现和没有生产证据的边界。
