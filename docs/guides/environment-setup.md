# 本地环境与常见问题

## 依赖容器

开发环境用主 Compose 启动（固定端口 3306/6379/9876/10911/9200，数据 bind mount 到 `./*-data/`）：

```bash
docker compose up -d mysql redis namesrv broker elasticsearch
docker compose --profile dev up -d nginx canal-server   # 需要前端或 Canal 时
```

业务库为 `local_deals`，由 Flyway 在应用启动时迁移。

集成测试与压测使用隔离栈（独立 compose project、`127.0.0.1` 上的独立端口、named volume），
不会碰开发环境的数据：

```bash
scripts/stack.sh up                 # MySQL/Redis/RocketMQ/ES
REDIS_MODE=cluster scripts/stack.sh up   # 另加 3 主 3 从 Redis Cluster（2700x 客户端 / 3700x 总线）
                                    # 单节点同时保留：M5 之前的版本没有 hash tag，跑不了 Cluster
                                    # 这样起栈后，stack.sh it 会自动让测试连 Cluster
scripts/stack.sh it '*IT'           # 在隔离栈上用 Java 21 跑集成测试
scripts/stack.sh build && scripts/stack.sh app-start
scripts/bench.sh users 100000       # 压测用户与 token
scripts/bench.sh step 500 1000 2000 # 开环阶梯压测，结果写 benchmark/v2/<milestone>/summary.csv
scripts/stack.sh down               # 删除隔离栈及其 volume
```

里程碑的长压测用场景入口，无人值守：自带独立隔离栈（`STACK_ID=m3bench`，3xxxx 端口）、
构建基线 tag 与当前 HEAD、预热、阶梯、落库、故障演练，结束后删除容器。结果目录
`benchmark/v2/m3/<时间戳>-<场景>/` 里有 `status`（RUNNING/DONE/FAILED + 原因）、`manifest.json`、
`run.log`、`summary.csv`、`kill-drill.csv` 与 `raw/`。工作区有未提交改动时拒绝运行。

```bash
scripts/bench.sh m3-smoke                                   # 缩小参数冒烟，约 8 分钟
nohup scripts/bench.sh m3 > /tmp/m3-bench.out 2>&1 &        # 正式场景，约 53 分钟
cat benchmark/v2/m3/*-m3/status                             # DONE 即完成

scripts/bench.sh m4-smoke                                   # M4（消费侧）冒烟
nohup scripts/bench.sh m4 > /tmp/m4-bench.out 2>&1 &        # M4 正式场景

scripts/bench.sh m5-smoke                                   # M5（分桶 + Cluster）冒烟，约 8 分钟
nohup scripts/bench.sh m5 > /tmp/m5-bench.out 2>&1 &        # M5 正式场景，约 1 小时
```

M4 的场景以落库为主：对照 `v2.0-m3` 交替 3 轮全量接收落库，再按 `batch-size:thread-count`
做一轮参数扫描（`1:16` 就是关掉批量的同一份代码），最后各做一次 `kill -9` 与「先杀 Broker」
演练。扫描行在 `summary.csv` 的 commit 列里带组合名（如 `1aad214:b64t16`）。

M5 的场景把这份代码（16 桶）放在 Cluster 上，与单节点上的 `v2.0-m4` 交替对照：阶梯、落库各
若干轮，再按桶数扫描（1/8/64），最后 `kill -9` 应用一次、**杀 Redis 主节点两次**。桶数扫描行的
commit 列带 `:k<桶数>`，Redis 演练写 `redis-kill-drill.csv`（失联/恢复秒数、被告知中签却没有订单
的人数、Redis 比真相多出的库存、是否超卖、收敛秒数）。

分桶的参数：`local-deals.seckill.bucket.count`（默认 16，2 的幂且 ≤ 1024）。运行中改桶数会把旧桶
的状态搁浅，要先把在途订单放干。Cluster 模式下应用需要 `spring.data.redis.cluster.nodes`。

批量消费的参数：`local-deals.seckill.consume.batch-size`（默认 64）、`thread-count`（16）、
`claim-lease`（30s）、`enabled`（测试 profile 里关闭）。线程数应当 ≤ Hikari 池
（`spring.datasource.hikari.maximum-pool-size`，默认 24）。

## 后端接口检查

可以访问下面的接口检查后端是否正常启动：

```text
http://localhost:8083/shop-type/list
```

如果通过前端和 Nginx 访问，打开浏览器开发者模式，切换到手机模式后访问：

```text
http://localhost:8088/
```

![手机模式图标](../../figure/手机模式.png)

## JDK 版本问题

如果运行或编译时报错：

```text
java: java.lang.NoSuchFieldError:
Class com.sun.tools.javac.tree.JCTree$JCImport does not have member field 'com.sun.tools.javac.tree.JCTree qualid'
```

通常是 JDK 版本和项目依赖不匹配。该项目建议使用 JDK 8。

### IDEA Project SDK

路径：

```text
File -> Project Structure -> Project
```

设置：

```text
Project SDK: JDK 1.8
Project language level: 8
```

### IDEA Module SDK

路径：

```text
File -> Project Structure -> Modules -> local-deals-service -> Dependencies
```

设置：

```text
Module SDK: JDK 1.8
```

### Maven Runner

路径：

```text
Settings -> Build Tools -> Maven -> Runner
```

设置：

```text
JRE: Project JDK
```
