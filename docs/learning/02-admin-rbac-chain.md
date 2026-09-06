# 02. 商户后台、RBAC 与 WebSocket 会话隔离

## 业务场景

同一个应用同时服务消费者和商户后台。后台需要登录、角色/权限校验、商户数据隔离、密码失效和在线订单通知；消费者仍使用 `tb_user` + 短信验证码，两套身份不能混用。

## 旧方案

V1/tutorial 路径以 `tb_user`、Redis `login:token:*` 和公共 Controller 为主，没有当前 V5 引入的后台身份、merchant scope 和权限矩阵。早期店铺/券查询也不能作为今天的商户隔离证明。当前后台是独立的 `tb_admin_account`、角色、权限和后台 session。

## 问题

只在 Controller 判断“是不是管理员”会留下跨商户读写、旧 token 继续有效、权限变化不生效和 WebSocket 连接越权的问题。后台请求还必须区分“没有身份”（401）和“有身份但没有权限”（403），资源不存在与跨商户资源不能泄露成可枚举的成功响应。

## 业务不变量

- admin token 只能解析为有效、启用中的后台 account；每次 resolve 都重新检查 account、merchant、role、permission 和 `auth_version`。
- 平台账号必须是 platform scope；商户账号必须绑定一个 active merchant，不能带平台角色。
- merchant 账号的所有 shop、voucher、marketing 读写都带 `merchant_id`；跨商户资源按资源不存在/禁止访问处理，不靠前端传参自觉。
- `tb_shop.merchant_id` 是根 scope；券通过 shop 继承 merchant，活动、标签、grant、batch job 再用复合 FK/查询保持同商户。
- admin WebSocket ticket 30 秒、只能消费一次；连接建立和每次发送都重新验证 token、账号状态、scope 和 `order:realtime`。
- 权限变化、密码变更、账号停用或 merchant scope 变化后，旧 session/WS 不能继续代表原权限。

## HTTP 路径与拦截器顺序

消费者链和后台链故意分开。`WebConfig#addInterceptors` 让 `RefreshTokenInterceptor`、`LoginInterceptor` 排除 `/admin/**`；后台请求只按下面顺序进入两只后台拦截器，因此消费者 Redis Hash `login:token:{token}` 和 `UserHolder` 不能充当后台身份。

| 顺序 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `AdminSessionInterceptor#preHandle` | `/admin/**`、`Authorization` | Bearer token；Redis `admin:login:token:{token}`；MySQL account/merchant/role/permission | `AdminPrincipalHolder`；可刷新 session TTL | 无事务；请求线程 `ThreadLocal` | 有效 token 建立当前后台 principal；无 token 也继续，让下一层定性 | malformed/缺失/过期 token 得不到 principal；Redis/MySQL RuntimeException 当前继续抛出 |
| 2 | `AdminAuthorizationInterceptor#preHandle` | HTTP method、MVC handler | `AdminPrincipalHolder`、`HandlerMethod` 方法/类上的 `@RequireAdminPermission` | 无 | 无 | OPTIONS 放行；身份和注解/permission 均满足才进 Controller | 无 principal→401；非 `HandlerMethod`、无注解或缺 permission→403 |
| 3 | Controller 方法 | 已授权请求 | DTO/path/query、principal | 调 Service | 事务由 Service public 方法代理 | 返回 `Result.ok(...)` | Service/参数异常交给 `WebExceptionAdvice` |
| 4 | `AdminSessionInterceptor#afterCompletion` | 本次请求 | 无 | `AdminPrincipalHolder.remove()` | 无 | 清除线程复用污染 | 即使 Controller 异常也在 MVC completion 阶段清理 |

`POST /admin/auth/login` 仍经过 session interceptor，但被 permission interceptor 的 `excludePathPatterns("/admin/auth/login")` 排除；所以匿名登录不会因类级 `@RequireAdminPermission` 被误拦。`/admin/auth/me`、logout、password 则继承 `AdminAuthController` 的空 permission 注解：必须有有效后台身份，但不额外要求某个 code。所有其他 `/admin/**` handler 必须显式在类或方法上 opt in；缺注解默认 403。

拦截器直接 `response.setStatus(401/403)` 并返回 `false`，此时响应不是 `Result` envelope。只有已经进入 Controller/Service 后抛出的 `ApiStatusException`、`IllegalArgumentException` 或其他 `RuntimeException` 才由 `WebExceptionAdvice` 生成 `Result.fail(...)`。这一区别是调试 HTTP 现场的第一步。

## Principal 构造与旧 token 失效

完整解析链是：Bearer token → Redis session value → `accountId:issuedAuthVersion` → `tb_admin_account` → merchant 状态 → role 合法性 → enabled permission codes → `AdminPrincipal`。

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `AdminSessionService#extractBearerToken` | Authorization | 只接受大小写不敏感的 `Bearer ` 前缀 | 无 | 无 | 返回 token 字符串 | 缺失/空/其他 scheme→null |
| 2 | `AdminSessionService#resolve` | token、refreshTtl | token 必须匹配 32 位十六进制；Redis String | malformed Redis payload 时删除 key；可刷新 TTL | Redis 单命令；无 DB 事务 | 解析 accountId 和签发时 authVersion | token 格式错、key 不存在/过期→null；payload 错→删 key 后 null |
| 3 | `AdminSessionService#resolveAccount` | accountId | `tb_admin_account` | 无 | 无 | 账号存在且 status=1 | 不存在/停用→null |
| 4 | 同上 | account scope | merchant account 再读 `tb_merchant` | 无 | 无 | PLATFORM 必须 merchantId=null；MERCHANT 必须绑定 active merchant | scope/merchant 不一致或 merchant 停用→null |
| 5 | 同上 | accountId | `AdminAccountMapper#selectRoleCodes` | 无 | 无 | platform 必须含 `PLATFORM_ADMIN`；merchant 必须含 OWNER/STAFF 且不能含平台角色 | 跨 scope 角色或无合法角色→null |
| 6 | 同上 | accountId | `selectPermissionCodes` 只取 active role 和 active permission | 构造 principal | 无 | principal 包含 account、merchant、scope、当前 authVersion、当前 permissions | 前置校验失败时不会装载 permission |
| 7 | `resolve` 收尾 | issued/current version | principal.authVersion | 版本不符删除 Redis session；匹配时按需 `EXPIRE` | 无 | 返回当前 principal | 版本不符→null |

Redis session 只保存 `accountId:authVersion` 和 TTL，不缓存 merchant、role 或 permission。每次 resolve 回 MySQL，正是为了让账号停用、merchant 停用、角色/权限表变化立即影响下一次 HTTP 请求和下一次 WebSocket 发送。密码变更通过 `AdminAuthService#changePassword` 的 public `@Transactional` 方法调用 `updatePasswordAndBumpVersion`；员工状态变化通过 `AdminManagementService#changeAccountStatus` 的 public `@Transactional` 方法条件更新并 bump version。旧 token 在下一次 resolve 时版本不符并被删除。直接改变角色/权限不会 bump version，但因为每次回库，也会在下一次 resolve 生效。

缺 token、格式错误、Redis key 过期和合法 token 对应账号失效，最终都由 authorization interceptor 返回 401。需要特别保留当前实现边界：`AdminSessionInterceptor` 没有把 Redis/MySQL 解析异常转换成 503，普通后台请求的此类异常会由 MVC 异常链落到 500；只有登录限流依赖不可用由 `AdminAuthService` 明确映射为 503。不能把两者统称为“登录失效”。

`UserHolder` 保存消费者 `UserDTO`，来源是 `login:token:*` Hash；`AdminPrincipalHolder` 保存后台 `AdminPrincipal`，来源是 `admin:login:token:*` String + MySQL 重建。两者分开避免消费者 token 进入 `/admin/**`，也避免后台权限/scope 污染普通用户请求；各自 interceptor 都在请求结束清理自己的 ThreadLocal。

## 权限、scope、归属与 SQL 的五层边界

| 层 | 回答的问题 | 当前锚点 | 不能替代什么 |
| --- | --- | --- | --- |
| 后台身份 | 是不是当前有效 admin | `AdminSessionService#resolve` | 不代表有具体操作权限 |
| permission code | 能否进入此 handler | `@RequireAdminPermission` + authorization interceptor | 不代表资源属于该 merchant |
| platform/merchant scope | principal 可声明哪个 merchant | `AdminPrincipal#isPlatform`、`MarketingAdminService#resolveMerchant` | 不代表目标资源存在 |
| Service 归属 | 资源是否在 scope 内、是否需锁 | `AdminCatalogService#requireScopedShop`、`MarketingAdminService#getCampaign` | 不能只靠先查后无条件写 |
| Mapper/SQL scope | 返回/更新的行本身受 merchant 条件约束 | `selectByIdAndMerchantForUpdate`、`queryVoucherOfShopForAdmin`、各 `selectScoped*`/条件更新 | 不替代身份与 permission |

前端菜单隐藏只改善体验，不在后端信任边界内；真实授权必须走以上层次。

### 接口推演一：`PUT /admin/shops/{id}`

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `AdminShopController#update` | shopId、patch | 注解 `shop:write` | 无 | 无 | 调 Service | 未登录 401；缺 permission 403 |
| 2 | `AdminCatalogService#updateShop` | 同上 + principal | `requireScopedShop(shopId,true)` | 无 | public `@Transactional` 开启 MySQL 事务 | 得到 scoped existing | platform 用 `selectByIdForUpdate`；merchant 用 `selectByIdAndMerchantForUpdate`；查不到均 404 |
| 3 | 同上 | validated patch | existing type/merchant | `tb_shop` controlled fields | 同一事务；merchant 更新 wrapper 再带 `merchant_id` | exactly 1 row | 0 row→404，事务回滚 |
| 4 | `registerCacheSyncAfterCommit` | old/new shop | transaction synchronization | commit 后删除/更新 shop cache、GEO | 不扩大 DB 事务；best effort | DB 事实已提交后同步缓存 | Redis 失败记录日志/指标，不回滚 shop |

角色矩阵：platform admin（有 `shop:write`）可更新任意真实 shop；正确 merchant admin 可更新本 merchant shop；错误 merchant admin 对外店因 scoped `FOR UPDATE` 得到 404；有身份但无 `shop:write` 在 Service 前 403；未登录 401。这里用 404 隐藏跨商户资源存在性。

### 接口推演二：`GET /admin/marketing/campaigns/{campaignId}`

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `MarketingAdminController#getCampaign` | campaignId、可选 merchantId | 注解 `marketing:read` | 无 | 无 | 调 Service | 未登录 401；缺 permission 403 |
| 2 | `MarketingAdminService#resolveMerchant` | requestedMerchantId、principal | `AdminPrincipalHolder` | 无 | 无 | platform 必须显式给 merchantId；merchant 默认使用自身 merchantId | platform 未给→400；merchant 显式给其他 merchant→403 |
| 3 | `MarketingAdminService#getCampaign` | campaignId、resolved merchantId | `VoucherCampaignMapper#selectScoped(campaignId,merchantId)` | 无 | 无事务的只读调用 | 返回 exact scoped campaign | 不存在或属于其他 merchant→404 |

同一资源下：platform admin 需带正确 `merchantId` 才成功；正确 merchant admin 可省略 merchantId；错误 merchant admin 若伪造目标 merchantId 得 403，若使用自身 scope 查询外部 campaign 得 404；无 `marketing:read` 得 403；未登录得 401。写接口还会在 public `@Transactional` Service 方法中使用 scoped `FOR UPDATE` 和条件更新，避免把一次先查当成最终授权。

## 401、403、404、429、503 与 envelope 决策

| HTTP | 触发点 | 当前响应形态 | 含义/现场 |
| --- | --- | --- | --- |
| 401 | authorization interceptor 无 principal | bare status，无 `Result` body | token 缺失、格式错、过期、authVersion 不符、账号/merchant/role 失效 |
| 401 | `AdminAuthService#doLogin` 密码/账号拒绝；ws-ticket 再 resolve 失败 | `Result{success=false,...}` | 登录凭据无效或已登录 token 在业务入口再次失效 |
| 403 | authorization interceptor 缺注解/permission | bare status | 已有身份，但 handler 没有 opt in 或 permission 不满足 |
| 403 | Service 拒绝 scope，例如 merchant 显式请求其他 merchant | `Result.fail` | 身份和 permission 已过，但声明范围非法 |
| 404 | scoped Service/Mapper 查不到资源 | `Result.fail` | 真不存在或跨商户；有意不泄露资源存在性 |
| 429 | 登录用户名/IP 计数达到上限 | `Result.fail(code=ADMIN_LOGIN_RATE_LIMITED)` | throttle 正常工作且拒绝请求，不是鉴权依赖故障 |
| 503 | 登录失败/IP 计数 Redis 读取、Lua 或返回值异常 | `Result.fail(code=ADMIN_LOGIN_UNAVAILABLE)` | 登录限流无法可靠判断，fail closed |

`Result.success=true` 只表示 Controller 正常返回；不能用 HTTP 200 代替权限、scope 或资源归属证明。反过来，拦截器 401/403 没有业务 envelope，也不能要求客户端总能从 body 取 code。普通 admin session resolve 的 Redis 故障当前是 500 边界，不应误记为表中的登录 503。

## WebSocket 生命周期与精确路由

后台客户端先调用 `POST /admin/auth/ws-ticket`，而不是把长期 Bearer token 放进 URL。URL 会进入浏览器历史、代理/access log 和监控标签；30 秒、一次性的 ticket 缩短泄露窗口，并通过 Lua `GET` 后 `DEL` 原子消费。

| 步骤 | 类#方法 | 输入 | 读取 | 写入 | 事务/锁 | 成功结果 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `AdminAuthController#issueWebSocketTicket` | Bearer token | HTTP principal 已要求 `order:realtime`；再次 resolve token | Redis `admin:ws:ticket:{ticket}`=token，TTL 30s | Redis SET | 返回 ticket/ttl | token/permission 当前无效→401；Redis 异常→500 |
| 2 | `AdminWebSocketAuthInterceptor#beforeHandshake` | `/ws/admin/connect?ticket=...` | Lua 一次消费 ticket，再 resolve token/account/permission/scope | handshake attributes：accountId、merchantId、scope、token、ADMIN type | Lua 原子 GET+DEL | 绑定 exact identity | 缺失/过期/复用/依赖异常→401；缺 realtime/坏 scope→403 |
| 3 | `SeckillWebSocketHandler#afterConnectionEstablished` | attributes | exact scope | 注册 platform map 或 `merchantId -> sessions` map | concurrent maps；单 session decorator | 连接进入唯一 registry | scope 不一致→policy violation close |
| 4 | `WebSocketNotifier#notify` | order result、voucherId | SQL voucher→shop→merchant | Redis user、platform、exact merchant channel | Pub/Sub，无业务事务 | 各类订阅者只收到自己的 channel | merchant 查询失败时不发 merchant channel，绝不广播其他商户；user/platform publish 已可能完成 |
| 5 | `SeckillWebSocketHandler#sendToAdminRegistry` | channel message | 每个 session 保存的 token；再次 `resolve(token,false)` | send 或从 registry 移除并 close | 每连接 `ConcurrentWebSocketSessionDecorator` 串行发送 | 当前 account/scope/permission 仍匹配才发送 | 撤权、停用、authVersion/scope 改变、Redis/MySQL 异常→policy close；发送异常→server-error close |

platform、merchant、user 是三套 registry 和三类 Redis channel：`ws:seckill:admin:platform`、`ws:seckill:admin:merchant:{merchantId}`、`ws:seckill:{userId}`；发券提示另用 `ws:voucher-grant:{userId}`。建立连接时验证一次仍不够，因为长连接期间密码、状态、角色或 scope 可变；发送前 resolve 是撤权生效边界。WebSocket publish/route/send 都发生在订单或 grant 事实提交之外，失败只丢失在线提示，不得改变订单落库或发券结果。

## 数据/状态模型

| 对象 | 作用 | 关键约束 |
| --- | --- | --- |
| `tb_admin_account` | 后台身份、merchant、scope、状态、密码 hash、auth version | username 唯一；`auth_version` 使旧 token 失效 |
| `tb_admin_role` / `tb_admin_permission` | 固定角色矩阵 | V5 预置 `PLATFORM_ADMIN`、`MERCHANT_OWNER`、`MERCHANT_STAFF` |
| `tb_shop.merchant_id` | 店铺根范围 | V5 非空回填 legacy merchant 并建 FK/index |
| Redis admin session | `accountId:authVersion` + TTL | 不是权限真相；resolve 时回 MySQL |
| Redis ws-ticket | ticket→token，一次消费，30s | Lua 原子消费，不能复用 |
| WS maps | platform、merchantId、userId 分开 | 发布只进精确 channel；发送前再校验 |

登录时 username/IP 失败窗口和 IP admission 由 Lua 维护；Redis 不可用时后台登录 fail closed。bootstrap initializer 只有“没有平台账号”时才允许用显式环境变量创建，不提供默认凭据。

## 异常与恢复

- 账号不存在时使用 dummy BCrypt hash，避免直接暴露“账号不存在”；失败计数、锁定和 Redis 故障分别映射为拒绝、429、503。
- token 的 `auth_version` 与数据库不一致时删除 session；密码修改通过条件更新 bump version。
- admin ticket 过期、重复消费或缺少 realtime 权限时拒绝连接；不是让连接建立后再猜权限。
- merchant scope 改变、账号停用、权限移除时，`SeckillWebSocketHandler` 的发送前 revalidation 会关闭并移除会话。
- 发布 merchant channel 时先由 `WebSocketNotifier` 通过 voucher→shop 查出准确 merchant；商户路由失败不会把 user/platform 的消息误发到别的商户，且可观测。
- 代理头只有 direct peer 命中 `TrustedClientIpResolver` 的显式 CIDR/IP 才可信；不能把任意 `X-Forwarded-For` 当成限流身份。

## 调试现场检查顺序

1. 先记 HTTP status、是否有 `Result` body、route/method 和 Authorization scheme；bare 401/403 优先看 interceptor，envelope 4xx/5xx 再看 Service/advice。
2. 只核对 Redis `admin:login:token:{token}` 是否存在、payload 是否为 `accountId:authVersion`、TTL 是否合理；不要打印 token 值到工单或日志。
3. 按 accountId 查 `tb_admin_account(status,scope_type,merchant_id,auth_version)`，merchant scope 再查 `tb_merchant.status`。
4. 查 `tb_admin_account_role`→active `tb_admin_role`→`tb_admin_role_permission`→active `tb_admin_permission`，并对照 handler 的 exact permission code。
5. 再查资源根归属：shop 的 `merchant_id`，voucher→shop，marketing 表自身 merchantId；复现 Mapper scoped 条件和 affected row count。
6. WebSocket 先查 ticket 是否已消费/过期，再查 handshake attributes 和 exact Redis channel；发送前 resolve 失败看 account/version/permission/scope，禁止改成全商户广播“验证消息”。
7. 最后看 auth/WS 指标与限频日志；指标能定位分支，不能替代数据库归属或用户收件证明。

## 方案取舍

独立后台身份提高了 scope 和审计清晰度，但增加了一套 session/RBAC 代码；固定角色牺牲自定义角色灵活性，换取低基数、可检查的权限矩阵。WS 采用“ticket + 每次发送复核”而不是长连接永久信任，增加了发送开销，却能处理撤权和 scope 变化。

当前没有 sub-merchant、单店粒度账号、历史订单后台列表的完整能力，也没有把 Redis HA 或跨实例 WS 可靠投递写成已实现能力。

## 代码导航

| 关注点 | 路径与方法 |
| --- | --- |
| 拦截器链 | [`WebConfig`](../../src/main/java/com/localdeals/config/WebConfig.java)、[`AdminSessionInterceptor.preHandle`](../../src/main/java/com/localdeals/interctptor/AdminSessionInterceptor.java)、[`AdminAuthorizationInterceptor.preHandle`](../../src/main/java/com/localdeals/interctptor/AdminAuthorizationInterceptor.java) |
| 登录/密码 | [`AdminAuthController`](../../src/main/java/com/localdeals/controller/AdminAuthController.java)、[`AdminAuthService.login/doLogin/changePassword`](../../src/main/java/com/localdeals/service/AdminAuthService.java) |
| session/ticket | [`AdminSessionService.resolve/issueWebSocketTicket/consumeWebSocketTicket`](../../src/main/java/com/localdeals/service/AdminSessionService.java) |
| scope 写入 | [`AdminCatalogService.requireScopedShop/assignShop`](../../src/main/java/com/localdeals/service/AdminCatalogService.java)、[`VoucherMapper` scoped SQL](../../src/main/java/com/localdeals/mapper/VoucherMapper.java) |
| 管理接口 | [`AdminShopController`](../../src/main/java/com/localdeals/controller/AdminShopController.java)、[`AdminManagementController`](../../src/main/java/com/localdeals/controller/AdminManagementController.java)、[`MarketingAdminController`](../../src/main/java/com/localdeals/controller/MarketingAdminController.java) |
| WS 隔离 | [`AdminWebSocketAuthInterceptor`](../../src/main/java/com/localdeals/websocket/AdminWebSocketAuthInterceptor.java)、[`WebSocketNotifier`](../../src/main/java/com/localdeals/websocket/WebSocketNotifier.java)、[`SeckillWebSocketHandler`](../../src/main/java/com/localdeals/websocket/SeckillWebSocketHandler.java) |
| 数据约束 | [`V5__merchant_admin_rbac.sql`](../../src/main/resources/db/migration/V5__merchant_admin_rbac.sql)、[`V6__admin_auth_version.sql`](../../src/main/resources/db/migration/V6__admin_auth_version.sql) |

## 验证证据

本轮只阅读测试源码，没有执行测试。下表把测试类自身契约与外部运行环境分开；类名和历史成功记录都不能替代当前运行。

| 测试 | fixture | 动作 | 核心断言 | 证据层级 | 未证明内容 |
| --- | --- | --- | --- | --- | --- |
| `AdminRbacIT` | `@SpringBootTest`、test profile；插入 merchant/account/role/shop/voucher，并操作真实 Mapper/RedisTemplate | 登录、resolve、一次 ticket、改密码/停账号；走真实 MockMvc；查询跨 merchant shop/voucher | 当前 permission 回载；ticket 仅消费一次；authVersion/停用撤 token；跨商户 404；消费者 token 不能进后台 | 集成契约；依赖是否专用取决于正式 runner 注入 | 类本身不创建容器；不证明生产 Redis/MySQL、HA 或多实例 |
| `AdminMvcSecurityTest` | `@WebMvcTest`；mock session/auth/catalog/management 和 consumer Redis Hash | login、匿名 admin GET、带不同 principal 的请求、旧写路由 | login 是匿名入口；有效 permission 200；缺 permission 403；consumer token 401；旧写 mapping 404/405 | MVC slice | 不读真实 Redis/MySQL，不证明 Service scope SQL |
| `AdminAuthorizationInterceptorTest` | 手工 `HandlerMethod`、mock request/response、ThreadLocal principal | 调 `preHandle` | anonymous 401；无注解/缺 permission 403；exact permission 和空 permission 注解放行 | 纯单元 | 不证明注册顺序、session resolve、响应 body |
| `AdminSessionServiceTest` | mock Redis ValueOperations、account/merchant Mapper | 连续 resolve；模拟账号/merchant/role/version 变化 | 每次回库加载 permission；失效会删 token；consumer/malformed token 不解析；跨 scope role 拒绝 | 纯单元 | 不证明 Redis TTL/Lua、SQL schema、并发变更窗口 |
| `AdminWebSocketAuthInterceptorTest` | mock session service、handshake request/response | 缺/旧 ticket、无 realtime、合法 merchant/platform ticket | 对应 401/403；合法连接只绑定 exact account/scope/token，platform 无 merchantId | 纯单元 | 不执行真实 Lua、TTL、网络握手或 origin 策略 |
| `WebSocketSessionIsolationTest` | mock WebSocketSession、session resolve 和 user token validator | 注册 platform/两个 merchant/user；按 registry 发送；撤权、改 scope、并发 burst、send failure | exact merchant/platform/user 不串线；发送前撤权关闭；native session 写串行；坏 session 移除 | 纯单元并发契约 | 不证明 Redis Pub/Sub 跨节点交付、断线重连、消息已读或容量 |

`AdminRbacIT` 没有 Testcontainers 依赖，也不自行创建或拥有容器。若由正式 runner 执行，隔离性来自 runner 注入的专用 Redis/MySQL 地址、端口和 schema；脱离 runner 单独执行时不能自动假定连接仍专用。历史 result 文档只证明当时限定环境中的断言曾通过，本轮没有重跑。

## 不能证明的边界

- 不能根据 Git 提交历史推断个人作者或工作量。
- 不能声称 Redis Cluster/Sentinel、跨节点 session/WS 一致性、生产审计留痕已经验证。
- 不能声称支付、退款、核销、历史订单后台能力或 Java 17 升级已实现。
- `HTTP 200` 或 WS 进程存活不等于消息被用户看到；WS 只是在线提示通道。
- `UNVERIFIED`：普通后台 session resolve 的依赖故障在反向代理/前端中的最终展示未做运行验证；源码仅能确认应用侧未专门转成 503。
- `BOUNDARY`：角色变化只有下次 resolve 生效；当前没有主动遍历并关闭全部长连接，关闭发生在下一次向该 session 发送前。

## 项目特色摘要

面向多商户后台的越权与会话撤销问题，建立独立后台身份、权限码与商户范围的分层校验，并通过会话版本和一次性 WebSocket 凭证控制旧会话及实时通道的隔离。正式简历表述与证据映射见 [07. 简历与面试定稿](07-resume-and-interview.md)。
