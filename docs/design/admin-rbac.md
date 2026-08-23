# 商户后台、RBAC 与发布门禁

## 1. 本阶段目标

本阶段把管理端从消费者账号体系中拆出，形成独立的后台身份边界：

- 消费者继续使用 `tb_user`、短信验证码和 `login:token:*`。
- 后台账号使用 `tb_admin_account`、BCrypt 密码和 `admin:login:token:*`。
- 后台请求同时校验权限码与商户数据范围；前端隐藏菜单只改善体验，不是安全边界。
- `tb_shop.merchant_id` 是数据范围根，优惠券通过 `voucher.shop_id -> shop.merchant_id` 继承范围。
- 管理端实时订单按平台或商户频道隔离，不再向所有已登录用户广播。

这不是完整 SaaS 租户平台。本阶段采用固定角色，不包含自定义角色编辑器、子商户层级、按单店授权、历史订单查询 API 或管理账号页面。

## 2. 数据模型

Flyway V5/V6 只做前向迁移，不应修改已执行迁移的内容或 checksum。

| 对象 | 用途 |
| --- | --- |
| `tb_merchant` | 商户主体；`status=1` 启用，`2` 停用，`LEGACY_UNASSIGNED` 使用 `0` |
| `tb_admin_account` | 独立后台账号；平台账号的 `merchant_id` 为空，商户账号必须绑定商户 |
| `tb_admin_role` | 固定内置角色 |
| `tb_admin_permission` | 稳定权限码 |
| `tb_admin_account_role` | 账号与角色关系 |
| `tb_admin_role_permission` | 角色与权限关系 |
| `tb_shop.merchant_id` | 商户数据范围的归属根 |
| `tb_admin_account.auth_version` | 密码或安全状态变化时递增，使旧 token 和后台 WebSocket 失效 |

V5 将存量商铺归入停用的 `LEGACY_UNASSIGNED` 商户。平台管理员只能把这类商铺一次性认领给启用商户；已经归属的商铺禁止跨商户换绑。这一限制同时避免优惠券查询和实时订单路由在并发换绑时泄露给旧商户。

## 3. 固定角色与权限

| 权限码 | PLATFORM_ADMIN | MERCHANT_OWNER | MERCHANT_STAFF |
| --- | --- | --- | --- |
| `dashboard:read` | 是 | 是 | 是 |
| `shop:read` | 是 | 是 | 是 |
| `shop:write` | 是 | 是 | 否 |
| `voucher:read` | 是 | 是 | 是 |
| `voucher:write` | 是 | 是 | 否 |
| `order:read` | 是 | 是 | 是 |
| `order:realtime` | 是 | 是 | 是 |
| `account:read` | 是 | 是 | 否 |
| `account:manage` | 是 | 是 | 否 |
| `merchant:manage` | 是 | 否 | 否 |

`PLATFORM_ADMIN` 的数据范围是全部商户；两个商户角色只能访问自己 `merchant_id` 下的数据。`order:read` 已作为稳定权限码预留，但当前没有历史订单列表接口，只有 `order:realtime` 对应的实时秒杀结果。

所有 `/admin/**` Controller 必须显式使用 `@RequireAdminPermission`。没有注解的管理接口默认返回 403；登录接口是唯一不要求现有后台会话的例外。

## 4. 登录、会话与限流

后台密码使用 BCrypt（cost 10），用户名统一为 4-64 位小写字母、数字、点、下划线或短横线，密码至少 12 个字符且 UTF-8 编码不超过 72 字节。

登录采用两层固定窗口限制：

1. `用户名 + 客户 IP` 记录失败次数，达到阈值后锁定该组合。
2. 客户 IP 在查询数据库和执行 BCrypt 前通过 Redis Lua 原子占用一次请求配额；成功和失败请求都计数，避免并发轮换用户名造成 CPU 峰值。

Redis 限流不可用时不会绕过限流进入密码校验。客户端地址只在直连 peer 命中 `LOCAL_DEALS_ADMIN_TRUSTED_PROXIES` 的精确 IP 或 CIDR 时读取 `X-Real-IP`/`X-Forwarded-For`；否则只使用 TCP peer。必须同时阻止外部客户端直连后端 8083，避免伪造代理头。

本仓库的 Docker Compose 将 nginx 固定在 `172.30.55.10`，`.env.example` 只信任该地址，应用默认不信任任何代理。如果修改 Compose 子网，必须同步修改 `LOCAL_DEALS_ADMIN_TRUSTED_PROXIES`；生产环境应填写实际反向代理地址，不要信任整个客户端网络。直接访问后端时无需把客户端地址加入代理信任列表。

登录成功后 Redis 只保存 `accountId:authVersion`，每次请求都会重新加载账号、商户、角色和权限。停用员工账号、停用商户或修改密码会使旧凭据在下一次请求时失效。

## 5. HTTP API

除登录外，请求都使用 `Authorization: Bearer <admin-token>`。响应沿用项目统一的 `Result` 包装。

| 方法与路径 | 权限 | 范围/说明 |
| --- | --- | --- |
| `POST /admin/auth/login` | 无现有会话 | 独立后台用户名、密码登录 |
| `GET /admin/auth/me` | 已登录 | 返回当前账号、范围和权限 |
| `POST /admin/auth/logout` | 已登录 | 撤销当前 token |
| `PUT /admin/auth/password` | 已登录 | 修改密码并递增 `auth_version` |
| `POST /admin/auth/ws-ticket` | `order:realtime` | 签发 30 秒一次性 WebSocket ticket |
| `GET /admin/merchants` | `merchant:manage` | 平台范围 |
| `POST /admin/merchants` | `merchant:manage` | 原子创建启用商户及其 `MERCHANT_OWNER` 主账号 |
| `GET /admin/accounts?merchantId=` | `account:read` | 平台可筛商户，商户账号强制使用自身范围 |
| `POST /admin/accounts` | `account:manage` | 创建 `MERCHANT_STAFF`；商户账号不能替其他商户创建 |
| `PUT /admin/accounts/{id}/status` | `account:manage` | 只允许启停员工，不能停用自己或内置主账号 |
| `GET /admin/shops` | `shop:read` | 分页、关键词查询；SQL 直接带商户范围 |
| `GET /admin/shops/{id}` | `shop:read` | 越界资源统一表现为不存在 |
| `POST /admin/shops` | `shop:write` | 平台必须指定启用商户，商户账号强制绑定自身商户 |
| `PUT /admin/shops/{id}` | `shop:write` | 更新条件同时包含 `id + merchant_id` |
| `PUT /admin/shops/{id}/merchant` | `merchant:manage` | 仅 `LEGACY_UNASSIGNED` 到启用商户的一次性认领 |
| `GET /admin/shops/{shopId}/vouchers` | `voucher:read` | voucher 与 shop 在同一条 SQL 中校验归属 |
| `POST /admin/shops/{shopId}/vouchers` | `voucher:write` | 创建普通券 |
| `POST /admin/shops/{shopId}/vouchers/seckill` | `voucher:write` | 创建秒杀券，事务提交后写 Redis 元数据 |

旧的消费者目录接口仍保留公开只读能力，但 `POST/PUT /shop/**`、`POST /voucher/**` 等旧写映射已经退役，消费者 token 也不能进入 `/admin/**`。

## 6. 管理端 WebSocket

管理端先调用 `POST /admin/auth/ws-ticket`，再连接：

```text
/ws/admin/connect?ticket=<30秒一次性ticket>
```

上面是后端路径；通过仓库自带 nginx 对外访问时，路径为 `/api/ws/admin/connect?ticket=...`。

ticket 由 Lua 原子读取并删除，长期 bearer token 不出现在 URL。握手要求 `order:realtime`，并把账号、scope、merchant 和原 token 绑定到 session。每条发送前都会再次解析 token；账号状态、商户状态、`auth_version`、权限或范围发生变化时会关闭连接。

Redis pub/sub 频道按范围拆分：

- 平台：`ws:seckill:admin:platform`
- 商户：`ws:seckill:admin:merchant:<merchantId>`
- 下单用户：`ws:seckill:<userId>`

商户 ID 由 `voucher -> shop` 查询得到；无法证明归属时只保留用户回执和平台事件，不向任意商户退化广播。同一 WebSocket session 使用有界串行发送，发送失败会关闭并移除连接。

## 7. 首次启动

系统没有默认管理员密码。首次启动前设置：

```bash
LOCAL_DEALS_ADMIN_BOOTSTRAP_USERNAME=platform.admin
LOCAL_DEALS_ADMIN_BOOTSTRAP_PASSWORD=<至少12位的唯一密码>
LOCAL_DEALS_ADMIN_BOOTSTRAP_DISPLAY_NAME=平台管理员
```

启动器仅在数据库中不存在任何 `scope_type=PLATFORM` 账号时创建一次 `PLATFORM_ADMIN`。创建并验证登录后，应从运行环境删除 bootstrap 用户名和密码；再次启动不会覆盖现有账号。如果没有平台账号且没有配置完整 bootstrap 凭据，后台保持无人可登录的 fail-closed 状态。已存在但被停用或角色损坏的平台账号也不会被 bootstrap 自动修复，应通过受控数据库运维恢复。

平台管理员随后按以下顺序初始化业务数据：

1. `POST /admin/merchants` 创建商户和主账号。
2. `PUT /admin/shops/{id}/merchant` 逐一认领历史商铺。
3. 用两个商户账号交叉访问 shop/voucher，确认越界请求得到 404/403 且无缓存或数据库副作用。
4. 确认实时订单只进入所属商户和平台频道。

## 8. 升级门禁

旧实例仍可能暴露匿名写接口、消费者 token 管理入口或全局管理 WebSocket，因此 V5/V6 不支持与旧版本滚动混跑：

1. 网关先封禁旧 `POST/PUT /shop/**`、`POST /voucher/**` 和旧管理 WebSocket。
2. 停止全部旧实例并备份数据库。
3. 由单个新实例执行 Flyway V5/V6，核对所有 `tb_shop.merchant_id` 已回填。
4. 完成平台账号 bootstrap、商户创建和历史商铺认领。
5. 验证匿名/消费者 token 被拒绝、权限不足返回 403、跨商户资源不可见、改密后旧 token 失效。
6. 删除 bootstrap 凭据后恢复管理入口。

如果不能接受停机窗口，应先实现版本化后台入口和双轨隔离；当前版本不能用普通滚动发布代替上述门禁。

## 9. 验证

核心测试：

```bash
~/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn \
  -Dtest=AdminAuthServiceTest,AdminClientIpResolverTest,AdminSessionServiceTest,\
AdminAuthorizationInterceptorTest,AdminCatalogServiceTest,AdminManagementServiceTest,\
AdminMvcSecurityTest,WebSocketSessionIsolationTest test
```

真实 MySQL/Redis 范围与迁移验证：

```bash
set -a && source .env && set +a
~/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn -Dtest=AdminRbacIT test
```

`AdminRbacIT` 需要隔离的测试数据和可连接的 MySQL/Redis；不要把共享生产库作为测试目标。
