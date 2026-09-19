# 登录、请求身份恢复与后台数据范围

## 一、消费者申请验证码

消费者调用 `POST /user/code`，带来手机号，希望取得一轮可以用于登录的验证码。这个请求到验证码状态写入 Redis 并返回为止，不会在同一次请求里继续登录或访问业务接口。

- 请求先进入消费者拦截器链。`POST /user/code` 属于公开入口，没有登录也可以进入 `UserController`，随后由 `UserServiceImpl` 检查手机号格式并生成六位数字验证码。
  - 格式检查实际调用 `RegexUtils.isPhoneInvalid`，使用项目当前的大陆手机号正则；不匹配就返回业务失败，Redis 中什么也不改。当前 `5[0-3,5-9]` 字符类意外包含逗号，因此这里只能说按现有格式校验，不能把它表述成严格的真实手机号验证。
- 服务用手机号拼出三类 Redis key，再把它们连同验证码和两个有效期传给 `issue_login_code.lua`。
  - `KEYS[1]` 是发送冷却 `login:code:rate:{phone}`，`KEYS[2]` 是验证码 `login:code:{phone}`，`KEYS[3]` 是失败次数 `login:code:failure:{phone}`。
  - `ARGV[1]` 是刚生成的六位验证码，`ARGV[2]` 是验证码有效期 120 秒，`ARGV[3]` 是发送冷却 60 秒。
  - 脚本先对冷却 key 执行 `EXISTS`。已经存在就返回 0，原验证码和失败记录都不改；Java 看到结果不是 1，抛出 429，本次请求结束。
  - 冷却不存在时，脚本先用 `SET ... EX 120` 写验证码，再用 `SET ... EX 60` 建立冷却，最后删除旧失败次数并返回 1。这些命令在一次 Lua 中连续执行，另一个并发申请只能等脚本结束后再看到冷却。
  - Redis 执行抛异常时，Java 把认证状态记为不可用并返回 503；脚本返回空值或其他非 1 结果时，当前代码仍按发送过频返回 429。
- Java 收到脚本返回的 1 后，按开关决定是否记录开发日志，然后返回 `Result.ok()`，本次请求结束。
  - 当前项目没有接入真实短信供应商，成功只表示验证码状态已经写入 Redis，不表示短信已经送达。
  - 验证码日志默认关闭，只有显式打开 `local-deals.auth.log-verification-code` 的隔离开发环境才会记录遮罩手机号和验证码。

## 二、消费者用验证码登录并建立会话

消费者另行调用 `POST /user/login`，带来手机号和验证码，希望换到一枚会话 token。这个请求从 Redis 消费验证码，随后查询或创建 MySQL 用户，再把会话写回 Redis。

- 请求作为公开 POST 进入登录 Service。服务先检查手机号格式，再要求验证码是严格的六位 ASCII 数字。
  - 手机号不合法返回“号码不合法”；验证码格式不合法返回“验证码不正确”，两者都不会调用 Redis。
- 输入通过后，服务用手机号拼出验证码 key `login:code:{phone}` 和失败次数 key `login:code:failure:{phone}`，把它们作为 `KEYS`，再把用户提交的验证码和最大失败次数 5 作为 `ARGV` 交给 `consume_login_code.lua`。
  - 脚本先读取失败次数；key 不存在按 0 处理，已经达到 5 就返回 2，不再读取验证码。
  - 接着读取验证码。验证码 key 不存在就返回 0；与提交值相同就同时删除验证码和失败次数并返回 1，所以这份验证码只能成功消费一次。
  - 值不相同时，脚本读取验证码剩余毫秒 TTL。TTL 已经小于等于 0，就删除验证码和失败记录并返回 2。
  - 验证码仍有效时，脚本给失败次数加一，并把失败 key 的 TTL 设置成验证码的剩余 TTL。累计到第 5 次时，再把失败值固定为 5、保留相同剩余 TTL、删除验证码并返回 2；尚未达到 5 次则返回 0。
  - Redis 执行抛异常时 Java 返回 503。脚本结束后控制回到 Java，只有返回 1 才继续查用户；0、2、空值或其他结果都统一返回“验证码不正确”，本次登录结束。
- 验证码消费成功后，服务按手机号查询 MySQL `tb_user`。查到就沿用现有用户；查不到就生成随机昵称并插入用户。
  - `tb_user.phone` 的唯一索引是数据库中的重复建号边界。
- 服务把 `User` 裁剪成只含 `id`、`nickName`、`icon` 的 `UserDTO`，生成一枚去掉连字符的 32 位随机 token，再把 DTO 的非空字段逐个转成字符串，写入 `login:token:{token}` Redis Hash。
  - `putAll` 写完 Hash 后，Java 再单独执行 `EXPIRE 30 分钟`。这两个命令不是一次 Lua 或事务；前者成功、后者失败时可能留下没有预期 TTL 的 Hash，当前没有自动清理任务。
  - 写入成功后把 token 返回客户端，本次登录请求结束；后续业务访问需要另发请求。
  - 验证码消费、MySQL 查建用户和 Redis 会话写入不是一个共同事务。验证码已经被脚本删除后，如果数据库或会话写入失败，当前实现不会恢复验证码，用户只能重新申请。

## 三、消费者带 token 访问公开或受保护接口

登录后的业务访问是新的 HTTP 请求。请求可以在原始 `authorization` 头里带消费者 token，也可以不带；它先尝试恢复身份，再根据请求方法和路径判断是否必须登录。

- 请求先进入顺序为 0 的身份恢复拦截器，从 `authorization` 头取 token。消费者链整体排除 `/admin/**`，因此不会进入后台身份链。
  - 没有 token 就不查 Redis，先放行给下一层判断接口是否公开。
  - 带了 token，就直接拼出 `login:token:{token}`，用 `HGETALL` 语义读取整个 Hash；消费者 token 在这里没有额外格式检查。Hash 为空就先放行，此时仍没有建立用户身份。
- 如果查到会话，拦截器把 Hash 中的字符串字段填回 `UserDTO`，放入当前线程的 `UserHolder`，再对同一个 key 执行 `EXPIRE 30 分钟`。后面的 Controller 和 Service 从 `UserHolder` 取得 `id`，而不是相信请求另外提交的 userId。
  - 当前代码不检查 `expire` 返回的布尔值；只要它没有抛异常，请求就继续。读取或续期抛异常时，会先清理 `UserHolder`，再抛出 `AUTH_STATE_UNAVAILABLE` 并返回 503；即使目标是公开接口，也不会把“无法确认会话”当作匿名访问继续执行。
- 请求接着进入顺序为 1 的登录要求拦截器，同时根据 method 和 path 判断是否公开。
  - 拦截器先从 URI 去掉 context path，并去掉非根路径末尾的 `/`。OPTIONS 预检直接放行；GET/HEAD 只与公开读取清单匹配，POST 只与 `/user/code`、`/user/login` 匹配。
  - 公开读取包括指定店铺、按类型或名称查店铺、店铺搜索、店铺类型、部分 Blog 读取和店铺上架券；匹配使用 Ant 风格路径，例如 `/shop/{id:\d+}` 只接受数字 ID。
  - 其余请求都受保护。`UserHolder` 中有用户就进入 Controller，没有就返回 401；同一个公开路径换成写方法也不会自动公开。
- 业务处理结束后，身份恢复拦截器在 `afterCompletion` 中删除 `UserHolder`，避免线程以后复用时还保存本次用户。
  - Controller 抛错或登录拦截器返回 401 时同样会清理；这里只清线程里的临时身份，不删除 Redis 会话。
- 消费者登出是另一个受保护请求 `POST /user/logout`。当前 token 先恢复身份并续期，业务随后删除 `login:token:{token}`，请求完成时再清理线程上下文。
  - 删除成功后，下一次请求读取不到会话：公开接口按匿名访问，受保护接口返回 401。
  - Redis 删除异常会让登出请求失败，不能把它当成会话已经撤销。

## 四、平台账号和商户账号登录后台

后台账号调用 `POST /admin/auth/login`，带来用户名、密码和连接信息，希望取得后台 token。平台账号和商户账号共用这一套登录、会话和权限链，身份差别来自 MySQL 中的 scope、merchantId、角色和权限。

- 请求先经过后台会话拦截器。正常登录请求没有 Bearer token，因此这里不建立 principal 并继续；后台授权拦截器明确排除了 `/admin/auth/login`，随后才进入登录 Controller。
  - 消费者公开路径和消费者 token 都不能让这个入口建立后台身份。
- 登录入口先取得客户端 IP。只有直连来源处于显式配置的可信代理范围时，才采用 `X-Real-IP` 或 `X-Forwarded-For`；否则使用直连地址。
  - 解析器先把直连地址规范成 IP 字面量；直连地址无效就使用 `unknown`。直连不是可信代理时忽略两个转发头；可信时先取合法的 `X-Real-IP`，否则只检查 `X-Forwarded-For` 的第一个地址。第一个地址不合法就退回直连地址，不会继续向后寻找合法地址。
- 登录服务把用户名去空格、转小写，只接受 4～64 位小写字母、数字、点、下划线和短横线；不合法时后续按无效身份处理。接着用规范化用户名和客户端 IP 组装两个 Redis 计数 key。
  - “用户名加 IP”先拼成 `username|ip`；无效用户名或地址分别使用 `invalid`、`unknown`，再做 MD5 摘要，得到 `admin:login:failure:{摘要}`。“单 IP 尝试”也对 IP 做摘要，得到 `admin:login:ip-attempt:{摘要}`，避免把原用户名和 IP 直接放进 key。
  - Java 先 GET 用户名加 IP 的失败计数；数值已经达到默认 5 次就返回 429，值无法解析或 Redis 抛错就返回 503。
  - 尚未锁定时，Java 调用 `record_admin_login_failure.lua` 增加单 IP 总尝试。脚本对 key 执行 `INCR`，如果结果为 1 就设置默认 15 分钟 TTL，然后返回新计数；超过默认 30 次时返回 429。脚本抛异常时返回 503；脚本返回空值时 Java 用 31 作为保守结果，因此按超限返回 429。
- 通过尝试预算后，服务按用户名查询 `tb_admin_account`，再用 BCrypt 比较密码。
  - SQL 按规范化用户名查询并限制一行。密码必须非空且 Java 字符长度不超过 256；用户名不存在时仍对 dummy hash 计算，减少根据快速返回时间枚举账号的差异。
  - 密码不匹配，或密码虽匹配但后面的账号组合无法构造 principal，都会调用同一个计数 Lua 增加用户名加 IP 的失败次数。新计数达到 5 返回 429，未达到返回统一的 401；脚本返回空值时 Java 按 5 次处理，因此也返回 429。响应不说明是账号、密码还是账号状态问题。
- 密码正确后，服务调用 `resolveAccount` 加载账号、商户、角色和当前权限，组装本次登录的 `AdminPrincipal`。
  - 方法先按 accountId 读取 `tb_admin_account`，要求记录存在且 `status=1`。平台账号必须是 `scope_type=PLATFORM` 且 `merchant_id` 为空；商户账号必须是 `scope_type=MERCHANT` 且 merchantId 非空，并继续读取 `tb_merchant` 确认商户 `status=1`。
  - 接着执行角色 SQL：从账号角色关系连接 `tb_admin_role`，只取 `r.status=1` 的角色码。平台账号必须包含 `PLATFORM_ADMIN`；商户账号不能含平台角色，并且至少包含 `MERCHANT_OWNER` 或 `MERCHANT_STAFF`。
  - 角色组合通过后再执行权限 SQL：账号角色关系依次连接启用的角色、角色权限关系和启用的权限，去重并按权限码排序。得到的集合写入 principal，表示这个账号当前能进入哪些后台功能。
  - 任一账号、商户、scope 或角色条件不满足，`resolveAccount` 返回 null；登录调用方把它与密码失败一样计数并拒绝，不会签发 token。
- 身份组合有效后，服务先删除“用户名加 IP”失败 key，再生成 32 位随机 token，把 `accountId:当前authVersion` 写入 `admin:login:token:{token}`，并按配置设置 TTL，默认 30 分钟。
  - 写 session 使用一条带过期时间的 Redis SET。单 IP 总尝试计数不会随成功登录提前清除；前面的失败 key 删除若抛异常，也不会继续签发 session。
  - Redis session 不保存角色和权限；后续请求需要重新回 MySQL 加载当前值。服务随后单独更新 `last_login_time`，再向客户端返回 token、TTL 和 principal，本次登录请求结束。
  - session SET 和最近登录时间更新不在共同事务中；如果 session 已写而 MySQL 更新最近登录时间失败，请求会报错，但当前实现没有回删刚写入的 token。

## 五、后台请求从 session 恢复到业务方法

后台后续请求在 `Authorization: Bearer <token>` 中携带 token，希望用当前账号、权限和数据范围执行管理操作。请求先恢复后台 principal，再检查入口权限，最后才进入业务 Service。

- `/admin/**` 请求先进入顺序为 0 的后台会话拦截器。它只接受 Bearer 方案，并要求 token 符合 32 位十六进制格式。
  - 提取时会 trim 整个 Header，`Bearer ` 前缀大小写不敏感，再 trim 后面的 token。没有 Bearer、使用其他方案或 token 为空时不建立 principal；非 32 位小写十六进制 token 会由 `resolve` 在查询 Redis 前拒绝。
- token 格式正确时，`AdminSessionService` 从 Redis 取出 `accountId:issuedAuthVersion`，再按 accountId 回 MySQL 重新检查账号状态、商户状态、scope 和角色组合，并加载当前权限。
  - Redis key 是 `admin:login:token:{token}`。值为空表示不存在或已过期，直接返回 null；值按冒号切分后必须恰好得到可解析的 accountId 和 issuedAuthVersion，格式损坏时删除这个 key 并返回 null。
  - `resolveAccount` 随后执行与登录时相同的账号、商户、有效角色和有效权限查询，构造全新的 principal。它返回 null，或当前 `authVersion` 不等于签发版本时，`resolve` 都删除旧 token 并返回 null。
  - Redis session 只说明 token 当初属于哪个账号以及签发版本，MySQL 才说明账号现在是否有效、现在拥有什么权限和商户范围。
- 所有检查通过后，服务先把后台 session TTL 续到配置值，再把新构造的 `AdminPrincipal` 返回给拦截器，由拦截器放入 `AdminPrincipalHolder`。
  - `resolve(token, true)` 先刷新 Redis TTL 再把 principal 返回给拦截器；拦截器只有拿到非 null principal 才写入 ThreadLocal。token 过期、账号或商户停用、角色组合非法时不建立身份，下一层返回 401。
  - Redis 或 MySQL 在恢复身份时抛运行时异常，当前会进入统一异常处理并返回 500，不会按普通 token 失效返回 401。
- 请求接着进入顺序为 1 的后台授权拦截器。它先处理 OPTIONS、检查 principal 和 MVC `HandlerMethod`，通过后才读方法上的 `@RequireAdminPermission`，方法没有时再读 Controller 类上的声明。
  - OPTIONS 预检直接放行。没有 principal 返回 401；目标不是 MVC `HandlerMethod`、两处都没有权限声明，或已有身份但缺少指定权限，返回 403。
  - 注解值为空只要求有效后台身份；带 `shop:write` 等权限码时，principal 必须含有完全相同的当前权限。
- 入口权限通过后，请求才进入 Controller 和业务 Service。Service 继续拿 principal 中的 scope 和 merchantId 限定资源范围，权限注解本身不会判断某个资源属于哪家商户。
- 请求完成后，会话拦截器删除 `AdminPrincipalHolder`，避免线程复用时沿用上一位后台账号。
  - `POST /admin/auth/logout` 也是受保护请求：先恢复 principal，再删除当前后台 session；删除异常落到 500，不能视为已经登出。

## 六、商户修改店铺时，范围怎样传到最终更新

假设商户 A 的 `merchantId=21`，账号已有 `shop:write`，现在调用 `PUT /admin/shops/7` 修改自己的店铺。请求带来路径中的 shopId 和允许修改的字段，希望 MySQL 只更新当前商户范围内的这一行。

- 后台会话链先恢复出 `AdminPrincipal(accountId=?, merchantId=21, scope=MERCHANT)`，权限拦截器再确认当前仍有 `shop:write`，通过后 Controller 把 shopId 7 和更新 DTO 交给 `AdminCatalogService.updateShop`。
  - 更新 DTO 只包含名称、类型、图片、地址、坐标、均价或营业时间等受控字段，没有 merchantId。
- `updateShop` 开启 MySQL 事务，先调用 `requireScopedShop(7, true)` 定位并锁定目标店铺。
  - 商户账号执行 `SELECT ... WHERE id=7 AND merchant_id=21 FOR UPDATE`，同时确认资源归属并锁住这一行。
  - 平台账号执行 `SELECT ... WHERE id=7 FOR UPDATE`。两条 SQL 都返回完整的原店铺，后面要用原 `typeId` 判断提交后需要清理哪个 GEO 集合。
  - 查不到就返回 404，不透露该 ID 是否属于其他商户，也不会继续执行更新。
- 查到以后，服务把请求交给 `controlledShopPatch` 校验并制作只含本次变更的 `Shop` patch。
  - 请求体不能为 null；名称、图片、地址只要提交就必须是非空文本并 trim，area 和营业时间的空白值转成 null，随后不会作为更新字段写入。均价不能为负数，经纬度必须同时提交，并分别落在经度 `[-180,180]`、纬度 `[-90,90]`。
  - 所有允许字段都未提供可写入的非空值时抛出 400；这里没有逐字段比较新旧值，因此提交与原值相同的内容也能通过这一步。DTO 没有 merchantId，patch 也不会设置 merchantId，所以普通更新不能改变归属。
- 校验通过后，服务给 patch 设置 shopId，再执行最终 `UPDATE`。商户账号的 UpdateWrapper 同时带 `id=7 AND merchant_id=21`；平台账号只带 `id=7`，MyBatis-Plus 只更新 patch 中的非空字段。
  - 影响行数不是 1 就抛出 404，当前事务回滚；影响 1 行才把 patch 中的非空值合并到前面锁出的 `existing` 对象，形成准备返回的新店铺视图。
- 数据库写入成功后，服务在当前事务中注册 `afterCommit` 回调并返回更新后的店铺对象。Spring 提交事务以后，MySQL 中的字段才成为正式结果，随后回调删除 `cache:shop:{shopId}`；如果原 typeId 非空还从旧 `shop:geo:{typeId}` 移除店铺，当前类型和坐标完整时再加入新 GEO 集合。
  - Redis 缓存或 GEO 同步失败只记录日志和指标，不会反向撤销已经提交的店铺更新。
- 平台账号走同一个 Controller 和 Service，但它的 principal 是 PLATFORM 且 merchantId 为空，所以前面的查询和更新都只按 shopId 定位。
  - 对“修改已有店铺”这个入口，平台账号用路径里的 shopId 指向目标店铺，不另外提交目标 merchantId；店铺已有的 merchantId 表示它属于谁，更新 DTO 不能修改归属。
  - 创建店铺时平台账号才从创建请求取得目标 merchantId。另一个归属接口也只允许把 `LEGACY_UNASSIGNED` 历史店铺认领给启用商户，已经归属的店铺禁止换绑。

## 七、后台账号变化怎样影响已有会话

改密、停用员工和调整角色并不是后台业务请求中的固定后续步骤，它们各有自己的触发入口。共同效果是在下一次 HTTP 身份恢复或 WebSocket 发送前重验时改变结果。

### 账号自己修改密码

已登录账号调用 `PUT /admin/auth/password`，带来原密码和新密码，希望修改凭据并让旧版本会话失效。

- 请求先走普通后台会话与授权链。`AdminAuthController` 类上的空权限注解表示只要求有效后台身份；通过后把当前 principal、原密码和新密码交给 `changePassword`，方法开启 MySQL 事务。
- 服务先要求新密码至少 12 个 Java 字符，并且 UTF-8 编码不超过 BCrypt 的 72 字节边界；随后按 principal.accountId 读取账号。
  - 账号必须仍然启用，原密码非空且不超过 256 个字符，并能匹配当前 passwordHash；否则返回 400“原密码不正确”。
  - 新密码如果还能匹配当前 Hash，说明与原密码相同，也返回 400。
- 校验通过后，服务计算新 BCrypt Hash，执行条件更新：`WHERE id=:accountId AND auth_version=:currentVersion AND status=1`，同时写新 Hash 并令 `auth_version=auth_version+1`。
  - 影响行数不是 1，说明校验到写入之间账号版本或状态已变化，事务以 409 结束；影响 1 行则事务提交，密码和新版本一起成为数据库事实。
- 当前 Redis token 不会在改密事务中主动删除。下一次 `resolve` 仍读到旧 issuedAuthVersion，与数据库新版本比较失败后才删除这枚 token 并返回 401；账号需要用新密码重新登录。

### 有管理权限的账号停用员工

具有 `account:manage` 的平台或商户账号调用 `PUT /admin/accounts/{id}/status`，带来目标 accountId 和状态 1 或 2，希望在允许的数据范围内启用或停用员工。

- 入口先要求当前 principal 具有 `account:manage`，Service 再拒绝空状态、约定外状态、空 accountId 和修改当前登录账号自身。
- 服务按目标 accountId 查询账号；商户 principal 还在同一查询中增加自己的 merchantId，平台 principal 不加商户条件。
  - 范围内查不到返回 404。查到后再加载目标账号角色；平台管理员和商户主账号不能通过这个员工接口变更状态，直接返回 400。
- 服务取出目标账号当前 authVersion，执行 `UPDATE tb_admin_account SET status=:status, auth_version=auth_version+1 WHERE id=:id AND auth_version=:oldVersion`。
  - 商户操作者还会在同一 SQL 中带自己的 merchantId；影响行数不是 1 返回 409，影响 1 行后事务提交。
- 目标账号已有 Redis session 仍可能暂时存在。它下一次 resolve 时要么先因 `status!=1` 失败，要么因版本不同失败，随后 token 被删除并返回 401。

### 角色或权限关系发生变化

当前源码没有通用的角色编辑 HTTP 入口。若账号角色关系、角色状态、角色权限关系或权限状态由受控数据库变更或后续管理能力调整，Redis session 也不会保存旧权限快照。

- 下一次 resolve 会重新执行角色和权限 SQL。撤掉某一项 `shop:write` 后，账号仍能构造 principal，但权限集合不再包含它，入口权限拦截器返回 403。
- 如果变化使平台账号失去 `PLATFORM_ADMIN`，或使商户账号既没有 OWNER 也没有 STAFF、混入平台角色，`resolveAccount` 会返回 null；外层删除 token，后台请求返回 401。
- 数据库变化不会主动扫描已经开始处理的请求，也不会立即遍历所有空闲 WebSocket。HTTP 在下一次请求恢复身份时生效，WebSocket 在下一次尝试发送前生效。

### 放到几个具体情形里理解

- 没登录的消费者请求 `GET /shop/7` 时，身份恢复层不查 Redis，登录要求层按公开 GET 放行；请求 `POST /voucher-order/seckill/7` 时则在 Controller 前返回 401。
- 两个请求同时提交同一个正确验证码时，先执行的 Lua 删除验证码并返回 1，后执行的脚本只能读到不存在并返回 0，因此只有前一个请求继续查建用户。
- 商户 A 有 `shop:write`，却把 URL 中的 shopId 换成商户 B 的 99。入口权限只证明它能做“修改店铺”这类操作，`id=99 AND merchant_id=21` 的加锁查询仍返回空，流程以 404 结束。
- 请求线程先服务用户甲，完成阶段会删除 `UserHolder`；后台链也会删除自己的 `AdminPrincipalHolder`。线程之后交给用户乙时，不会继承甲的身份。

## 八、WebSocket 身份是独立的补充流程

WebSocket 只在客户端需要在线提示时另外建立连接。这里说明认证和路由；握手或发送成功都不代表秒杀、发券或其他业务已经成功。

### 消费者用现有 token 建立连接

消费者连接 `/ws/connect?token=...`，直接带来已有的长期消费者 token，希望按自己的 userId 登记连接。

- 握手拦截器读取 `login:token:{token}` Hash，解析出 userId，再把 userId、token 和 USER 类型写入 WebSocket session attributes。
  - 它先要求请求是 Servlet 握手请求，再从查询参数读取 token。token 为空直接拒绝；有值就读取整个 Redis Hash，填成 `UserDTO` 并取得 id。
  - Hash 为空、DTO 中没有 id，或 Redis 抛异常时都返回 null，握手不建立连接。异常这里只记录日志并按认证失败处理；这次读取不刷新消费者 session TTL。
  - 解析成功后，attributes 中保存 `userId`、原 token 和 `connectionType=USER`，后面的 Handler 不需要再从 URL 解析身份。
- 连接建立后，Handler 用最多 10 秒发送时间和 256 KiB 缓冲上限包装 session，再按 userId 写入当前 JVM 的 `userSessions`。
  - 同一 userId 新建连接会替换并关闭旧连接。
- 当前实例分别订阅 `ws:seckill:[0-9]*` 和 `ws:voucher-grant:[0-9]*`。监听器从频道后缀截出 userId，转成 Long 后，把消息体交给 `sendToUser(userId, body)`；后缀不是数字只记录日志，不尝试路由。
- `sendToUser` 先按 userId 找本机连接并确认连接仍打开，再从 attributes 取出登记时的 userId 和 token，用同一个 Hash 解析方法重验 token，并要求解析结果仍等于目标 userId。
  - 找不到连接或连接已关闭就返回 false。用户已登出、会话过期或 Redis 重验异常时也不发送，而是从连接表移除并用策略违规状态关闭。
  - 身份仍有效才调用 `sendMessage` 并返回 true；发送抛异常时移除连接、用服务端错误状态关闭并返回 false。
  - 空闲 WebSocket 不会刷新 session，也不会在会话过期的一刻被主动扫描；下一次发送才触发重验和关闭。

### 后台用 Bearer token 换一次性 ticket

后台不能直接把长期 Bearer token 放进 WebSocket URL。它先通过已认证 HTTP 请求取得短 ticket，再用 ticket 发起握手。

- 后台先调用 `POST /admin/auth/ws-ticket`。普通后台链恢复当前 principal，入口确认有 `order:realtime`，Controller 随后再次 resolve Bearer token。
  - `issueWebSocketTicket` 使用 `resolve(token,true)` 再次读取 session、回库重建当前 principal 并刷新后台 session TTL；principal 为空或已经没有实时权限就返回 null，Controller 转成 401。
  - 通过后生成 32 位随机 ticket，用一条带 30 秒 TTL 的 Redis SET 写入 `admin:ws:ticket:{ticket}=后台token`，再返回 ticket 和 30 秒有效期。Redis 写入异常没有单独映射，当前落到统一 500。
- 客户端连接 `/ws/admin/connect?ticket=...`，Lua 对 ticket 执行一次 GET 和 DEL，取出原后台 token。
  - Java 先要求 ticket 是 32 位小写十六进制；不符合就不调用 Redis 并返回 null。格式通过后，用 `admin:ws:ticket:{ticket}` 作为唯一的 `KEYS[1]` 调用 `consume_admin_ws_ticket.lua`。
  - Lua 先 GET ticket key；不存在或已过期返回 nil。存在就保存其中的后台 token、删除 key 并返回 token，所以第一个握手会消费 ticket，重复握手拿不到。
  - 消费 ticket 和恢复后台 principal 不是一个共同原子步骤，ticket 取出后即使后续检查失败也不会恢复。Lua 或后面的依赖抛异常时，握手拦截器记录日志并返回 401。
- 握手拦截器用取出的长期 token 重新加载当前账号，但不刷新后台 session TTL，并再次检查 `order:realtime` 和 scope。通过后把 accountId、merchantId、scope、token 和 ADMIN 类型写入连接属性。
  - 这里调用 `resolve(token,false)`，仍会读取 Redis session、回 MySQL 重建账号和当前权限，只是不执行续期。principal 无效返回 401；身份有效但缺 `order:realtime` 返回 403。
  - scope 还必须满足：平台 principal 的 accountId 非空且 merchantId 为空；商户 principal 的 accountId、merchantId 都非空。否则返回 403。
  - 通过后 attributes 保存 accountId、可选 merchantId、scope、长期 token 和 `connectionType=ADMIN`，这些值用于登记分组与发送前比较。
- 连接建立后同样先包装 session。平台账号要求 scope 为 PLATFORM 且 merchantId 为空，按 sessionId 登记到 `platformAdminSessions`；商户账号要求 scope 为 MERCHANT 且 merchantId 非空，登记到 `merchantAdminSessions[merchantId][sessionId]`。
  - attributes 与这两种组合不一致时不登记，直接用策略违规状态关闭。
  - 发往商户 21 的频道只查 merchantId 21 的本机连接，不会退化成向所有商户广播。
- Redis Pub/Sub 用 `ws:seckill:admin:platform` 路由平台事件，用 `ws:seckill:admin:merchant:{merchantId}` 路由精确商户事件。监听器从商户频道后缀解析 merchantId，再选择对应本机连接表；后缀非法只记录日志。
- Handler 遍历选中的连接表：关闭的连接先移除；打开的连接从 attributes 取长期 token 和登记时的 accountId，再调用 `resolve(token,false)` 进行发送前重验。
  - principal 必须仍存在、accountId 相同、仍有 `order:realtime`，并与本次连接表要求的 scope 相同。平台连接还要求当前 merchantId 为空；商户连接还要求当前 merchantId 等于频道的 merchantId。
  - 账号停用、改密、角色撤权、商户停用、session 过期或身份字段变化都会使重验失败；Redis/MySQL 抛异常也按失败关闭。Handler 移除连接并用策略违规状态关闭，不发送这条消息。
  - 全部匹配才执行 `sendMessage`；发送异常时移除连接并用服务端错误状态关闭。

后台撤权或会话过期以后，系统没有任务主动扫描所有空闲连接。只有下一条消息尝试发送时才重验并关闭，所以空闲连接可能暂时保持打开。消费者和后台握手都受显式 Origin 允许列表约束；连接表只在当前 JVM，实例退出会丢失。Redis Pub/Sub 不保存离线消息，`sendMessage` 返回成功也不证明浏览器已经展示或用户已经读取。

## 九、从源码反查这些判断

- 消费者公开范围与两个拦截器顺序：[WebConfig](../../../src/main/java/com/localdeals/config/WebConfig.java)、[RefreshTokenInterceptor](../../../src/main/java/com/localdeals/interctptor/RefreshTokenInterceptor.java)、[LoginInterceptor](../../../src/main/java/com/localdeals/interctptor/LoginInterceptor.java)、[UserHolder](../../../src/main/java/com/localdeals/utils/UserHolder.java)、[RegexPatterns](../../../src/main/java/com/localdeals/utils/RegexPatterns.java)。
- 验证码消费、查建用户、会话和登出：[UserController](../../../src/main/java/com/localdeals/controller/UserController.java)、[UserServiceImpl](../../../src/main/java/com/localdeals/service/impl/UserServiceImpl.java)、[验证码签发 Lua](../../../src/main/resources/lua/issue_login_code.lua)、[验证码消费 Lua](../../../src/main/resources/lua/consume_login_code.lua)、[V1 用户手机号唯一索引](../../../src/main/resources/db/migration/V1__baseline_schema.sql)。
- 后台登录、可信 IP 与每次重建权限：[AdminAuthController](../../../src/main/java/com/localdeals/controller/AdminAuthController.java)、[AdminAuthService](../../../src/main/java/com/localdeals/service/AdminAuthService.java)、[AdminSessionService](../../../src/main/java/com/localdeals/service/AdminSessionService.java)、[TrustedClientIpResolver](../../../src/main/java/com/localdeals/service/TrustedClientIpResolver.java)、[AdminAccountMapper](../../../src/main/java/com/localdeals/mapper/AdminAccountMapper.java)、[登录计数 Lua](../../../src/main/resources/lua/record_admin_login_failure.lua)。
- 后台入口权限和线程上下文：[AdminSessionInterceptor](../../../src/main/java/com/localdeals/interctptor/AdminSessionInterceptor.java)、[AdminAuthorizationInterceptor](../../../src/main/java/com/localdeals/interctptor/AdminAuthorizationInterceptor.java)、[RequireAdminPermission](../../../src/main/java/com/localdeals/auth/RequireAdminPermission.java)、[AdminPrincipal](../../../src/main/java/com/localdeals/dto/AdminPrincipal.java)。
- 店铺修改的归属锁与最终更新：[AdminShopController](../../../src/main/java/com/localdeals/controller/AdminShopController.java)、[AdminCatalogService](../../../src/main/java/com/localdeals/service/AdminCatalogService.java)、[ShopMapper](../../../src/main/java/com/localdeals/mapper/ShopMapper.java)。
- 改密、员工状态与版本条件更新：[AdminManagementController](../../../src/main/java/com/localdeals/controller/AdminManagementController.java)、[AdminManagementService](../../../src/main/java/com/localdeals/service/AdminManagementService.java)、[AdminAuthService](../../../src/main/java/com/localdeals/service/AdminAuthService.java)、[AdminAccountMapper](../../../src/main/java/com/localdeals/mapper/AdminAccountMapper.java)。
- 后台表、店铺商户字段和凭据版本：[V5 商户与 RBAC](../../../src/main/resources/db/migration/V5__merchant_admin_rbac.sql)、[V6 authVersion](../../../src/main/resources/db/migration/V6__admin_auth_version.sql)、[默认配置](../../../src/main/resources/application.yaml)。
- 两类 WebSocket 握手、登记和发送前重验：[WebSocketConfig](../../../src/main/java/com/localdeals/config/WebSocketConfig.java)、[消费者握手](../../../src/main/java/com/localdeals/websocket/WebSocketAuthInterceptor.java)、[后台握手](../../../src/main/java/com/localdeals/websocket/AdminWebSocketAuthInterceptor.java)、[连接登记与路由](../../../src/main/java/com/localdeals/websocket/SeckillWebSocketHandler.java)、[ticket 消费 Lua](../../../src/main/resources/lua/consume_admin_ws_ticket.lua)。

这次只依据当前源码、配置、V1/V5/V6 迁移和相关测试反查控制流，没有启动服务、运行迁移或执行测试。旧笔记中“消费者 token 与后台 token 格式不同”的说法应改为“字符串外形可以相同，Header 规则、Redis 命名空间、上下文和拦截器范围不同”；“角色变化靠 authVersion 使旧 token 失效”应改为“角色和权限每次 resolve 实时重载，改密和状态更新才明确递增 authVersion”；“普通后台会话依赖异常返回 503”也不准确，当前登录限流异常明确是 503，而普通后台 session 恢复异常会落到统一 500。
