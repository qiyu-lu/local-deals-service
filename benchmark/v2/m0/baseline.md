# M0 基线与瓶颈分析（对照组）

> 代码：`v2/m0-baseline-cleanup` @ `9d9fa7e`（瘦身之后、任何优化之前）。所有数字来自
> [summary.csv](summary.csv)，原始 k6 JSON / 火焰图在 `raw/`（gitignore，只在本机）。
> 单机测得的是这台机器上的拐点和瓶颈位置，不是生产容量。

## 1. 环境与方法

| 项 | 值 |
| --- | --- |
| 机器 | AMD Ryzen 7 7700，8C/16T，30 GB；宿主机磁盘占用 93%（见第 5 节） |
| 分核（按物理核，SMT 兄弟为 N/N+8） | 应用 `taskset 0-3,8-11`（4C/8T）；MySQL/Redis/RocketMQ/ES 容器 `4-5,12-13`（2C/4T）；k6 容器 `6-7,14-15`（2C/4T） |
| 运行时 | 应用 jar 跑在 Dragonwell 8，`-Xms2g -Xmx2g`；MySQL 8.0、Redis 6.2、RocketMQ 4.9.4（`scripts/stack.sh` 隔离栈） |
| 压测模型 | k6 2.2.0 `constant-arrival-rate`（开环）：请求按目标速率到达，与系统响应快慢无关；系统跟不上时表现为延迟上升和 `dropped_iterations` |
| 场景 | 每档一张新秒杀券，库存 1000，每档 30 s；每个请求是不同用户（10 万预生成 token 轮转）→ 首秒售罄，其余 99%+ 是注定失败的请求，正是秒杀的真实形态 |
| 配置偏离 | `traffic.seckill.activity-limit` / `ip-limit` 调到 100000（上限）。原因：所有压测请求来自 127.0.0.1，默认 100/s 的 IP 限流和 300/s 的活动限流会让 99% 请求在 L0 就 429，测到的只是限流器。user-limit 保持默认 2/s |
| 预热 | 正式阶梯前以 500 req/s 跑 30 s（`raw/warmup-summary.csv`） |
| CPU 列 | k6 窗口内平均占用核数：应用取 `/proc/<pid>/stat`，容器取 cgroup `cpuacct.usage` 差值 |
| 落库速率 | 每秒轮询一次 `COUNT(*)`，取已接收订单从 10% 到 90% 落库之间的斜率 |

## 2. 准入接口阶梯（step）

| 目标 req/s | 实际 req/s | dropped | p50 | p95 | p99 | 应用 CPU | Redis | Broker | MySQL |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 500 | 500.0 | 0 | 0.9 ms | 1.1 ms | 1.3 ms | 0.37 | 0.07 | 0.11 | 0.04 |
| 1 000 | 1 000.0 | 0 | 0.8 | 1.0 | 2.9 | 0.60 | 0.10 | 0.16 | 0.03 |
| 2 000 | 1 999.9 | 0 | 0.7 | 1.2 | 9.3 | 0.97 | 0.16 | 0.24 | 0.03 |
| 5 000 | 4 999.8 | 0 | 0.6 | 5.3 | 21.0 | 1.98 | 0.28 | 0.46 | 0.03 |
| 10 000 | 9 985.5 | 378 (0.13%) | 1.3 | 25.8 | 63.3 | 3.82 | 0.45 | 0.75 | 0.04 |
| 15 000 | 14 034.7 | 28 658 | 73.2 | 214.7 | 449.8 | 4.91 | 0.45 | 0.82 | 0.05 |
| 20 000 | 15 858.2 | 120 726 | 222.1 | 331.2 | 366.8 | 5.36 | 0.51 | 0.92 | 0.05 |

- **拐点：约 1 万 req/s。** 10k 是最后一档 p99 < 100 ms、丢弃 < 1% 的；15k 时实际吞吐只到 14.0k，
  p99 跳到 450 ms；20k 时封顶在 **15.9k req/s**。
- 每档 1000 单全部被接收，无超卖（accepted = stock）。
- 15k 档的 196 个 HTTP 503 全部是 Broker 返回的
  `[TIMEOUT_CLEAN_QUEUE] broker busy, start flow control`，而此时 Redis 只用 0.45 核。
- k6 峰值约 2.7 核（容器共 4 线程），压测端不是瓶颈。

## 3. 消费落库速率（drain：2 万单以 2000 req/s 放入）

| 轮次 | 放入耗时 | 放入后追平耗时 | 落库速率（10%→90%） | MySQL CPU | 备注 |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 | 11 s | 155.6 s | 123.3 单/s | 0.08 | 前 50 s 同时在做 wall 采样；采样结束后仍只有约 116 单/s |
| 2 | 11 s | 35.8 s | 477.6 单/s | 0.27 | 无采样 |
| 3 | 11 s | 71.5 s | 264.3 单/s | 0.11 | 无采样 |

**中位数 264 单/s，区间 123–478 单/s。** 同样的输入、同样的代码，三轮相差 3.9 倍；MySQL CPU 始终
不到 0.3 核。吞吐不稳、DB 不忙，本身就是锁竞争的特征（见 4.2）。计划里写的「约 75 单/s」是 V1
JMeter 的口径；本次 300 req/s 的冒烟也测到 73 单/s（500 单，样本太小，不作为结论）。

## 4. 火焰图结论

### 4.1 准入端（10k req/s，itimer，15 s）

| 占比 | 位置 | 对应诊断 |
| ---: | --- | --- |
| 15.8% | `SeckillTrafficGuard` 独立 Lua | D2：第 1 次 Redis RTT |
| 1.9% | `RedisIdWorker.nextId` INCR（在准入之前，失败者也要消耗） | D2：第 2 次 Redis RTT |
| 10.6% | 事务消息本地事务里执行的准入 Lua | D1+D2：第 3 次 Redis RTT，而且被夹在 half message 与 end-tx 之间 |
| 5.3% + 6.1% | `sendMessageInTransaction` + RocketMQ 客户端线程 | D1：每个请求 1 条 half message + 1 次 end-transaction |
| 15.5% | Lettuce/netty IO | 以上 3 次 Redis 往返的 IO |
| 3.8% | token Hash 读取 + 每请求 `EXPIRE` 续期 | 登录拦截器，额外 2 次 Redis 命令 |

Broker 侧：`RMQ_SYS_TRANS_HALF_TOPIC` 的 offset 为 **578 713**，与累计 HTTP 请求数
一致（该时刻累计请求约 57.7 万，含冒烟与预热）；真正提交到 `seckill-order-topic` 的只有已接收的订单。Broker 当日写入 116 万条，约为请求数的
2 倍（half + op）。**D1 成立：half message 数 = 请求数，而不是成功数。**

### 4.2 消费端（drain 进行中，wall，20 s，只统计消费线程）

| 占比 | 位置 |
| ---: | --- |
| 44.7% | `UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE ...` 等待行锁（`ChainUpdate.update` → socket recv） |
| 24.3% | `@Transactional createVoucherOrder` 进入前等待 Hikari 连接 |
| 25.2% | `WebSocketNotifier.resolveMerchantId`：落库后在消费线程里同步查一次 DB，同样在等 Hikari 连接 |
| 4.7% | commit |
| 0.2% | Redisson `lock/unlock` |

- **D4 成立**：接近一半的消费时间在同一行库存上排队。连接池只有 10，而消费线程是 20，
  连接被行锁等待占住，导致另一半时间在等连接，吞吐由锁排队的形态决定，所以三轮波动很大。
- **D3 部分成立**：单条消费 + 每单一次事务确实是结构性瓶颈；但「每消息 Redisson 锁」在
  wall 采样里只占 0.2%（锁按订单/用户粒度，无竞争），实际代价不在锁本身。
- **新发现（计划未覆盖）**：成功通知为了补 merchantId，在消费线程里多了一次同步 DB 查询，
  与落库事务抢同一个连接池，占消费线程 25% 的时间。记录给 M4（批量消费时一并处理：
  通知改为批后异步，或把 merchantId 放进消息体）。

## 5. 结论（M3/M4 要打的点）

1. **准入**：单实例约 1 万 req/s 拐点、1.59 万 req/s 天花板；天花板处应用 CPU 5.4/8 线程，而
   Broker 在 1.4 万 req/s 时先出现流控。99% 请求是注定失败的，却每个都付出
   3 次 Redis RTT + 2 次 Broker RTT。M3 的目标是让失败请求 0 次 Broker、最多 1 次 Redis。
2. **落库**：123–478 单/s，瓶颈是热点行锁 + 连接池，不是 MySQL CPU。M4 的批量合并扣减
   （每批每券一次 `stock - n`）直接消除 44.7% 的行锁等待。
3. **诚实边界**：宿主机磁盘 93%，RocketMQ 4.9 默认 90% 就拒绝写入，隔离栈把
   `diskSpaceWarningLevelRatio` 调到 0.98 才能跑；宿主机上还有其他项目的容器在跑（负载约 2），
   未绑核，属于背景噪声。这台机器 8 物理核被三方瓜分，应用只有 4 物理核。

## 6. 复现

```bash
DEPS_CPUS=4-5,12-13 scripts/stack.sh up
scripts/stack.sh build
APP_CPUS=0-3,8-11 APP_ARGS="--local-deals.traffic.seckill.activity-limit=100000 --local-deals.traffic.seckill.ip-limit=100000" scripts/stack.sh app-start
scripts/bench.sh users 100000
K6_CPUS=6-7,14-15 scripts/bench.sh step 500 1000 2000 5000 10000 15000 20000
K6_CPUS=6-7,14-15 scripts/bench.sh drain 20000 2000
scripts/bench.sh profile 20 m0-admission-cpu            # 压测进行中执行
AP_EVENT=wall scripts/bench.sh profile 20 m0-consumer   # drain 进行中执行
```
