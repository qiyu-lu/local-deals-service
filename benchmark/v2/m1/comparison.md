# M1 复测：Java 21 / Boot 3.5 与 v2.0-m0 同场对比

> 数字全部来自 [summary.csv](summary.csv)。`11e1d8f` = tag `v2.0-m0` 的 jar（Dragonwell 8），
> `aefe34f` = M1 分支的 jar（Temurin 21）。原始 k6 JSON 在 `raw/`（gitignore）。

## 1. 为什么不直接和 M0 的数字比

M0 基线是另一天测的，而这台机器上还跑着其他项目的容器（负载约 2）。今天用同一份 `v2.0-m0` jar
复测，天花板从 M0 当天的 15.9k 降到了 14.5k req/s，说明机器状态本身就有约 10% 的漂移。所以 M1
的结论只拿**同一场次**的对照组来比：同一隔离栈、同一分核、同一 k6 脚本、交替运行。

| 项 | 值 |
| --- | --- |
| 分核 | 应用 `0-3,8-11`；MySQL/Redis/RocketMQ/ES `4-5,12-13`；k6 `6-7,14-15`（与 M0 相同） |
| 运行时 | 对照：Dragonwell 8，默认 ParallelGC；M1：Temurin 21，默认 G1。两者都是 `-Xms2g -Xmx2g`，GC 用各自 JDK 的默认值——升级带来的就是这个默认值 |
| 依赖 | MySQL 8.0、Redis 6.2、RocketMQ 4.9.4（两者相同）。ES：M1 用 8.18.8；对照 jar 的 7.6 客户端连不上 8.x，另起一个 tmpfs 上的 7.17 容器（同样 pin 在依赖核上）。ES 不在秒杀链路上 |
| 顺序 | 生成 10 万 token → 对照阶梯 → M1 阶梯 → 落库测试按 对照/M1 交替 3 轮；每次启动后先 500 req/s 预热 30 s |
| 配置 | 与 M0 相同：`activity-limit` / `ip-limit` = 100000，其他默认 |

## 2. 准入阶梯

| 目标 req/s | 实际（对照 → M1） | dropped | p99 ms | p95 ms | 应用 CPU（核） |
| ---: | --- | --- | --- | --- | --- |
| 500 | 500.0 → 500.0 | 0 → 0 | 1.8 → 2.0 | 1.1 → 1.1 | 0.44 → 0.45 |
| 1 000 | 1 000.0 → 1 000.0 | 0 → 0 | 2.4 → 2.0 | 1.0 → 1.0 | 0.66 → 0.59 |
| 2 000 | 2 000.0 → 2 000.0 | 0 → 0 | 8.1 → 5.3 | 0.9 → 0.9 | 0.97 → 0.86 |
| 5 000 | 4 999.8 → 4 999.9 | 0 → 0 | 23.4 → 15.8 | 3.0 → 1.4 | 2.00 → 1.60 |
| 10 000 | 9 971.7 → 9 989.8 | 832 → 286 | 73.7 → 51.8 | 24.5 → 20.8 | 3.84 → 3.06 |
| 15 000 | 14 584.7 → 14 868.8 | 11 803 → 3 909 | 190.9 → 118.8 | 129.3 → 63.2 | 5.01 → 4.15 |
| 20 000 | 14 500.5 → 17 538.2 | 161 413 → 73 485 | 424.4 → 251.3 | 353.6 → 196.5 | 4.92 → 4.60 |

- **无回退，且准入端变好**：天花板 14.5k → **17.5k req/s（+21%）**；10k 档 p99 73.7 → 51.8 ms，
  同样吞吐下应用 CPU 3.84 → 3.06 核（−20%）。
- **拐点口径不变，仍是 1 万 req/s**：按 M0 的定义（p99 < 100 ms 且丢弃 < 1%），15k 档 M1 丢弃 0.9%
  但 p99 118.8 ms，差一点不达标。
- 每档都是 1000 单全部接收，无超卖，无 HTTP 错误。
- **提升未归因**。候选：JIT、G1 替代 ParallelGC、RocketMQ client 4.x→5.3、Lettuce 6.1→6.6、
  Tomcat 9→10.1。本次没有做火焰图，也没有做 GC 单变量对照，所以不声称是哪一个。M1 不改业务
  逻辑，准入端的结构性问题（每请求 3 次 Redis + 2 次 Broker 往返）原样还在，留给 M3。

## 3. 消费落库（2 万单以 2000 req/s 放入）

| 轮次 | 对照 单/s | M1 单/s | 对照追平 s | M1 追平 s |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 184.6 | 155.6 | 118.8 | 109.4 |
| 2 | 100.1 | 224.3 | 185.2 | 68.4 |
| 3 | 180.8 | 147.3 | 108.3 | 134.7 |
| 中位数 | 180.8 | 155.6 | 118.8 | 109.4 |

**测不出差别。** 两组区间几乎重合（100–185 vs 147–224）：中位数 M1 低 14%，均值 M1 高 13%，
6 轮的方差远大于组间差。这和 M0 的火焰图一致：落库被同一行库存上的行锁排队 + 连接池等待决定，
不看运行时。运行时升级不会解决 D3/D4，那是 M4 的事。

## 4. 复现

```bash
DEPS_CPUS=4-5,12-13 scripts/stack.sh up
scripts/stack.sh build                                   # M1 jar，Temurin 21
git worktree add /tmp/m0 v2.0-m0 && (cd /tmp/m0 && JAVA_HOME=<jdk8> mvn -q package -DskipTests)
docker run -d --name ld-v2-es7 --cpuset-cpus 4-5,12-13 -p 127.0.0.1:29201:9200 \
  -e discovery.type=single-node -e xpack.security.enabled=false \
  --tmpfs /usr/share/elasticsearch/data:uid=1000,gid=0 hm-dianping-elasticsearch:latest   # 7.17，只给对照组
scripts/bench.sh users 100000
LIMITS="--local-deals.traffic.seckill.activity-limit=100000 --local-deals.traffic.seckill.ip-limit=100000"
# 对照
APP_CPUS=0-3,8-11 APP_JAR=/tmp/m0/target/local-deals-service-0.0.1-SNAPSHOT.jar APP_JAVA_HOME=<jdk8> \
  APP_ARGS="$LIMITS --spring.elasticsearch.rest.uris=http://127.0.0.1:29201" scripts/stack.sh app-start
MILESTONE=m1 BENCH_COMMIT=11e1d8f K6_CPUS=6-7,14-15 scripts/bench.sh step 500 1000 2000 5000 10000 15000 20000
# M1
scripts/stack.sh app-stop && APP_CPUS=0-3,8-11 APP_ARGS="$LIMITS" scripts/stack.sh app-start
MILESTONE=m1 K6_CPUS=6-7,14-15 scripts/bench.sh step 500 1000 2000 5000 10000 15000 20000
# 落库：每次重启 + 预热后各跑一次 drain 20000 2000，对照/M1 交替 3 轮
```
