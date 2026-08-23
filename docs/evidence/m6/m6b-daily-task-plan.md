# M6B 每日签到任务与每日任务奖励计划

> HISTORICAL EXECUTION PLAN：本文保留阶段执行时的契约、状态与负面结果；最终结论以同目录 results 文档为准。

> 状态：M6B COMPLETED；M6 IN PROGRESS；M6C 未开始且未经授权

## 范围

只实现固定任务 `DAILY_SIGN_IN`：用户完成当天签到后，可以领取 `TASK` 活动中的一份每日奖励。签到事实和每日 grant 的幂等边界由 MySQL 保证，奖励继续复用 M6A `VoucherGrantService`。服务端按可配置业务时区（默认 `Asia/Shanghai`）计算日期，客户端不传日期或幂等键。

不实现任务定义 CRUD、任务 DSL、连续签到/补签、积分商城、定时任务、批量发券、MQ、通知 Outbox、核销、支付或退款；M6C（批量发放与通知 Outbox）未授权。

## 实施产物

- V10：`tb_sign(user_id, date)` 唯一事实；活动 `grant_mode=TASK`；grant `source=TASK_REWARD`、非空 `idempotency_key`；V9 历史 grant 回填 `ONCE`，唯一键调整为 `(campaign_id,user_id,idempotency_key)`。
- `/user/sign`、`/user/sign/count` 改为 MySQL 日期记录；新增 `POST /voucher-campaigns/{campaignId}/task-reward`，请求只接收 `expectedRuleVersion`。
- TASK 奖励在统一 grant 事务中校验当日签到、活动/版本/额度和已有标签资格；重复签到/领奖返回稳定幂等结果，跨业务日生成新签到和新 grant。
- 管理端可配置 TASK 活动且不显示手工发放；用户商铺详情显示 TASK 活动，并以“签到后领奖”按钮串联两个接口。
- 补齐 DTO、低基数 `task_reward` 指标、Java 8/安全单元/MVC/Node/admin build，以及专用 MySQL fresh/upgrade 和并发不变量证据。

结果见 `docs/evidence/m6/m6b-daily-task-results.md`。

## 停止线

真实测试只允许连接本次 M6B 专用 MySQL 端口；发现共享/未知地址立即停止并保留证据。不得通过降低并发或放宽断言获得 PASS；不得破坏 M6A `USER_CLAIM`/`ADMIN_GRANT` 的 `ONCE` 不变量。测试结束清零 M6B fixture，并按精确 run-id 清理容器和网络。
