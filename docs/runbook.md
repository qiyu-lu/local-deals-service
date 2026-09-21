# 运行手册

只写「怎么操作」。为什么这么设计在 [ADR](adr/)，数字与方法在 [压测报告](benchmark-report.md)。

## 1. 前置

- JDK 21（默认 `~/.jdks/temurin-21.0.12.1`，用 `APP_JAVA_HOME` 覆盖）、Maven 3.x。
- Docker Engine + Compose v2。
- 宿主机上要有 `mysql` 与 `redis-cli` 命令行客户端：压测脚本和对账直接调用它们。
- 环境变量：

  ```bash
  cp .env.example .env      # 把 change-me 换成本地密码；.env 不提交
  set -a; source .env; set +a
  ```

  首次创建平台管理员时临时填 `LOCAL_DEALS_ADMIN_BOOTSTRAP_USERNAME` / `_PASSWORD`，
  登录验证后从运行环境移除。仓库里没有默认管理员密码。

## 2. 开发栈

固定端口 3306 / 6379 / 9876 / 10911 / 9200，数据 bind mount 在 `./*-data/`：

```bash
docker compose up -d mysql redis namesrv broker elasticsearch
docker compose --profile dev up -d nginx canal-server   # 需要前端或 Canal 时
mvn spring-boot:run                                      # 业务 8083，management 127.0.0.1:18084
```

Flyway 在应用启动时迁移 `local_deals`。起来以后的最小只读检查：

```bash
docker compose ps
curl -fsS http://127.0.0.1:18084/actuator/health
curl -fsS http://localhost:8083/shop-type/list
```

管理端静态资源要先构建：`npm --prefix frontend/admin ci && npm --prefix frontend/admin run build`。
用户端 `http://localhost:8088/`，管理端 `http://localhost:8088/admin/`。

停：`docker compose --profile dev down`（加 `-v` 才删数据）。

## 3. Quick Start：走通一次秒杀 → 支付 → 核销

用户端和管理端两个身份，两种 token 头：消费者是 `authorization: <token>`，后台是
`Authorization: Bearer <token>`。

```bash
BASE=http://localhost:8083

# 3.1 消费者登录。本地开发把验证码打进日志：启动时加
#     --local-deals.auth.log-verification-code=true，再从应用日志里取那一行。
curl -s -X POST "$BASE/user/code?phone=13800000001"
curl -s -X POST "$BASE/user/login" -H 'Content-Type: application/json' \
  -d '{"phone":"13800000001","code":"<日志里的六位数>"}'      # -> data 即消费者 token
TOKEN=<上一步的 token>

# 3.2 后台登录，建一张秒杀券（shopId 取管理端已有的店铺）
curl -s -X POST "$BASE/admin/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"<admin>","password":"<password>"}'          # -> data.token
ADMIN=<上一步的 token>
curl -s -X POST "$BASE/admin/shops/1/vouchers/seckill" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"quickstart","payValue":100,"actualValue":200,"stock":10,
       "beginTime":"2026-01-01T00:00:00","endTime":"2030-01-01T00:00:00"}'   # -> 券 id

# 3.3 抢。活动开启期间先领秒杀令牌（开了令牌校验时必需），再下单。
curl -s "$BASE/voucher-order/seckill/<券 id>/token" -H "authorization: $TOKEN"
curl -s -X POST "$BASE/voucher-order/seckill/<券 id>" -H "authorization: $TOKEN" \
  -H "X-Seckill-Token: <上一步的令牌>"                          # -> data = 订单号
ORDER=<订单号>

# 3.4 订单是异步落库的，轮询到可见为止
curl -s "$BASE/voucher-order/status/$ORDER" -H "authorization: $TOKEN"

# 3.5 支付：预下单拿 payNo，再让 mock 渠道「付款」，渠道异步回调把订单改成 PAID
curl -s -X POST "$BASE/orders/$ORDER/pay" -H "authorization: $TOKEN"   # -> payNo / cashierUrl
curl -s -X POST "$BASE/mock-channel/payments/<payNo>/pay"

# 3.6 券进钱包，取核销码；后台核销
curl -s "$BASE/coupons" -H "authorization: $TOKEN"                     # -> verifyCode
curl -s -X POST "$BASE/admin/coupons/verify" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"verifyCode":"<verifyCode>"}'
```

不付款就等：超时关单会把库存还回去，订单转 `CLOSED`，同一个用户可以再买。

## 4. 隔离栈与集成测试

`scripts/stack.sh` 起的是另一套栈：独立 compose project、`127.0.0.1` 上的独立端口
（MySQL 23306 / Redis 26379 / NameServer 29876 / Broker 20911 / ES 29200 / 应用 28083）、
named volume，且拒绝碰开发栈的端口。

```bash
scripts/stack.sh up                      # 起依赖
REDIS_MODE=cluster scripts/stack.sh up   # 另起 3 主 3 从 Cluster（2700x 客户端 / 3700x 总线）
scripts/stack.sh env                     # 打印测试和应用要的变量，可 eval
scripts/stack.sh it '*IT'                # 用 Java 21 在这套栈上跑集成测试
scripts/stack.sh it 'Seckill*IT'         # 只跑一组
scripts/stack.sh build && scripts/stack.sh app-start    # 打包并起 jar
INSTANCE=2 scripts/stack.sh app-start    # 第 2 个实例：端口、pid、日志各自一份
APP_INSTANCES=3 scripts/stack.sh lb-start               # nginx 轮询三个实例
scripts/stack.sh status
scripts/stack.sh down                    # 删掉这套栈和它的 volume
```

单元与切片测试不需要任何栈：`mvn -o test`。

## 5. 压测

`scripts/bench.sh <场景>` 是无人值守的单一入口：它自己起一套 `STACK_ID=m3bench` 的栈
（2xxxx 端口），构建基线 tag 与当前 HEAD，预热、阶梯、落库、演练，结束后无论成败都拆掉自己的栈。

| 场景 | 内容 | 预计 |
| --- | --- | --- |
| `m3` | 准入漏斗对 `v2.0-m2`：九档阶梯、三轮落库、`kill -9` ×2 与先杀 Broker | 54 分钟 |
| `m4` | 批量消费对 `v2.0-m3`：落库 A/B + `batch-size:threads` 扫描 + 演练 | 46 分钟 |
| `m5` | 分桶 + Cluster 对 `v2.0-m4`：桶数扫描、应用 `kill -9`、**杀 Redis 主节点 ×2** | 33 分钟 |
| `m6` | 分库分表对 `v2.0-m5`（基线跑在 `local_deals_pre_m6`，它早于 V16） | 17 分钟 |
| `m6-consume` | 只扫消费参数 `batch:threads:pullIntervalMs:pullBatchSize` | 16 分钟 |
| `m8` | 一条命令四个场景：`m6`、`m8-fullchain`、`m8-scale`、`m8-window`，各自独立栈与独立 status，一个失败不影响后面 | 54 分钟 |

每个正式场景都有同名的 `-smoke`：参数缩小一档，单个 4–9 分钟，`m8-smoke` 四个合计约 20 分钟。
上表是各自最近一次完整运行的实测耗时（`run.log` 首尾两行），换机器会变。

单点用法：`scripts/bench.sh users 100000`（造用户与 token）、`scripts/bench.sh step 500 1000 2000`
（开环阶梯）、`scripts/bench.sh drain 20000 2000`（落库速率）、`scripts/bench.sh profile 30 name`
（async-profiler 火焰图）。

正式场景挂后台跑：

```bash
nohup scripts/bench.sh m8 > /tmp/m8-bench.out 2>&1 &
```

启动前的 preflight 会直接拒绝：工作区有未提交改动（结果必须对应一个 commit，冒烟可用
`ALLOW_DIRTY=1`）、端口被占（`ss -ltn`）、盘上不足 15 GB、没有 JDK 21、docker 不可用、
缺 `mysql` 或 `redis-cli`。同时只允许一个场景在跑。

结果读法，目录是 `benchmark/v2/m<n>/<时间戳>-<场景>/`：

1. `status` —— `RUNNING` / `DONE` / `FAILED: <阶段>: <原因>`。**不是 `DONE` 就不要分析。**
2. `manifest.json` —— commit、场景参数、分核方式、JVM 参数、机器与 loadavg、同时在跑的容器。
3. `summary.csv` —— 每次测量一行；`kill-drill.csv`、`redis-kill-drill.csv` 是演练；
   套件运行另有 `scenarios.csv` 记录每个子场景的成败。
4. `run.log` 与 `raw/` —— k6 原始 JSON 与日志。

CPU 分核：应用 `APP_CPUS`，依赖容器 `DEPS_CPUS`，k6 容器 `K6_CPUS`，各列是 k6 窗口内的平均核数。

## 6. 故障演练怎么复现

三种演练都在场景里，也可以单独跑（先按第 4 节把栈和应用起起来，再 `eval "$(scripts/stack.sh env)"`）：

```bash
benchmark/v2/scripts/kill-drill.sh 20000 2000 6      # 放行到第 6 秒 kill -9 应用，量收敛
BROKER_DOWN=1 benchmark/v2/scripts/kill-drill.sh 20000 2000 6   # 先杀 Broker 再杀应用
benchmark/v2/scripts/redis-kill-drill.sh 20000 2000 6           # 杀当前持有桶 0 的 Redis 主节点
REPLICA_SLEEP_S=20 benchmark/v2/scripts/redis-kill-drill.sh 20000 2000 6   # 复制空窗后再杀主
```

要杀的主节点是运行时查出来的（谁在服务桶 0），不是写死的名字。复制空窗那一轮先让副本睡着，
再从主节点 `CLIENT KILL TYPE replica` 断链，然后杀主。每个脚本打印一行 CSV：预占数、订单数、
Redis 库存、DB 库存、残留 PROCESSING、收敛秒数。

## 7. 按 traceId 排障

nginx 在调用方没带头时用 `$request_id` 生成 `X-Trace-Id`，同一个 id 贯穿边缘日志、应用日志和
`trade_order.trace_id`。

```bash
# 1) 边缘：这个请求打到了哪个实例、状态码和耗时
#    （lb-start 的 nginx 把日志写进 RUN_DIR，格式是 traceId upstream status request_time）
grep '<traceId>' benchmark/v2/run/m3bench/access.log

# 2) 应用：这个 id 的每一行日志（多实例时逐个实例的日志文件）
grep '<traceId>' benchmark/v2/run/m3bench/app*.log

# 3) 落库：反查订单，或者从订单反查 id
mysql -h 127.0.0.1 -P 24306 -u root -p<pw> local_deals \
  -e "SELECT order_no, status, trace_id FROM trade_order WHERE trace_id = '<traceId>'"
```

覆盖面：HTTP 全量；MQ → DB 这一跳只接了秒杀链与关单链。

最终对账常用的几条（压测脚本用的是同一组）：

```bash
python3 benchmark/v2/scripts/fixture.py order-stats <券 id>   # 总单数、在册、关单、已付、买家数
mysql ... -e "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = <券 id>"
redis-cli -c -h 127.0.0.1 -p 21000 -a "$LOCAL_DEALS_REDIS_PASSWORD" --no-auth-warning \
  GET "sk:{sk:b3}:stock:<券 id>"   # 分桶后每个桶一份，桶号 0..K-1
```

## 8. 常见问题

- **ES 起来了但分片不分配**：Elasticsearch 8.x 在 90% 高水位就停止分配。`df -h` 看盘，
  把数据目录挪到空盘并在 `.env` 里设 `LOCAL_DEALS_ES_DATA_ROOT`；Broker 同理用
  `LOCAL_DEALS_ROCKETMQ_STORE_ROOT`。
- **端口被占，尤其是重跑压测时**：本机临时端口段是 32768–60999，所以所有栈的端口都压在 32768
  以下；Redis Cluster 的总线端口固定是客户端端口 + 10000，两个基数必须正好差 1000。
  查占用：`ss -ltn "( sport = :24083 )"`。
- **Redis Cluster 起不来、卡在等待握手**：多半是总线端口宣告错了，见上一条。
- **`mvn test` 报 JDK 相关的编译错**：项目要 Java 21，检查 `JAVA_HOME` 与 IDEA 的
  Project SDK / Module SDK / Maven Runner JRE 三处是否都是 21。
- **压测拒绝启动说工作区有改动**：先提交。冒烟可以 `ALLOW_DIRTY=1`，改动会记进 `manifest.json`。
- **开发栈的 nginx 起不来、提示网络有活动端点**：这套栈的网络早于 compose 里固定的子网，
  compose 会想重建网络。要么整栈 `down` 再 `up`，要么临时用一个独立容器挂同样的卷验证配置。
