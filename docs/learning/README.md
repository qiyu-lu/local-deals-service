# 项目学习文档

这组文档用于理解当前 checkout 的业务闭环、数据真相、失败恢复，并将源码证据提炼为可用于简历和面试的工程特色。项目及其原始版本由作者完整实现；文档不再审计逐模块个人归属，但仍严格区分当前实现、本地/历史验证与生产效果。

当前阅读基线：`codex/platform-hardening`，HEAD `9525b65e7aa88fa8e04670f1054cea5d5d752098`，Java 8 / Spring Boot 2.3.12.RELEASE。M7-RC 与 Pre-M8 本地证据已经记录，M8、Java 17、Spring Boot 3 尚未开始。

## 推荐顺序

第一次复习只需要按下面顺序阅读：

1. [补充模块面试讲述手册 §1](08-interview-playbook.md#1-项目总览这不是功能拼盘而是围绕一致性边界做的本地生活平台)：先用概括版建立项目定位、身份边界和数据角色。
2. [三条核心业务面试讲述稿](09-core-business-chain-review.md)：依次掌握秒杀、点赞热榜和营销发券的概括版、完整版与高频追问。
3. [补充模块面试讲述手册](08-interview-playbook.md)：再按需准备登录、读取与搜索、关注流、后台权限和实时通知。
4. [系统地图与真相边界](01-system-map.md)：需要核对对象职责和源码证据时再进入技术底稿。

需要回答源码细节或核验证据时，再进入：

- [商户后台、RBAC 与 WebSocket 会话隔离](02-admin-rbac-chain.md)
- [秒杀下单、PROCESSING 与恢复](03-seckill-order-chain.md)
- [点赞 Outbox 与热榜](04-blog-like-hot-rank-chain.md)
- [营销发券、任务与通知](05-marketing-grant-chain.md)
- [四项项目特色、证据与边界](06-evidence-and-ownership.md)
- [正式简历与面试材料](07-resume-and-interview.md)

## 先记住的判断规则

- MySQL 保存长期业务事实和约束：商户范围、订单、点赞关系、活动额度、发券流水、签到和 Outbox 都在这里落地。
- Redis 保存会话、缓存、限流窗口、秒杀预约/状态、可重建热榜和 Pub/Sub；这些对象分别有明确协议，不能一概称为“缓存”或“最终真相”。
- RocketMQ 是秒杀异步交付和事务消息边界，不是订单真相源；消费成功必须回到 MySQL 与 Redis 状态协议核对。
- Elasticsearch 是搜索读模型；Canal→RocketMQ→ES 的完整链路没有在本 checkout 的 evidence 中被证明。
- WebSocket 是实时提示通道；发券和秒杀的持久事实仍通过 MySQL 查询兜底，通知 `PUBLISHED` 只表示 Redis publish 已被调用成功且对应数据库 marker 已提交。
- HTTP 200、进程存活、测试退出码、局部平均值和单次 drain 时间，都不能单独证明业务正确性或生产 SLA。

## 文档中的证据标签

`CURRENT` 表示当前源码或当前设计契约；`EVIDENCE` 表示仓库记录的既有、限定环境证据；`HISTORICAL` 表示旧方案或历史执行记录；`BOUNDARY` 表示未验证或明确不在范围内的结论。历史测试不会被写成当前会话刚刚复现。

作者前提允许在简历中直接使用“设计、实现、构建、完善”等工程动词；证据标签仍约束结论强度，不能据此使用“完全避免”“百分之百送达”“生产级高可用”等绝对表述。

## 工作区检查口径

`git diff --check` 只检查 tracked diff；当前未跟踪的 `docs/learning/` 不在普通 `git diff` 的检查范围内。未跟踪学习文档需另行执行 Markdown 相对链接、源码锚点和尾随空白检查，不应为了检查而提前暂存文件。
