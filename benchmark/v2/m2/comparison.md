# M2 落库复测：订单闭环对消费吞吐的影响（同场对照 `v2.0-m1`）

> 数字全部来自 [summary.csv](summary.csv)（`kind=drain` 行）。`60c541e` = tag `v2.0-m1` 的 jar，
> `ecfefbe` = M2 分支的 jar。原始 k6 JSON 与火焰图在 `raw/`（gitignore）。

## 1. 为什么要测

M2 不以性能为目标，但每一单在消费侧多做了三件事：`INSERT ... SELECT` 联表快照价格与商户、
写一行 `order_state_log`、同步投递一条定时关单消息。需要知道这些额外工作有没有拖慢落库。

## 2. 方法

| 项 | 值 |
| --- | --- |
| 分核 | 应用 `0-3,8-11`；MySQL/Redis/RocketMQ/ES `4-5,12-13`；k6 `6-7,14-15`（与 M0/M1 相同） |
| 依赖 | MySQL 8.0、Redis 6.2、**RocketMQ 5.3.2**（M2 起；两组相同） |
| 库 | `v2.0-m1` 的 jar 在另一个 schema `local_deals_m1` 上跑（它写 `tb_voucher_order`，而 M2 的 V14 已把这张表迁走），同一 MySQL 实例 |
| 负载 | `drain 20000 2000`：2 万单以 2000 req/s 放入，看 MySQL 追平的速度；每次启动后先 500 req/s 预热 30 s |
| 顺序 | 对照 / M2 交替 5 轮；M2 以 `pay-timeout=24h` 启动：每单照常投递定时消息，但压测期间不会触发关单 |

## 3. 结果（2 万单全部接收、全部落库）

| 轮次 | `v2.0-m1` 单/s | M2 单/s | `v2.0-m1` 追平 s | M2 追平 s |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 224.6 | 178.2 | 70.4 | 88.5 |
| 2 | 270.6 | 176.8 | 68.4 | 102.4 |
| 3 | 189.4 | 139.1 | 97.8 | 121.8 |
| 3′（带 wall 采样） | — | 359.8 | — | 62.1 |
| 4 | 156.3 | 427.2（带采样） | 121.0 | 44.0 |
| 5 | 151.2 | 173.3（带采样） | 122.0 | 117.1 |
| 中位数 | **189.4** | **177.5** | 97.8 | 95.5 |

**测不出差别。** 前 3 轮 M2 每一轮都低于对照，看起来像回退了 20%；多跑 3 轮后 M2 出现
360–427 单/s，比对照任何一轮都快。两组区间大幅重叠（151–271 vs 139–427），中位数差 6%，
远小于同组内的波动。M2 的结果还呈双峰（约 175 或 360+），这台宿主机上同时跑着其他项目的
容器，负载在 3–4 之间，这种波动在 M1 复测里也出现过（100–224）。结论只能是：没有可测的回退，
也不声称变快。

## 4. 时间花在哪（M2，wall 模式，两次采样各 40 s）

消费线程 `SeckillOrderConsumer.onMessage` 的墙钟时间：

| 部分 | 第 4 轮 | 第 5 轮 |
| --- | ---: | ---: |
| `createPendingOrder` 执行 SQL | 49.8% | 49.6% |
| 　其中 `UPDATE tb_seckill_voucher SET stock = stock - 1`（同一行） | 43.7% | 44.2% |
| 　其中 COMMIT | 4.7% | 4.5% |
| 　其中 `INSERT trade_order ... SELECT`（M2 新增的联表） | 0.7% | 0.4% |
| 　其中 `INSERT order_state_log`（M2 新增） | 0.5% | 0.3% |
| `createPendingOrder` 等连接池 | 23.6% | 23.3% |
| WebSocket 通知（补查 merchantId + pub/sub） | 25.0% | 26.2% |
| 定时关单消息同步投递（M2 新增） | 0.5% | 0.3% |
| Redisson 锁、Redis 校验/标记 Lua | 1.1% | 0.6% |

M2 新增的三件事合计不到 2%。瓶颈和 M0 火焰图一致：同一行库存上的行锁排队（D4）与由此放大的
连接池等待，外加通知里那次同步 DB 查询。这些都在 M4 的范围内（批量消费、合并扣库存、
通知不再逐单查库）。

## 5. 复现

```bash
DEPS_CPUS=4-5,12-13 scripts/stack.sh pin
git worktree add /tmp/m1 v2.0-m1 && (cd /tmp/m1 && mvn -q package -DskipTests)
scripts/bench.sh users 100000
LIMITS="--local-deals.traffic.seckill.activity-limit=100000 --local-deals.traffic.seckill.ip-limit=100000"
export APP_CPUS=0-3,8-11 K6_CPUS=6-7,14-15 MILESTONE=m2
# 对照：独立 schema，按旧表计数
STACK_SCHEMA=local_deals_m1 APP_JAR=/tmp/m1/target/local-deals-service-0.0.1-SNAPSHOT.jar APP_ARGS="$LIMITS" scripts/stack.sh app-start
STACK_SCHEMA=local_deals_m1 ORDERS_TABLE=tb_voucher_order BENCH_COMMIT=60c541e DURATION=30s STOCK=1000 scripts/bench.sh step 500
STACK_SCHEMA=local_deals_m1 ORDERS_TABLE=tb_voucher_order BENCH_COMMIT=60c541e scripts/bench.sh drain 20000 2000
scripts/stack.sh app-stop
# M2
scripts/stack.sh build
APP_ARGS="$LIMITS --local-deals.order.pay-timeout=24h" scripts/stack.sh app-start
DURATION=30s STOCK=1000 scripts/bench.sh step 500
scripts/bench.sh drain 20000 2000      # 采样：asprof -d 40 -e wall -o collapsed <pid>
scripts/stack.sh app-stop
```
