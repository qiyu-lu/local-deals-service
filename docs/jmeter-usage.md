# JMeter 使用说明

## 适用场景

本文档记录本项目优惠券秒杀接口的 JMeter 使用流程。JMeter 用于产生可复现的并发请求和压测报告；Apifox 更适合单接口冒烟验证，不适合作为正式压测工具。

当前秒杀接口：

```text
POST http://localhost:8083/voucher-order/seckill/{voucherId}
```

## 启动 JMeter

确认 JMeter 可用：

```bash
jmeter -v
```

打开图形界面：

```bash
jmeter
```

正式压测更推荐命令行模式，图形界面主要用于调试脚本和导出报告。

## 核心参数

JMeter 并发主要在 `Thread Group` 中设置：

| 参数 | 含义 | 本项目示例 |
| --- | --- | --- |
| Number of Threads (users) | 虚拟用户数，也就是并发线程数 | `100` 表示 100 个虚拟用户 |
| Ramp-Up Period (seconds) | 在多少秒内启动完所有线程 | `5` 表示 100 个线程在 5 秒内启动完 |
| Loop Count | 每个线程重复请求次数 | `1` 表示每个用户请求 1 次 |

总请求数大致等于：

```text
Number of Threads * Loop Count
```

例如：

```text
Number of Threads = 100
Ramp-Up = 5
Loop Count = 1
```

含义是：5 秒内启动 100 个虚拟用户，每个用户请求 1 次秒杀接口，总请求数约为 100。

## GUI 配置流程

### Test Plan

将 `Test Plan` 命名为：

```text
local-deals-service-seckill-benchmark
```

添加用户变量：

| Name | Value |
| --- | --- |
| host | `localhost` |
| port | `8083` |
| voucherId | 压测工具日志中的秒杀券 id |

### Thread Group

右键 `Test Plan`：

```text
Add -> Threads (Users) -> Thread Group
```

冒烟测试：

```text
Number of Threads: 1
Ramp-Up Period: 1
Loop Count: 1
```

100 并发基线：

```text
Number of Threads: 100
Ramp-Up Period: 5
Loop Count: 1
```

超卖压力测试可以把 `Number of Threads` 改成 `300` 或 `500`，库存仍保持 `100`。预期最终订单数仍不超过 100。

### CSV Data Set Config

右键 `Thread Group`：

```text
Add -> Config Element -> CSV Data Set Config
```

配置：

```text
Filename: /home/sd101t/IdeaProjects/hm-dianping/benchmark/tokens.csv
Variable Names: token,userId,phone
Delimiter: ,
Ignore first line: False
Recycle on EOF: True
Stop thread on EOF: False
Sharing mode: All threads
```

`tokens.csv` 不包含表头，每行格式：

```text
token,userId,phone
```

### HTTP Header Manager

右键 `Thread Group`：

```text
Add -> Config Element -> HTTP Header Manager
```

添加：

```text
authorization: ${token}
Content-Type: application/json
```

### HTTP Request Defaults

右键 `Thread Group`：

```text
Add -> Config Element -> HTTP Request Defaults
```

配置：

```text
Protocol: http
Server Name or IP: ${host}
Port Number: ${port}
```

### HTTP Request

右键 `Thread Group`：

```text
Add -> Sampler -> HTTP Request
```

配置：

```text
Name: seckill voucher
Method: POST
Path: /voucher-order/seckill/${voucherId}
```

Body 不需要填写。

### Listener

调试阶段可以添加：

```text
Add -> Listener -> View Results Tree
Add -> Listener -> Summary Report
Add -> Listener -> Aggregate Report
```

正式压测时尽量不要打开 `View Results Tree`，它会消耗内存并影响结果。正式数据建议使用 `Summary Report`、`Aggregate Report` 或命令行 HTML Dashboard。

## 压测前流程

1. 准备测试用户和 token：

```bash
mvn -Dtest=BenchmarkDataTool#prepareBenchmarkUsersAndTokens test
```

2. 重置秒杀库存、订单、Redis key 和 Stream：

```bash
mvn -Dtest=BenchmarkDataTool#resetSeckillBenchmarkData test
```

3. 确认 JMeter 中的 `voucherId` 与重置工具日志中的 voucherId 一致。

4. 先用 1 线程冒烟测试，再改成 100/300/500 并发。

## 报告导出

Summary Report 导出文件建议命名：

```text
docs/JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-100t-1l-summary-r1.csv
```

Aggregate Report 导出文件建议命名：

```text
docs/JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-100t-1l-aggregate-r1.csv
```

命名规则：

```text
YYYY-MM-DD-模块-实现版本-并发数t-循环数l-报告类型-r轮次.csv
```

例如：

```text
2026-05-19-seckill-reliable-stream-v1-100t-1l-summary-r1.csv
2026-05-19-seckill-reliable-stream-v1-100t-1l-aggregate-r1.csv
2026-05-19-seckill-reliable-stream-v1-5000t-5l-aggregate-r1.csv
```

其中：

- `100t` 表示 100 个 JMeter 线程。
- `1l` 表示每个线程循环 1 次。
- `summary` / `aggregate` 表示导出的 JMeter Listener 类型。
- `r1` 表示同一场景下的第 1 轮压测，重复跑同一场景时递增为 `r2`、`r3`。

每轮导出前要清空 Listener 结果，避免历史运行累计导致 Summary 和 Aggregate 样本数不一致。

## 命令行运行

### 自动化脚本

推荐使用仓库脚本统一执行压测、导出结果和校验异步落库：

```bash
set -a
source .env
set +a

scripts/run-seckill-benchmark.sh \
  --threads 100 \
  --loops 1 \
  --stock 100 \
  --user-count 1000
```

运行前需要保证后端服务、MySQL 和 Redis 已经启动，并在 `.env` 中配置好 `LOCAL_DEALS_*` 连接参数。脚本会自动从环境变量读取主机和端口，无需传入容器名。

脚本默认会使用 `/home/sd101t/.jdks/dragonwell-ex-1.8.0_472` 作为 `JAVA_HOME`。如果终端找不到 `mvn`，脚本会自动尝试 IDEA 内置 Maven：`/opt/idea/plugins/maven/lib/maven3/bin/mvn`。也可以显式指定：

```bash
scripts/run-seckill-benchmark.sh \
  --maven-cmd /opt/idea/plugins/maven/lib/maven3/bin/mvn \
  --java-home /home/sd101t/.jdks/dragonwell-ex-1.8.0_472 \
  --threads 5000 \
  --loops 5 \
  --stock 1000 \
  --user-count 5000
```

脚本会自动完成：

- 使用 `BenchmarkDataTool` 生成测试用户和 Redis token。
- 重置秒杀券库存、订单、Redis 库存 key、已购集合、Stream、dead-letter 和重试计数。
- 通过命令行 JMeter 运行 `docs/Summary Report.jmx`。
- 生成 JTL 原始文件、HTML Dashboard、Summary CSV 和 Aggregate CSV。
- JMeter 结束后立即轮询 Docker 中的 MySQL 和 Redis，记录异步落库追平耗时 `drain_ms`。
- 输出机器可读总表：`docs/JmeterTestSummary/<场景名>/metrics.csv`。
- 输出单轮证据快照：`docs/JmeterTestSummary/<场景名>/<run_id>-run-summary.md`。

常用参数：

| 参数 | 含义 | 示例 |
| --- | --- | --- |
| `--threads` | JMeter 线程数 | `5000` |
| `--loops` | 每个线程循环次数 | `5` |
| `--stock` | 本轮重置后的秒杀库存 | `1000` |
| `--user-count` | 生成的测试用户和 token 数 | `5000` |
| `--expected-orders` | 预期最终订单数；不传时默认取 `stock`、请求数、用户数的最小值 | `1000` |
| `--voucher-id` | 指定秒杀券 id；不传时自动创建或复用 benchmark 秒杀券 | `10` |
| `--round` | 同一场景的轮次；不传时自动使用当天同场景下一个可用轮次 | `1` |
| `--stream-key` | Redis Stream key | `stream.orders` |
| `--stream-group` | Redis Stream consumer group | `g1` |
| `--dead-letter-key` | Redis dead-letter Stream key | `stream.orders.dlq` |
| `--mysql-host` | MySQL 主机名（默认读取 `LOCAL_DEALS_DATASOURCE_URL`） | `localhost` |
| `--mysql-port` | MySQL 端口 | `3306` |
| `--redis-host` | Redis 主机名（默认读取 `LOCAL_DEALS_REDIS_HOST`） | `localhost` |
| `--redis-port` | Redis 端口 | `6379` |
| `--mysql-database` | MySQL 数据库名 | `local_deals` |
| `--maven-cmd` | Maven 可执行文件路径 | `/opt/idea/plugins/maven/lib/maven3/bin/mvn` |
| `--java-home` | Maven 运行使用的 JDK 路径 | `/home/sd101t/.jdks/dragonwell-ex-1.8.0_472` |
| `--skip-prepare` | 跳过测试用户和 token 生成 | 适合 token 已经准备好时使用 |
| `--skip-reset` | 跳过库存、订单和 Redis 重置 | 仅调试脚本时使用 |

输出文件命名示例：

```text
benchmark/2026-05-19-seckill-reliable-stream-v1-5000t-5l-r1.jtl
benchmark/report-2026-05-19-seckill-reliable-stream-v1-5000t-5l-r1/
docs/JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-5000t-5l-summary-r1.csv
docs/JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-5000t-5l-aggregate-r1.csv
docs/JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-5000t-5l-r1-run-summary.md
docs/JmeterTestSummary/seckill-reliable-v1/metrics.csv
```

`drain_ms` 的含义是：JMeter 进程结束后，到 MySQL 订单数达到预期值且 Redis Stream pending-list 清空之间的耗时。它不等同于接口响应时间，而是衡量异步消费者追平消息积压的指标。改造版还会校验 `stream.orders.dlq` 长度，dead-letter 非 0 时本轮结果判定为失败。

`summary.csv` 和 `aggregate.csv` 是 JMeter 派生结果，保留作原始证据；日常阅读优先看 `metrics.csv`、`run-summary.md` 和 `docs/benchmark-results.md` 的总表。

### 手动命令

冒烟测试：

```bash
mkdir -p benchmark
jmeter -n -t "docs/Summary Report.jmx" -l benchmark/seckill-smoke.jtl
```

生成 HTML Dashboard：

```bash
rm -rf benchmark/report-2026-05-19-seckill-reliable-stream-v1-100t-1l-r1
jmeter -n \
  -t "docs/Summary Report.jmx" \
  -l benchmark/2026-05-19-seckill-reliable-stream-v1-100t-1l-r1.jtl \
  -e \
  -o benchmark/report-2026-05-19-seckill-reliable-stream-v1-100t-1l-r1
```

HTML 报告中 `Statistics` 区域的字段：

| 字段 | 含义 |
| --- | --- |
| Throughput | 吞吐量 |
| Average | 平均响应时间 |
| 90th pct / 90% Line | P90 |
| 95th pct / 95% Line | P95 |
| 99th pct / 99% Line | P99 |
| Error % | HTTP 请求错误率 |

## 压测后校验

JMeter 的 `Error % = 0` 只代表 HTTP 层没有失败，不代表业务一定成功。秒杀压测后必须检查 MySQL 和 Redis。

订单数：

```sql
SELECT COUNT(*)
FROM tb_voucher_order
WHERE voucher_id = ${voucherId};
```

重复下单：

```sql
SELECT user_id, COUNT(*) AS cnt
FROM tb_voucher_order
WHERE voucher_id = ${voucherId}
GROUP BY user_id
HAVING cnt > 1;
```

DB 库存：

```sql
SELECT voucher_id, stock
FROM tb_seckill_voucher
WHERE voucher_id = ${voucherId};
```

Redis：

```bash
redis-cli -a "$LOCAL_DEALS_REDIS_PASSWORD" GET seckill:stock:${voucherId}
redis-cli -a "$LOCAL_DEALS_REDIS_PASSWORD" SCARD seckill:order:${voucherId}
redis-cli -a "$LOCAL_DEALS_REDIS_PASSWORD" XLEN stream.orders
redis-cli -a "$LOCAL_DEALS_REDIS_PASSWORD" XPENDING stream.orders g1
redis-cli -a "$LOCAL_DEALS_REDIS_PASSWORD" XLEN stream.orders.dlq
```

有效基线至少需要满足：

- DB 订单数不超过初始库存。
- 重复下单 SQL 结果为空。
- DB 库存不小于 0。
- Redis 库存与已购集合数量符合预期。
- Stream pending-list 为 0。
- Dead-letter Stream 长度为 0。

## 结果记录

每轮有效结果写入：

```text
docs/benchmark-results.md
```

建议记录：

- 实现版本
- JMeter 配置
- Summary Report 指标
- Aggregate Report 的 P95/P99
- MySQL 正确性校验
- Redis 正确性校验
- 当前结论和下一步改进

## 清理压测用户

如果需要删除本轮生成的压测用户和对应 Redis token：

```bash
mvn -Dtest=BenchmarkDataTool#cleanupBenchmarkUsersAndTokens test
```

该操作只删除手机号匹配 `bench.phonePrefix`（默认 `138`）的用户及其 Redis token，不影响其他数据。
