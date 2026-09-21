# 13 测试是怎么跑的：一次走查与用到的命令

## 一、这一章解决什么问题

前面十二章讲的是「系统怎么工作」，这一章讲的是「我怎么验证它工作」。目标只有一个：换一台机器，
照着这一章能把三层测试重新跑起来，并且每敲一条命令都说得出它在干什么、输出看哪一列、什么值算异常。

压测的方法论、各里程碑的数字与结论都属于第 12 章，这一章只讲动手：命令、参数、阶段顺序、
输出怎么读。碰到结论性的东西一律写「详见第 12 章」。

本章的命令，除了下面这两类，全部是从 `scripts/bench.sh`、`scripts/stack.sh`、
`benchmark/v2/scripts/`（含 `fixture.py`）、`.github/workflows/ci.yml` 和 `pom.xml` 里摘出来的；
变量换成了一个具体例子，形状保持原样。两个例外都逐条标注了：

- 第七节「线上排障」里，项目真实用过的与「通用做法、本项目未实际用到」的分开标注；
- 3.2 的几条 `git` 命令、按方法名点名的 `mvn -Dtest='类#方法'`、以及 5.9 的 `tail -f` / `less`，
  是我在终端里人工敲的，不在任何脚本里——但它们查的对象（提交历史、测试类、`run.log`）都是真的。

## 二、三层测试的分工

| 层 | 跑什么 | 要什么依赖 | 入口 |
| --- | --- | --- | --- |
| 单元 / 切片 | 纯逻辑、Lua 契约、状态机、限流桶、雪花 id | 无 | `mvn -o test` |
| 集成（IT） | 真的 MySQL / Redis / RocketMQ / ES，真的并发交错 | Testcontainers 或隔离栈 | `scripts/stack.sh it '<pattern>'` |
| 压测 / 演练 | 开环阶梯、落库速率、崩溃与故障注入 | 自己起一整套隔离栈 | `scripts/bench.sh <场景>` |

三层的成本差两个数量级：第一层几十秒、第二层几分钟、第三层几十分钟到一整夜。所以写代码时的循环
永远在第一层，第二层在提交前跑一遍，第三层只在里程碑收尾时跑。

仓库里现在有 104 个 `*Test` 和 47 个 `*IT`。

---

## 三、第一层：单元与切片测试

### 3.1 `mvn test` 到底跑了哪些类

`pom.xml` 里**没有** surefire 或 failsafe 的任何配置，只配了 Spring Boot 的打包插件。这句话很重要，
因为它决定了一件容易答错的事：surefire 的默认包含规则是 `Test*` / `*Test` / `*Tests` / `*TestCase`，
**`*IT` 不在里面**（`*IT` 是 failsafe 的默认规则，而这个项目没有引 failsafe）。所以：

- 直接 `mvn test` → 只跑那 104 个 `*Test`，一个 IT 都不会起，也就不需要任何依赖；
- 47 个 `*IT` **只能靠 `-Dtest=` 点名**才会被执行——CI 和 `scripts/stack.sh it` 走的都是这条路。

```text
mvn -o test
```
本地跑全部单元与切片测试。`-o` 是 offline，不去远程仓库解析快照，断网也能跑、也快一点。
→ 输出看最后的 `Tests run: N, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`；
`Skipped` 不为 0 要点进去看是哪条被 `@Disabled` 或条件注解挡掉了，**跳过不等于通过**。
→ 追问：「`mvn test` 会跑集成测试吗？」——不会，因为没配 failsafe，`*IT` 不在 surefire 默认包含里。

```text
mvn -o test -Dtest='TradeOrderPersistenceIT'
```
只跑一个类。`-Dtest` 会**覆盖**默认包含规则，所以这也是把一个 `*IT` 拉起来的唯一方式。
→ 追问：「那它的依赖哪来？」——见第四节，要么 Testcontainers 要么隔离栈。

```text
mvn -o test -Dtest='PaymentRefundIT#aPaymentArrivingAfterTheCloseIsRefundedAutomatically'
```
只跑一个方法，`#` 后面是方法名，这是 surefire 的语法。调一个竞态用例时几乎只用这一条：
竞态用例常常要连跑很多遍才有意义，跑一整个类太慢。
→ 追问：「怎么连跑二十遍确认不是偶然？」——这个项目的做法不是在命令行重复，而是把重复写进用例
里（第 4.3 节的 20 轮循环），这样断言能跨轮次生效。

```text
mvn -o -q test -Dtest='Seckill*IT' -DfailIfNoTests=false
```
`scripts/stack.sh it` 内部就是这一条（外面还套了 `JAVA_HOME` 和栈的环境变量）。
→ 参数：`-q` 只在失败时刷屏；`-DfailIfNoTests=false` 是关键——模式没匹配到任何类时**不报错**，
否则 CI 里一排除就整条挂掉。
→ 追问：「`-DfailIfNoTests=false` 会不会掩盖『我的测试根本没跑』？」——会，所以要配合看
`Tests run` 的数字，不能只看 `BUILD SUCCESS`。

### 3.2 TDD 先红后绿，在 git 历史里长什么样

这个仓库的提交规范是 Conventional Commits，全英文小写。测试先行这件事在历史里**有固定标记**：
先写的那个失败测试，提交标题结尾带一个字面量 `(red)`，正文常常直说「这一条编译不过，下一条让它变绿」；
紧接着的那个实现提交，正文结尾写上当时的绿色计数，形如 `mvn test 503/503`。

目前历史里有 333 个提交，其中 32 个带 `(red)`。

```text
git log --oneline --grep='(red)'
```
把所有「先写失败测试」的提交列出来。`--grep` 匹配提交信息，`--oneline` 一行一条。
→ 输出怎么读：每一行的下一个提交通常就是让它变绿的那个，所以这张表实际上是 32 对红绿。
→ 追问：「你怎么证明你真的先写了测试？」——给他看下面这一条。

```text
git show --stat 2923ad9 c31258b
```
把一对红绿并排摊开。`--stat` 只看每个提交改了哪些文件、各加删多少行，不看 diff 内容。
→ 输出怎么读：红的那个提交**只动 `src/test/`**（两个新测试文件，+86 和 +131 行，正文写明
「被测的两个类还不存在，所以这一条编译不过」）；绿的那个提交**只动 `src/main/`**，测试文件一行没碰。
「测试没被改过而它变绿了」——这是先红后绿最硬的证据，比任何口头说明都有力。
→ 追问：「有没有为了让测试通过而改测试？」——`--stat` 里绿提交不含 test 文件就是答案。

```text
git log --oneline --follow -- src/test/java/com/localdeals/trade/sharding/OrderShardingAlgorithmTest.java
```
跟踪单个测试文件的一生。`--follow` 让它跨过重命名继续追。
→ 用来回答「这个测试是什么时候、因为什么加的」：第一条通常就是那个 `(red)`。

手上留三对随时能讲的例子：两个实例共用同一个雪花 worker id（红的提交正文直接贴了真实报错
`Duplicate entry ... for key 'order_state_log_3.PRIMARY'`）、每请求一个 traceId、
分片算法对多个分片键取交集而不是退化成全表扫。

---

## 四、第二层：集成测试

### 4.1 两套依赖：Testcontainers 和隔离栈，什么时候用哪个

同一批 IT 可以跑在两种依赖上，切换靠一个环境变量：

- **Testcontainers**：`LOCAL_DEALS_IT_CONTAINERS=true` 时，一个
  `ApplicationContextInitializer`（通过测试 classpath 下的 `META-INF/spring.factories` 注册）
  起一个 MySQL 8 容器和一个 Redis 6.2 容器，把 jdbc url、用户名密码、Redis host/port/password
  以最高优先级的 property source 塞进每个 Spring 测试上下文。容器**每个 JVM 只起一次**，所有上下文共享。
  不设这个开关，这个 initializer 什么都不做。
  值得一提的是：整个测试树里**没有一个 `@Testcontainers` / `@Container` 注解**——接入是全局且命令式的，
  所以不需要每个类去声明容器，任何一个带 Spring 上下文的 IT 在 CI 里都自动连上容器。
  依赖也只显式引了 MySQL 那一个 testcontainers 模块，Redis 用的是核心包里的通用容器。
- **隔离栈**：不设开关时，IT 连的是 `scripts/stack.sh up` 起的那一套——独立的 compose project、
  绑在 `127.0.0.1` 的独立端口（MySQL 23306 / Redis 26379 / NameServer 29876 / Broker 20911 /
  ES 29200 / 应用 28083），而且脚本会主动拒绝碰开发栈的固定端口。

**分工的理由很直接**：Testcontainers 只起了 MySQL 和 Redis 两样，所以需要真 RocketMQ broker 或
真 Elasticsearch 的那几个 IT 在 CI 里跑不了，只能在本机的隔离栈上跑。CI 的 workflow 里就是把它们
一个个排除掉的：

```text
mvn -B test -DfailIfNoTests=false -Dtest='*IT,!SeckillWithRocketMQIT,!SeckillOrderRetryIT,!OrderCloseTimerIT,!CanalSyncIT,!ShopSearchAfterIT,!ShopSearchBeforeIT'
```
CI 的集成 job。`-B` 是 batch mode（非交互、不带进度动画，日志能看）；`-Dtest` 里 `!` 前缀是排除；
job 的 `env` 里设了 `LOCAL_DEALS_IT_CONTAINERS: "true"`，所以 MySQL/Redis 走容器。
→ 输出怎么读：被排除的六个类**不会被报告为跳过，而是根本不存在**，所以「CI 绿」不等于「全部 IT 绿」。
这六个必须在本机隔离栈上补跑。
→ 追问：「为什么不把 RocketMQ 和 ES 也 Testcontainers 化？」——可以，但 broker 起一次几十秒、
ES 要磁盘水位，CI 的收益不抵成本；这条边界是知情的，不是漏的。

CI 的另一个 job 就是一条 `mvn -B test`，不设任何开关，对应第一层。

本机跑集成测试的完整顺序：

```text
scripts/stack.sh up
REDIS_MODE=cluster scripts/stack.sh up
eval "$(scripts/stack.sh env)"
scripts/stack.sh it '*IT'
REDIS_MODE=cluster scripts/stack.sh it 'Seckill*IT'
REDIS_MODE=cluster scripts/stack.sh down
```
`up` 起依赖并等到全部健康；`REDIS_MODE=cluster` 额外起三主三从（客户端 2700x、总线 3700x）。
`env` 把所有连接串打印成一串 `export`，所以要 `eval` 才生效——脚本自己内部也是这么用的。
`it` 只认**这一次调用**上的 `REDIS_MODE`，不会自己去问栈当初是怎么起的：带上 `REDIS_MODE=cluster`
它才会把 cluster 节点列表和最大重定向次数注入给测试，忘了带就还是连单节点——这是最容易踩的一脚。
`down` 同理（带上它才会连集群那套 compose project 一起拆），volume 一起删。
→ 输出怎么读：`up` 成功的最后一行是 `stack ld-v2 ready: mysql=... redis=... namesrv=... broker=... es=...`；
集群模式下中间那一段变成 `redis-cluster=<六个节点地址>`。没有这一行就别往下跑。
→ 追问：「为什么要 `eval` 而不是 source 一个文件？」——因为端口、schema、密码都是按 `STACK_ID`
算出来的，同一台机器上可以并存好几套栈，把它们写进文件反而会串。

### 4.2 环境门控的 IT 怎么打开

47 个 IT 里有 19 个平时是**故意不跑**的，用 JUnit 5 的 `@EnabledIfEnvironmentVariable` 按环境变量门控
（全部是 `matches = "true"`），不满足就直接跳过而不是失败。六个门控变量：

- `M5A_ISOLATED` —— 需要独立环境的平台域 IT（数据同步、management 端点安全、两个可靠性积压 IT）；
- `M5B_ISOLATED` —— 两个有界缓存 IT；
- `M6A_ISOLATED` / `M6B_ISOLATED` / `M6C_ISOLATED` —— 各自要独立数据源或独立 Flyway 基线的营销域 IT；
- `M6A_DOCKER_FAULT` —— 要真的把 MySQL 容器停掉来注入故障的那一个，它在普通 `M6A_ISOLATED` 之上
  **又叠了一层开关**，因为停容器会波及同一台机器上别的测试；
- `LOCAL_DEALS_RUN_ISOLATED_LIKE_IT` —— 两个点赞可靠性 IT。

另外还有一个 `LOCAL_DEALS_IT_CONTAINERS`，它**不是** JUnit 门控（没有一个类用它写条件注解），
而是 4.1 里那个 initializer 自己读的容器化依赖总开关：它决定 IT 连容器还是连隔离栈，不决定哪些 IT 会跑。

**这六个门控变量没有任何脚本会替你设**：`scripts/stack.sh env` 打印的是连接串，不含这些门控；
仓库里除了测试源码只有一份 ADR 提到它们。所以要跑就得自己在命令行上带。
（唯一被脚本设过的是 `LOCAL_DEALS_IT_CONTAINERS`，在 CI workflow 的 `env` 里——而它不是门控。）

打开方式就是在命令前面带上变量：

```text
M6A_ISOLATED=true M6A_DOCKER_FAULT=true mvn -o test -Dtest='M6aMysqlStopFailureIT'
```
→ 输出怎么读：**最该盯的是 `Tests run` 的数字**。门控没打开时这些类会安静地跳过，
`BUILD SUCCESS` 照样出现——这正是「跳过不等于通过」最容易翻车的地方。
→ 追问：「为什么用条件注解而不是 Maven profile？」——门控的粒度是单个类甚至单个环境能力
（有没有 docker、有没有独立库），profile 的粒度是整次构建；而且条件注解会在报告里留下
「因为什么条件被跳过」的一行，profile 不会。

### 4.3 并发竞态 IT：怎么造出两种交错

这是集成层最值钱的部分，也是最容易被追问的部分。计划里把几条危险的并发路径编了号（race 1、
race 4、race 5……），每一条都有**两半测试**：一半先手、一半闩锁。

**先手那一半（确定性的）**：不追求并发，而是人为把顺序钉死——让其中一方**先完整跑完**，再让另一方
到达，断言迟到的一方走的是补偿路径而不是覆盖前者。例如支付与关单这条 race：先让订单过期、
先把关单执行完，然后才投递支付回调，断言回调的返回是「自动退款」而不是「支付成功」，并且
订单仍是关闭态、没有发券、退款记录恰好一条、库存已经还回去了。这一半保证的是**补偿逻辑本身正确**，
而且它百分之百可复现，任何机器上跑都一样。

**闩锁那一半（真并发的）**：用一个计数为 1 的 `CountDownLatch` 当发令枪。每个任务提交到线程池后
第一件事是 `start.await()` 把自己停在闩上，主线程等所有任务都提交完再 `start.countDown()`，
于是 N 个调用几乎同时冲进临界区。有的用例还在前面加一道 `ready` 闩（每个工作线程先
`ready.countDown()`，主线程 `ready.await()` 确认大家都到位了才开枪），这样连线程池自己创建线程的
耗时都被排除在外。断言是「恰好一个赢」：同一用户同一券八个线程并发下单，只有一个成功，
其余七个拿到预占冲突，并且库存只少 1。

**两者合起来的那个技巧，才是真正想讲的**：光靠闩锁并发，在某一台机器上可能总是同一方赢，
于是另一种交错从来没被测到，而测试还是绿的。所以竞态用例把**先手时延**编进了循环：同一个场景跑 20 轮，
前 10 轮两边同时起跑，第 11–15 轮给其中一方 20 毫秒的先手，第 16–20 轮把先手让给另一方；
每一轮根据最终状态判定是谁赢了，分别断言那一方对应的完整后果（状态、有没有发券、有没有自动退款、
库存有没有回滚），最后再断言 **两种赢法都至少出现过一次**。这样「这台机器上另一种交错从没发生」
会让测试**变红**，而不是悄悄变成一个只测了一半的绿。

**还有第三种机制，用在「单飞」和「隔离舱」这类用例上：把一方钉死在临界区里。** 做法是再加两道闩，
一道由被测代码在进入临界区时倒数（主线程据此确认「领跑者此刻确实卡在回调里」），一道由主线程
在验证完之后倒数放它走。有了这个中间态，就可以在**领跑者还没出来的时候**断言其余三十一个线程
确实是以「跟随者」的身份共享结果而不是各自去加载一次；也可以在这个窗口里让一个跟随者超时或者被中断，
证明它既不会取消领跑者、也不会把在途记录删掉、更不会另起一次加载。这种「窗口内断言」是
纯闩锁做不到的——不把一方钉住，等你去断言的时候一切都已经结束了。同类的技巧还有在被 mock 的写方法里
塞一小段睡眠，人为**把竞态窗口撑宽**，再断言「同时进入的最大并发数是 1」。

另外，测试用的那份 profile 会把后台的消费者、扫描任务、点赞落库工作线程统统关掉。
道理是：如果后台 worker 还在跑，用例断言的那一刻到底是谁写的数据就说不清了，
竞态用例会变成随机红。要测消费链路时是在用例里**手工调用**一次处理方法，而不是等后台线程碰巧跑到。

→ 面试追问：「并发测试不是很容易 flaky 吗？」——正是因为怕 flaky 才拆成两半：确定性那一半负责
「逻辑对不对」，并发那一半负责「互斥挡不挡得住」；并发那一半不断言谁赢，只断言「恰好一方赢、
赢的那一方后果自洽、两种赢法都出现过」，这三条都不依赖调度顺序。
→ 追问：「闩锁真能让它们同时开始吗？」——不能保证指令级同时，只能把线程创建和提交的开销排除掉；
剩下的抖动正是用先手时延主动制造的。
→ 追问：「那更强的做法是什么？」——见第九节。

---

## 五、第三层：一次 `scripts/bench.sh` 从头到尾

这一节按时间顺序走一遍。场景很多（`m3` / `m4` / `m5` / `m6` / `m6-consume` / `m8` 等，每个都有
同名 `-smoke` 缩小版），但骨架是同一条：

**启动前检查 → 起隔离栈 → 分核 → 打包 → 预热 → 阶梯 / 排空 → 采集 → 演练 → 对账 → 清理**

整场无人值守，所以第一件事是挂后台：

```text
nohup scripts/bench.sh m8 > /tmp/m8-bench.out 2>&1 &
```
把一整夜的运行挂到后台。逐个拆开：
- `nohup` —— 让进程忽略 `SIGHUP`。终端关掉（或者 ssh 断了）时内核会给前台进程组发 HUP，默认动作是
  终止；`nohup` 之后就不怕终端断开了。
- `> /tmp/m8-bench.out` —— 标准输出重定向到文件。不重定向的话，终端一没，写 stdout 会拿到
  `SIGPIPE` 或 EIO，照样死。
- `2>&1` —— 把标准错误**复制到标准输出当前指向的地方**。注意顺序：必须写在 `>` 后面，因为
  `2>&1` 复制的是「此刻 fd 1 指向哪」；写成 `2>&1 > file` 的话 stderr 还留在终端上。
- 末尾的 `&` —— 放进后台作业。

→ 输出怎么读：`/tmp/m8-bench.out` 只是 stdout 的兜底，真正的过程日志在结果目录的 `run.log` 里。
→ 追问：「`nohup &` 和 `setsid`、`systemd-run`、`tmux` 有什么区别？」——`nohup &` 只解决 HUP 和
输出两件事，进程仍在原会话里；要彻底脱离会话是 `setsid`；要能回去交互是 `tmux`。这里不需要交互，
所以最轻的那个就够。

### 5.1 启动前检查（preflight）

脚本拒绝启动的五件事：工作区脏、端口被占、磁盘不够、没有 JDK 21、docker 不可用，外加「同时只允许
一个场景在跑」。

```text
git status --porcelain -uall -- . ':(exclude)benchmark/v2/*/[0-9]*-*/' ':(exclude)benchmark/v2/*/[0-9]*-*/**'
```
工作区必须干净，**因为每一行测量结果都要能对应到一个 commit**。`--porcelain` 是给脚本读的稳定格式
（有输出就是脏）；`-uall` 把未跟踪目录里的文件一个个列出来，而不是折叠成一行目录名——正因为会逐个列，
后面两个 `:(exclude)` 路径规格才能把「历史结果目录」整个排掉，哪怕它整体是新建的。
→ 输出怎么读：**输出为空才算干净**。冒烟可以用 `ALLOW_DIRTY=1` 放行，脏文件清单会被记进
`manifest.json`。
→ 追问：「为什么非要求干净？」——不然半年后回看一行数字，没法知道它是哪份代码跑出来的。

```text
ss -ltn "( sport = :24083 )"
```
查端口有没有被占。`-l` 只看 listening，`-t` 只看 TCP，`-n` 不做端口名/主机名反解（快，且输出稳定
好 grep）。括号里是 ss 自己的过滤表达式，`sport` 是源端口（对 listening socket 来说就是监听端口）。
脚本对 MySQL / Redis / NameServer / Broker / ES / 应用 / management 七个端口逐个查，
`grep -q LISTEN` 命中就直接拒绝启动。
→ 输出怎么读：没有 `LISTEN` 行就是空闲。有的话看 `-p`（要权限）能看到是谁占的。
→ 追问：「和 `netstat -ltnp` / `lsof -i` 什么区别？」——`ss` 直接读内核的 netlink（`sock_diag`），
不像 `netstat` 去遍历 `/proc/net/*` 文本，连接一多快一个数量级；`netstat` 在新发行版上已经不默认装。
`lsof -i` 强在反查「哪个进程持有这个 fd」，但要扫全部进程的 fd 表，更慢。查端口占用用 ss，
查「谁占的」用 `lsof -i :端口` 或 `ss -ltnp`。
→ 追问：「为什么这套栈的端口全压在 32768 以下？」——这台机器的临时端口段是 32768–60999，
一次压测在里面占着上百条连接；某次运行把一个本该监听的端口拿去当出方向源端口，下一个进程就
bind 不上了。M8 的冒烟因此丢了四个场景里的两个，报错看起来像「多实例有问题」，其实完全不是。
Redis 6.2 的集群总线固定在客户端端口 +10000 且只有「宣告值」可配，所以客户端基数还必须小于 22768。

```text
df -Pk /home/sd101t/IdeaProjects/hm-dianping | awk 'NR==2 {print int($4/1024/1024)}'
```
磁盘余量检查，不足 15 GB 直接拒绝。
- `df` 看文件系统余量；`-P` 是 POSIX 输出格式，**保证一条记录一行**（设备名很长时默认格式会折行，
  脚本就取错列了）；`-k` 固定以 1K 为单位，这样列的含义与机器无关。
- `awk 'NR==2 {...}'` —— `NR` 是当前行号，只处理第 2 行（第 1 行是表头）；`$4` 是 Available 列；
  除两次 1024 换成 GB，`int()` 取整。
→ 输出怎么读：这里看的是 **Available（$4）不是 Used%**。异常值就是小于 15。
→ 为什么卡 15 GB：Broker 的 commitlog 和 ES 的数据都落在这台机器上，而 Elasticsearch 8.x 在
90% 高水位就停止分配分片——盘满的表现不是「写失败」，而是「ES 起来了但分片一直不分配」，很难查。
排查时用人读的 `df -h`（`-h` 是 human readable）。
→ 追问：「`df` 和 `du` 的区别？」——`df` 问文件系统要统计值，一次系统调用；`du` 递归遍历目录累加，
慢，而且删了但仍被进程打开的文件 `du` 看不到、`df` 看得到。

```text
free -g | awk 'NR==2 {print $2" GB"}'
```
不是门槛检查，而是**记录机器规格**：连同 `lscpu | sed -n 's/^Model name: *//p'`、`nproc`、`uname -r`、
`cat /proc/loadavg`、以及「当时还有哪些别的容器在跑」一起写进 `manifest.json`。
- `free` 读 `/proc/meminfo`；`-g` 以 GB 显示（排障时更常用 `free -m`，MB 粒度看 available 更准）。
- 第 2 行是 Mem 行，`$2` 是 total。
→ 输出怎么读：排障时真正要看的是 **available 那一列，不是 free**——free 不含可回收的 page cache，
Linux 上 free 很低是常态，不代表内存紧张。
→ 追问：「为什么要记 loadavg 和别的容器？」——这台机器上还跑着其它项目的未绑核容器，属于背景噪声；
两次运行数字对不上时，第一件事就是翻 `manifest.json` 比当时的负载。

```text
command -v docker >/dev/null && docker info >/dev/null 2>&1
command -v mysql >/dev/null && command -v redis-cli >/dev/null
```
`command -v` 查命令在不在 `PATH` 里（比 `which` 可移植，是 shell 内建）。docker 还要再 `docker info`
一次，因为「装了」和「daemon 连得上、当前用户有权限」是两回事。
→ 为什么强制要宿主机的 `mysql` 和 `redis-cli`：整套 fixture 和最终对账都是直接调这两个命令行做的
（见 5.8），没有它们脚本连券都造不出来。

```text
exec 9>benchmark/v2/run/.scenario.lock
flock -n 9 || fail "preflight: another scenario is running"
```
同时只允许一个场景在跑。`exec 9>file` 把文件描述符 9 长期绑到锁文件上（不是子进程，所以锁能跟着
整个脚本的生命周期）；`flock -n 9` 对这个 fd 加排他锁，`-n` 是拿不到就立刻失败而不是排队等。
→ 为什么必须有：两个场景同时跑会互相抢端口、抢 CPU，数字全废。套件模式下父进程持锁、
子场景用 `SCENARIO_LOCK_HELD=1` 跳过加锁，否则自己会把自己锁死。
→ 追问：「进程被 kill -9 了锁会不会留着？」——不会，`flock` 的锁挂在打开的文件上，进程死了
内核就释放，这正是它比「写个 pid 文件」可靠的地方。

### 5.2 起隔离栈

```text
docker compose --project-name ld-m3bench --project-directory . --env-file /dev/null --file docker-compose.yml up -d mysql redis namesrv broker elasticsearch
```
起依赖。逐个参数：
- `--project-name` —— compose 用它给容器、网络、volume 加前缀，**这是隔离的根**：这套栈叫
  `ld-m3bench`，删的时候也只删这个 project 的东西，碰不到开发栈。
- `--project-directory .` —— 相对路径的基准目录，和 compose 文件的位置解耦。
- `--env-file /dev/null` —— **明确不读 `.env`**。否则开发栈的 `.env` 会悄悄污染这套栈的端口和密码，
  而这种污染极难发现。所有变量都由脚本显式 `env VAR=... docker compose ...` 传进去。
- `up -d` —— 后台起；后面列服务名就是只起这几个。
→ 输出怎么读：`docker compose ps` 看 State；起不来就 `docker logs --tail 20 <容器>`。
→ 这条 `up` 外面包了三次重试：docker 偶尔会拒绝把容器挂到刚建的 bridge 网络上
（`bridge port not forwarding after 200ms`），套件每个场景都重建一次网络，所以撞得比较频繁——
M8 的整夜运行就因此在第 38 秒丢了第一个场景。这个调用是幂等的，重试至今每次都成功。

集群模式另起一套 compose project，形成集群用的是 `redis-cli` 的集群管理模式：

```text
redis-cli -a "$LOCAL_DEALS_REDIS_PASSWORD" --no-auth-warning --cluster create 127.0.0.1:21001 127.0.0.1:21002 127.0.0.1:21003 127.0.0.1:21004 127.0.0.1:21005 127.0.0.1:21006 --cluster-replicas 1 --cluster-yes
```
- `--cluster create` —— 一次性分配 16384 个 slot 并建立主从关系。
- `--cluster-replicas 1` —— 每个主配一个从，六个节点即三主三从；前三个是主，后三个是从。
- `--cluster-yes` —— 不交互确认（无人值守必须）。
- `-a <密码>` + `--no-auth-warning` —— 密码走命令行，后者只是压掉「密码在命令行不安全」的提示。
→ 输出怎么读：之后用 `CLUSTER INFO` 里的 `cluster_state:ok` 判断成没成，脚本就是 `grep -q '^cluster_state:ok'` 轮询。
→ 卡在握手不动，八成是总线端口宣告错了（Redis 6.2 的总线固定在客户端端口 +10000，只有宣告值可配），
脚本专门校验了两个基数必须正好差 1000，不满足直接报错而不是等十五分钟超时。

起完依赖还要建 topic 和消费组，用的是 broker 容器里的 `mqadmin`：

```text
docker exec ld-m3bench-broker sh mqadmin updateTopic -n namesrv:9876 -c ld-m3bench -t seckill-order-topic
```
`docker exec` 在已经在跑的容器里执行命令（区别于 `docker run` 是新建容器）。`-n` 是 NameServer 地址，
`-c` 是 cluster 名，`-t` 是 topic 名。消费组同理用 `updateSubGroup -g`。
→ 为什么显式建：让 topic 的队列数与权限是确定的，而不是靠 broker 的自动创建——自动创建出来的队列数
会影响消费并行度，那正是被测量的东西。

### 5.3 分核

```text
docker update --cpuset-cpus 4-5,12-13 ld-m3bench-mysql
```
把依赖容器钉在固定的核上。`docker update` 改的是**已经在跑的容器**的 cgroup 限制，不用重建。
`--cpuset-cpus` 接的是核号列表，`4-5,12-13` 是两个物理核和它们的超线程兄弟。
→ 脚本对 mysql / redis / namesrv / broker / es 五个容器都做一遍。

```text
taskset -c 0-3,8-11 ~/.jdks/temurin-21.0.12.1/bin/java -Xms2g -Xmx2g -jar current.jar
```
被测应用用 `taskset` 绑核。`-c` 后面同样是核号列表，写在要启动的命令前面，
让新进程一出生就带着这个 CPU 亲和性掩码。
→ 三层分核是固定的：应用 `APP_CPUS=0-3,8-11`、依赖容器 `DEPS_CPUS=4-5,12-13`、
压测客户端容器 `K6_CPUS=6-7,14-15`。
→ **为什么压测机和被测应用必须分开**：单机压测里客户端和服务端抢的是同一份 CPU，不分核的话
「应用变慢」和「压测客户端自己跑不动了」这两件事在数字上完全分不开，拐点是假的。
分核之后，`summary.csv` 里连 k6 自己用了几个核都是一列，客户端一旦接近它那几个核的上限，
这一档的数据就要打折看。
→ M8 的多实例场景里每个实例还各给一对核（`0,8` / `1,9` / `2,10`），nginx 再给一对（`3,11`），
这样「加一个实例」是真的加算力，而不是把同一份预算切成更细的几份——否则测出来的是进程切换开销
而不是扩展性。
→ 追问：「`taskset` 和 cgroup 的 cpuset 有什么区别？」——`taskset` 设的是进程的亲和性掩码，
进程自己可以改回去；cgroup 的 cpuset 是外部强加的上界，容器用后者。这里应用是宿主机进程所以用前者，
容器用 `docker update`。
→ 追问：「为什么核号是 `0-3,8-11` 这种成对的？」——这台机器开了超线程，`n` 和 `n+8` 是同一个物理核的
两个逻辑核，成对分配才不会让两份负载在同一个物理核上互相拖。

### 5.4 打包与预热

基线构建从一个 tag 打出来，用 git worktree 而不是切分支：

```text
git worktree add --detach benchmark/v2/run/m3bench/baseline-src v2.0-m5
```
`worktree add` 在另一个目录里再检出一份工作区，共用同一个 `.git`；`--detach` 是游离 HEAD，
不建分支。这样当前工作区一动不动（preflight 还要求它干净），基线和当前 HEAD 可以并排构建。
跑完在清理阶段 `git worktree remove --force` 掉。

```text
JAVA_HOME=~/.jdks/temurin-21.0.12.1 mvn -q package -DskipTests
```
打 jar。`-DskipTests` 跳过测试执行（注意和 `-Dmaven.test.skip=true` 的区别：后者连测试代码都不编译）。
显式指定 `JAVA_HOME` 是因为要保证基线和当前用**同一个 JDK**，否则比的就不只是代码了。
→ 每个 commit 的 jar 会缓存一份并记下 commit，一夜四个场景不会把同样两个构建打四遍。

预热是一次**不记进 `summary.csv`** 的低速跑，输出丢进 `raw/warmup/`。没有它，第一档测量同时也是
JIT 第一次编译热点方法、连接池第一次建连、Tomcat 线程池第一次扩容的那一刻——那一档必然难看，
而难看的原因和被测的改动无关。多实例场景里预热还要**穿过 nginx** 打到每个实例，道理一样。

### 5.5 阶梯与排空

两种测量，回答两个不同的问题：

- **阶梯（step）**：每一档一张全新的券，用开环模型按固定到达率打固定时长，档位逐级抬高
  （正式 m3 是 500→30000 九档，每档 30 秒）。量的是**准入侧**的延迟与拒绝行为。
- **排空（drain）**：一次把整批库存以高速率放进去，然后**量 MySQL 追上来要多久**。
  量的是**落库侧**的吞吐——这一半才是消费端真正的能力上限。

压测客户端跑在容器里：

```text
docker run --rm --name ld-m3bench-k6-12345 --network host --cpuset-cpus 6-7,14-15 --user 1000:1000 -v ./benchmark/v2/scripts:/scripts:ro -v ./benchmark/v2/run:/data:ro -v ./benchmark/v2/m8/raw:/out grafana/k6:2.2.0 run --quiet -e BASE_URL=http://127.0.0.1:24083 -e VOUCHER_ID=42 -e RATE=20000 -e DURATION=30s -e TOKENS=/data/tokens.csv -e USER_OFFSET=0 --summary-export /out/step-r20000-s1000-231501.json /scripts/seckill.js
```
逐个参数：
- `--rm` —— 退出即删容器，不然一夜攒几十个死容器。
- `--network host` —— 直接用宿主机网络栈。**不这么做，每个请求都要多走一层 NAT**，测的就成了
  docker 的网络转发；被测应用和 nginx 都是宿主机进程，桥接毫无意义。
- `--cpuset-cpus` —— 见 5.3，把客户端钉死在它自己那两对核上。
- `--user 1000:1000` —— 以当前用户身份跑，否则挂载出来的结果文件属于 root，后续脚本读写都要 sudo。
- `-v 源:目标:ro` —— 三个挂载：脚本只读、token 池只读、结果目录可写。
- `-e` —— 传给压测脚本的参数：目标地址、券 id、到达率、时长、token 文件、用户游标起点。
- `--summary-export` —— 把汇总指标写成 JSON，后面用 python 取数（`-e` 传进去的自定义 outcome 计数器
  也在里面）。
→ **输出怎么读**（这几条是判断一次运行可不可信的关键）：
  - `dropped_iterations` —— 开环模型下「到点了却没有空闲 VU 去发」的次数。它一大，说明**客户端没打满**
    设定的到达率，这一档的 achieved_rps 是客户端的上限不是服务端的。
  - `achieved_rps` 对不对得上 `target_rps` —— 对不上同理。
  - `http_req_duration` 的 p95 / p99 / max，以及 `http_req_failed` 的比例。
  - `k6_cpu_cores` —— 客户端自己的核数，接近它分到的核数就意味着这一档要打折看。
→ 追问：「为什么用开环（constant-arrival-rate）而不是固定并发？」——固定并发是闭环，系统一慢
请求自然就发得少，于是永远测不出过载行为，延迟曲线被系统自己压平了。详见第 12 章。
→ 追问：「用户为什么要带游标？」——每档从上一档停的地方接着取 token，避免同一个用户在
限流窗口内重复出现，整池用完才绕回头。

排空之后要等 MySQL 追平：脚本每秒查一次订单数写进 `-orders.csv`，然后取「已落到 10% 和 90%
两个采样点之间的斜率」当落库速率——取中间段是为了甩掉两头的爬坡与收尾。

### 5.6 采集：那几列「核数」是怎么来的

`summary.csv` 里每个组件一列平均核数。**采集方式不是 `docker stats`**，而是直接读 cgroup 与 procfs
的累计量，在 k6 窗口前后各取一次、相减、除以墙钟时间。

```text
awk '{print $14 + $15}' /proc/12345/stat
```
应用进程用掉的 CPU 时间。`/proc/<pid>/stat` 的第 14、15 个字段是 `utime` 和 `stime`，
单位是时钟嘀嗒；把它们相加就是这个进程累计消耗的用户态 + 内核态 CPU 时间。
配套要 `getconf CLK_TCK` 拿到每秒多少嘀嗒（通常 100）才能换成秒。多实例时把每个实例的 pid 都加起来，
所以「应用的核数」这一列含义不变，不管它现在是几个进程。

```text
docker inspect -f '{{.Id}}' ld-m3bench-mysql
cat /sys/fs/cgroup/cpu,cpuacct/docker/<容器完整 Id>/cpuacct.usage
```
容器用掉的 CPU 时间。`docker inspect -f` 用 Go template 只取出完整容器 Id（`-f` 就是 `--format`），
拿它拼出 cgroup 路径；`cpuacct.usage` 是**纳秒**累计值。Redis 那一列在集群模式下是六个节点相加。
k6 自己的用量则由一个后台采样子进程每 0.5 秒写一次文件，因为容器一退出这个文件就没了。
→ 核数 = (后 − 前) / 1e9 / 墙钟秒数。**平均核数会掩盖尖峰**，这是这个口径的已知代价。
→ 追问：「为什么不用 `docker stats`？」——`docker stats` 给的是瞬时百分比、要流式读、还得自己攒窗口；
这里需要的是「k6 那一段窗口内的精确累计量」，读累计计数器前后相减是最直接也最准的做法。
（`docker stats` 本身这个项目**没有用到**，看实时负载时才用它。）

每一轮测量结束前，还要在应用还活着的时候抓一次指标快照：

```text
curl -fsS --max-time 20 http://127.0.0.1:24184/actuator/prometheus -o raw/ladder-current-i1-231530.prom
```
`-f` 让 HTTP 错误码变成非零退出码（不然 500 的响应体会被当成正常输出存下来），`-s` 静默，
`-S` 是「静默但仍然打印错误」（`-sS` 基本是固定搭配），`--max-time` 总超时，`-o` 存文件。
→ 为什么非在停之前抓：批大小分布和退化计数只活在进程内存里，应用一停就没了。M4 和 M5 两个里程碑
就是因为当时没有这层仪表，结论只能写成「批可能没填满」而无法证实。

```text
curl -fsS http://127.0.0.1:24184/actuator/prometheus | awk '/^local_deals_seckill_consume_batch_size_orders_(count|sum)\{stage="delivered"\}/ { if ($1 ~ /_count/) c = $2; else s = $2 } END { printf "%d %d", c + 0, s + 0 }'
```
从 Prometheus 文本格式里取两个数。`awk` 在这里干的事：`/正则/ { 动作 }` 对匹配行执行动作，
`$1` 是指标名（含标签）、`$2` 是值，`END` 块在读完所有行后跑一次，`+ 0` 是**强制转成数字**
（没匹配到时变量为空，空字符串加 0 得 0，这样输出永远是合法数字）。
→ 输出怎么读：拿一轮测量前后的 `sum` 差 ÷ `count` 差，得到**这一轮自己的平均批大小**。
接近 1 就说明批量消费者其实在一条一条提交，不管批大小参数配成多少。

### 5.7 故障演练

三种演练都能单独跑（先起栈起应用；演练脚本自己会 `eval "$(scripts/stack.sh env)"`，
但 `STACK_ID` 要和栈一致，两个 Redis 演练还必须显式给 `SECKILL_BUCKETS`——脚本对它是硬要求，
缺了直接退出，因为它得按桶去逐个求和）：

```text
benchmark/v2/scripts/kill-drill.sh 20000 2000 6
BROKER_DOWN=1 benchmark/v2/scripts/kill-drill.sh 20000 2000 6
SECKILL_BUCKETS=16 benchmark/v2/scripts/redis-kill-drill.sh 20000 2000 6
SECKILL_BUCKETS=16 REPLICA_SLEEP_S=8 DRILL_TAIL_S=15 benchmark/v2/scripts/redis-kill-drill.sh 30000 2000 5
```
三个位置参数统一是「库存、到达率、第几秒动手」。前三条是 M5 场景用的值；最后一条是复制空窗那一轮，
库存、速率、动手时刻和收尾时延都跟着换了（为什么换见下文）。

**杀应用**：

```text
kill -9 "$(cat benchmark/v2/run/m3bench/app.pid)"
```
在放行到第 6 秒时把应用打死。
- `kill` 默认发的是 `SIGTERM`（15），可以被捕获：JVM 会跑关闭钩子，Spring 会优雅停机——
  连接排空、消费者反注册、线程池 shutdown。
- `kill -9` 发 `SIGKILL`，**内核直接回收进程，不能被捕获也不能被忽略**，一行关闭钩子都不会跑。
- **演练为什么必须用 -9**：要模拟的是断电 / OOM killer / 容器被强杀这类「没有机会收尾」的崩溃。
  用普通 `kill` 的话，优雅停机会把半截状态收干净，于是演练验证的是优雅停机而不是崩溃恢复——
  恰好把最想验的那条路径绕过去了。这一条几乎必被追问。
→ 对比着记：`scripts/stack.sh app-stop` 走的是正常路径——先 `kill`（TERM），然后**每秒轮询
最多 30 次**等它自己退出，仍然活着才 `kill -9` 兜底。这才是日常停服的正确形状。
→ 输出怎么读：演练打印一行 CSV——券号、被重投的条数、**动手那一刻的预占数**、最终预占数、订单数、
Redis 库存、DB 库存、残留 PROCESSING、收敛秒数。
判据是「重启后预占数最终等于订单数，且残留处理中为 0」，以及收敛用了多久。
→ `BROKER_DOWN=1` 的变体先在第 4 秒 `docker kill` 掉 broker 再杀应用：这样准入的 Lua 已经成功、
消息却从没被存下来，专门制造「必须由对账器重投」的那一类残局。

**杀 Redis 主节点**：要杀的节点是**运行时查出来的**（当前谁在服务 0 号桶），不是写死的名字——
第二轮时第一轮被杀的节点会以「自己那个已被提升的副本的从」的身份回来，再杀它什么也证明不了。

```text
redis-cli -c -h 127.0.0.1 -p 21002 -a "$LOCAL_DEALS_REDIS_PASSWORD" --no-auth-warning CLUSTER NODES
```
`-c` 是**集群模式**：客户端收到 `MOVED` / `ASK` 重定向时自动跟着跳到正确节点，不加的话一个跨槽的 key
会直接报 `MOVED` 错误。读演练数据时连的是**幸存节点**，因为环境变量里那个默认地址指的正是要被杀的那台。
→ 输出怎么读：`CLUSTER NODES` 一行一个节点——第 1 列 node id、第 2 列 `ip:port@总线端口`、
第 3 列标志（`master` / `slave` / 含 `fail`）、第 4 列它的主的 id、最后几列是它持有的 slot 区间。
脚本就是用 `awk` 从这张表里找出「持有某个 slot 的主」和「某个主的从」。
→ 配套的还有 `CLUSTER KEYSLOT <key>` 算某个 key 落在哪个 slot，以及
`CLUSTER INFO | grep '^cluster_state:ok'` 判断集群整体状态。

**复制空窗那一轮**，是这套演练里最需要设计的一次。目的是造出「主节点收了写、但从节点一个字节都没收到，
然后主死了」——被提升的从于是回来时**相信着已经卖掉的库存**，Redis 会超发，
此时 MySQL 那条 `stock = stock - n WHERE stock >= n` 是最后一道防线。

```text
redis-cli -h 127.0.0.1 -p 21004 -a "$LOCAL_DEALS_REDIS_PASSWORD" --no-auth-warning DEBUG SLEEP 8 &
redis-cli -h 127.0.0.1 -p 21001 -a "$LOCAL_DEALS_REDIS_PASSWORD" --no-auth-warning CLIENT KILL TYPE replica
docker kill ld-m3bench-redis-c1
```
三步，顺序不能换，而且这里**故意不加 `-c`**：加了的话一次重定向就会把 `DEBUG SLEEP` 发到别的节点去。
1. `DEBUG SLEEP n` 让从节点的主线程睡 n 秒（Redis 单线程，所以它整个停摆）。**关键不在于它停止应用
   复制流，而在于睡着的从节点无法重连。**
2. `CLIENT KILL TYPE replica` 在**主节点这一侧**掐断所有复制连接。`TYPE replica` 限定只杀从连接，
   不会误伤业务客户端。
3. 空窗期（默认 3 秒）里主节点继续收写，这些写谁也收不到；然后 `docker kill` 杀主。
→ **第一次做这个演练是失败的，失败的原因值得讲**：一开始只用 `DEBUG SLEEP`，结果 RPO 仍然是 0。
因为从节点只是停止**应用**复制流，字节早就躺在它的 socket 缓冲区里了，睡醒一股脑全应用上。
必须让主节点**停止发送**，所以才加了第二步。
→ 输出怎么读（这一行 CSV 的关键列）：`window_effective` 必须是 `yes`（杀的那一刻库存还没卖完，
否则这一轮什么也没赌上，一排 0 会被误读成「没丢数据」）；`lost_admissions` 是买家被告知中了、
但订单永远不会存在的数量；`stock_gap` 是 Redis 相信的库存减去真实库存，也就是「从死人手里回来的库存」；
`oversold` 必须是 0；`stock_short_degraded` 大于 0 才说明**最后那道防线真的被触发过**。
→ `docker kill` 和 `docker stop` 的区别，正好与 `kill -9` 和 `kill` 对应：`kill` 直接发 SIGKILL，
`stop` 先 SIGTERM 再等宽限期。演练要前者。

### 5.8 对账

最终对账全部用宿主机的 `mysql` 客户端直查，**绕开应用自己的那套分库分表路由**——用被测系统自己的
代码去证明被测系统对，是循环论证。

```text
mysql -h 127.0.0.1 -P 24306 -u root -p'ld-stack-mysql' -N -B local_deals -e "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 42"
```
查券上剩余库存。
- `-h` / `-P` 主机端口，`-u` / `-p` 用户密码（**`-p` 和密码之间不能有空格**，有空格它会把下一个参数
  当数据库名）。
- `-N` 不打印列名表头，`-B` 是 batch 模式：用 tab 分隔、不画那个 ASCII 表格边框。
  **`-N -B` 合起来就是「给脚本读的格式」**，这样 shell 直接拿到一个裸值。
- `-e "SQL"` 执行一句就退出，不进交互式客户端。
→ 追问：「为什么是 `-N -B` 不是 `--xml` / `--json`？」——要的是一个能直接参与 shell 算术的裸标量，
解析成本为零。

订单表被拆成八张——**两个库各四张**，所以数订单要把它们 `UNION ALL` 起来数，表名还要带库名限定：

```text
mysql ... -e "SELECT COALESCE(SUM(c), 0) FROM (SELECT COUNT(*) AS c FROM \`local_deals\`.\`trade_order_0\` WHERE voucher_id = 42 UNION ALL SELECT COUNT(*) AS c FROM \`local_deals_1\`.\`trade_order_0\` WHERE voucher_id = 42 ...) counted;"
```
表名不是写死的，是先从 `information_schema.tables` 里按「两个库、表名是逻辑名或逻辑名加下划线数字」
查出来的——这样同一条命令能同时量 A/B 的两边（分表前的那个构建只有一张逻辑表，照样数得出来）。
→ 追问：「为什么不直接查逻辑表？」——逻辑表要经过分片中间件，那正是被验证的对象之一。

全链路对账的那一条，一句 SQL 同时产出五个数：

```text
mysql ... -e "SELECT COUNT(*), SUM(status NOT IN ('CLOSED','REFUNDED')), SUM(status = 'CLOSED'), SUM(status = 'PAID'), COUNT(DISTINCT IF(status NOT IN ('CLOSED','REFUNDED'), user_id, NULL)) FROM (<八张表 UNION ALL>) o;"
```
`SUM(布尔表达式)` 是 MySQL 里数条件行数的惯用法（真是 1 假是 0）；最后一列
`COUNT(DISTINCT IF(条件, user_id, NULL))` 数的是**在册订单的去重买家数**——`IF` 把不满足条件的行
变成 `NULL`，而 `COUNT(DISTINCT ...)` 不数 `NULL`。
→ **输出怎么读，这是整场压测的验收条件**：
  - 在册订单数 == 当初上架的库存 → 既没超卖也没少卖；
  - 在册订单数 − 去重买家数 == 0 → 没有人买到两张（这一列就是为此存在的）；
  - 关单数 + 已付数与预期比例对得上 → 超时关单链路真的跑了；
  - Redis 各桶库存求和 == 0、DB 库存 == 0、残留预占 == 0、残留处理中 == 0 → 没有状态泄漏。
→ 追问：「怎么证明退回去的库存是真的能再卖，而不只是改了个状态？」——第二波用**全新的一批用户**
去抢恰好那些退回来的单位，抢到了才算数。

Redis 侧的对账按桶逐个查再相加：

```text
redis-cli -c -h 127.0.0.1 -p 21001 -a "$LOCAL_DEALS_REDIS_PASSWORD" --no-auth-warning GET "sk:{sk:b3}:stock:42"
```
`{}` 里是 hash tag：Redis Cluster 只对花括号内的部分算 slot，所以同一个桶的库存计数、预占哈希、
处理中索引必然落在同一个节点，Lua 脚本才能原子地操作它们。桶号 0..K−1 各查一次求和。
→ 追问：「为什么要分桶？」——把热点 key 拆散到多个节点，详见第 04 章。

### 5.9 每阶段超时与清理

每一个阶段都套了超时，因为无人值守最怕的不是失败而是卡住：

```text
timeout --kill-after=30 1500 scripts/bench.sh _phase drain_round current
```
`timeout <秒> <命令>`：超时先发 `SIGTERM`；`--kill-after=30` 是再等 30 秒还不死就补 `SIGKILL`。
外层用退出码区分：`124` 是 timeout 判定超时，`137` 是 128+9 即被 SIGKILL 打死——两者都记成
「这一阶段超时」，其它非零退出码记成「这一阶段失败」。
→ 因为 `timeout` 只能跑外部命令、不能跑 shell 函数，所以脚本是**重新 exec 自己**并带上
一个内部子命令来执行那个函数的。
→ 追问：「为什么不用 `&` + `sleep` + `kill` 自己实现？」——`timeout` 已经处理好了进程组、
信号转发和退出码语义，自己写的版本在被测进程自己 fork 了子进程时几乎一定会漏杀。

清理挂在 `trap ... EXIT` 上，不管成功失败还是被中断都会跑：归档所有应用日志、停应用、
`docker unpause` 可能还被暂停着的 broker、拆掉自己的栈和 volume、删掉基线的 worktree，
最后把 `status` 从 `RUNNING` 改写成终态。**锁是在确认拿到之后才装这个 trap 的**——一次被拒绝的启动
绝不能去拆掉正在跑的那一场的容器。

结果目录是 `benchmark/v2/m<n>/<时间戳>-<场景>/`，读法：

1. `status` —— `RUNNING` / `DONE` / `FAILED: <阶段>: <原因>`。**不是 `DONE` 就不要分析。**
2. `manifest.json` —— commit、参数、分核、JVM 参数、机器、loadavg、当时还在跑的容器。
3. `summary.csv` —— 每次测量一行；演练另有各自的 CSV；套件运行还有 `scenarios.csv`。
4. `run.log` 与 `raw/`。

```text
tail -f benchmark/v2/m8/20260401-231500-m8-fullchain/run.log
less benchmark/v2/m8/20260401-231500-m8-fullchain/run.log
```
看一场还在跑的运行。`tail -f` 是 follow，文件追加什么就打印什么（`-F` 还能跟上文件被轮转）；
`less` 是分页器，`/` 搜索、`G` 跳末尾、`Shift+F` 等效于 `tail -f` 且能随时 `Ctrl+C` 退回浏览。
→ 诚实起见：脚本本身用的是 `tail -1`（取子命令打印的最后一行结果）和 `grep`；
`tail -f` 与 `less` 是我人工读 `run.log` / `app.log` 时用的，不在脚本里。

---

## 六、沿 traceId 查一次请求

nginx 在调用方没带头时用 `$request_id` 生成 `X-Trace-Id`，同一个 id 贯穿边缘日志、应用日志
和订单表的 trace 列。全链路演练最后一步就是拿一个已关闭订单的 trace id，去证明「这一行记录」
和「一分钟前那个请求」确实是同一件事。

```text
grep '<traceId>' benchmark/v2/run/m3bench/access.log
```
边缘侧：这个请求被 nginx 分给了哪个实例。日志格式是 `traceId upstream 状态码 请求耗时`，
所以第 2 列就是答案。
→ nginx 的这份访问日志是 `buffer=256k flush=2s` 缓冲写的——**写日志本身不能变成被测对象**。

```text
grep -h '<traceId>' benchmark/v2/run/m3bench/app.log benchmark/v2/run/m3bench/app-i*.log
```
应用侧：这个 id 的每一行日志。多实例时每个实例一个日志文件，`-h` 是多文件时不要在每行前面
打文件名（要文件名就去掉它，或者用 `-l` 只列出命中的文件）。
→ 脚本里用的变体：`grep -q` 只要退出码不要输出（判断「在不在」）、`grep -m1` 命中一条就停、
`grep -c` 只数条数（演练用它数「被重投了多少条」）。
→ 注意 `grep -- "$id"`：`--` 表示选项到此为止，后面即使以 `-` 开头也当成模式，这是脚本里
处理不可控变量的固定写法。

```text
python3 benchmark/v2/scripts/fixture.py one-trace 42
```
落库侧：取这张券的一个已落库订单，打印 `order_no,trace_id`，拿着它回上面两条 `grep`。
- 为什么不是直接 `SELECT ... FROM trade_order WHERE trace_id = ...`：宿主机的 `mysql` 客户端不经过
  分片中间件，看不到逻辑表，只能逐张物理表查；而按 trace 反查要扫八张表，按券号查才有分片键可用。
- 脚本默认挑一个**已关闭**的订单：准入路径上每请求一行日志在两万 QPS 下是不能要的，批量落库又只打 DEBUG，
  所以已付订单在日志里留不下痕迹；而关单那一行是 INFO，且带着当初买它那个请求的 trace，正好是全链路的接头。
→ 覆盖面要说清楚：HTTP 是全量带 trace 的；MQ → DB 这一跳只接了秒杀链和关单链。

→ 追问：「为什么不上 SkyWalking / Jaeger？」——见第九节。trace 是怎么生成、怎么进日志和订单行的，
详见第 11 章；被关掉的那些订单是怎么关的，详见第 06 章。

---

## 七、线上排障时我会先敲什么

**这一节必须分清哪些是真跑过的。** 上面第三到第六节里的每一条命令，都是这个项目的脚本或演练里
真实执行过的。下面这四条命令链**大多数没有在本项目中实际用到**——这台机器上的问题是用压测脚本
自带的仪表（actuator 指标、火焰图、CSV 列）定位的，不是用线上排障工具链。用过的和没用过的
逐条标注，被问到的时候不能把没做过的说成做过的。

### 7.1 CPU 高

```text
top -H -p 12345
printf '%x\n' 67890
jstack 12345 | grep -A 30 'nid=0x10932'
```
**通用做法，本项目未实际用到。** 思路：`top -H` 按线程显示（`-H` 是 threads 模式，`-p` 限定进程），
找出占 CPU 最高的那个线程的十进制 tid；`printf '%x'` 把它转成十六进制，因为 `jstack` 输出里的
`nid=` 是十六进制；再在线程栈里按 `nid` 定位，`-A 30` 打印命中行之后的 30 行也就是那一段调用栈。
连着抓三次栈比对，反复出现在栈顶的那几帧就是热点。

**本项目实际用的是这一条**：

```text
scripts/bench.sh profile 30 seckill-hot
```
内部执行：

```text
benchmark/v2/tools/async-profiler-3.0-linux-x64/bin/asprof -d 30 -e itimer -f benchmark/v2/m8/raw/seckill-hot.html 12345
```
async-profiler 采一张火焰图。
- `-d 30` —— 采样 30 秒。
- `-e itimer` —— 采样事件。**默认的 `cpu` 事件要用 perf 的硬件事件，而这台机器的
  `perf_event_paranoid` 大于 1，拿不到权限**，所以退到 `itimer`（基于定时器信号的采样，不需要
  perf 权限）。另一个常用值是 `wall`（墙钟采样），能看见**阻塞中的线程**——CPU 采样看不见在等锁、
  等 IO 的线程，而「接口慢但 CPU 不高」时要看的恰恰是它们。
- `-f xxx.html` —— 直接输出可交互的火焰图 HTML。
- 最后一个参数是目标 JVM 的 pid。
→ **火焰图怎么读**：横轴**不是时间，是样本数占比**——一个格子越宽，说明采样时越经常「正在执行它
或它的子调用」。纵轴是调用栈深度，向上是被调用方。看法是从底往上找第一个「宽得不合理」的格子，
那就是热点入口；平台期（顶上一大片同宽的平顶）就是真正在烧 CPU 的叶子方法。
→ 追问：「和 jstack 抽样有什么区别？」——jstack 是手动稀疏采样、还会 safepoint 暂停；
async-profiler 高频采样且支持 `-e wall` / alloc / lock 等多种事件，代价小得多。

### 7.2 接口变慢

```text
jstat -gcutil 12345 1000 10
```
**通用做法，本项目未实际用到。** 每 1000 毫秒打印一次、共 10 次的 GC 利用率。
输出列：`S0`/`S1` 两个 survivor、`E` eden、`O` 老年代、`M` 元空间的使用百分比，
`YGC`/`YGCT` young GC 次数与累计秒数，`FGC`/`FGCT` full GC 次数与累计秒数。
→ 怎么判异常：**`O` 一直高位不降、`FGC` 在涨**就是老年代回收不掉；`YGCT` 的增量除以 `YGC` 的增量
是平均每次 young GC 的耗时。

本项目在这个场景下实际做的是三件事：看 actuator 的 Prometheus 指标（延迟分位、批大小分布、
退化计数）、用上面的 `asprof -e wall` 看阻塞栈、按 traceId 把慢请求在边缘日志和应用日志里对上
（第六节）。

### 7.3 消息积压

```text
docker exec ld-m3bench-broker sh mqadmin topicStatus -n namesrv:9876 -t seckill-order-topic
```
**本项目实际用过**（压测脚本用它统计每轮写入的消息量）。`topicStatus` 列出该 topic 每个队列的
brokerName、queueId、**最小/最大 offset** 和最后更新时间；脚本用
`awk '$1 == b { s += $4 } END { print s + 0 }'` 把本集群那些行的最大 offset 相加，得到
「这个 topic 一共被写进过多少条消息」，测量前后各取一次相减就是这一轮的消息量。
半消息 topic 也这么数，用来核对事务消息的数量。
→ 判积压的正确形状是「最大 offset − 消费组的消费进度」，对应 `mqadmin consumerProgress -g <组>`；
**这一条本项目没有实际用到**，压测里判积压用的是「订单落库数追平接受数要多久」这个端到端口径。

### 7.4 连接池等待

```text
ss -s
```
**通用做法，本项目未实际用到。** `-s` 是 summary，打印各状态套接字的总数
（`estab` / `closed` / `timewait` 等）。排查方向：`timewait` 巨大通常是短连接没复用；
`estab` 贴着连接池上限说明池打满了。

本项目里连接池相关的事实是从另外两个地方来的，都**实际用过**：一是压测脚本在扫描消费参数时
把连接池上限跟着线程数一起调（线程数 + 8），二是 nginx 到上游用 keepalive 长连接
（`keepalive 512`、`keepalive_requests 100000`、`proxy_set_header Connection ""`），
理由写在配置旁边：**不这么做，测出来的是 TIME_WAIT 的性能而不是应用的**。
→ 追问：「为什么 `proxy_http_version 1.1` 和清空 `Connection` 头要一起写？」——nginx 默认用
HTTP/1.0 回源且会带 `Connection: close`，不改这两项 keepalive 配了也不生效。

---

## 八、口述版

### 8.1 「你们的压测是怎么做的」

先说环境。这是一台机器上的单机压测，所以第一件事是把 CPU 切开：被测应用绑一组核，依赖容器绑另一组，
压测客户端的容器再绑第三组。之所以非分不可，是因为不分核的话「应用变慢」和「压测客户端自己跑不动了」
在数字上是分不开的，你会把客户端的上限当成系统的拐点。多实例那一组场景还给每个实例各分一对核，
nginx 再一对，这样加一个实例是真的加算力，而不是把同一份预算切得更碎——否则测出来的是进程切换开销，
不是扩展性。

模型用的是开环，固定到达率往里打，而不是固定并发。原因是固定并发是个负反馈回路：系统一慢，
请求自然就发得少，于是过载行为永远测不出来，延迟曲线被系统自己压平了。开环就没有这个回路，
系统撑不住就是延迟涨、就是开始拒绝，曲线才有拐点可言。

整场是一条命令、无人值守的。它先做启动前检查，五件事任何一件不满足就直接拒绝跑：工作区必须干净，
因为每一行结果都要能对应到一个 commit，否则半年后没人知道这个数字是哪份代码跑出来的；
要用的端口一个个查过必须空着；盘上要留够十五个 G，因为消息存储和搜索引擎的数据都落在本地，
而盘满的表现不是写失败，是搜索引擎起来了但分片一直不分配，很难查；要有一个 JDK 21；
docker 也要能连上；这五件之外还有一把文件锁，保证同一时刻只有一场在跑。检查过了才起自己那一套隔离栈——独立的 compose 项目名、
独立端口、明确不读开发环境的配置文件，删的时候也只删自己这个项目的东西。

然后是构建。基线是从一个 tag 用额外工作区检出来单独打的，和当前代码用同一个 JDK，这样比较的
才只是代码。每次测量之前都要重启一个全新的应用，重新造用户和令牌，再跑一段不记录的预热——
没有预热的话，第一档同时也是即时编译第一次编热点方法、连接池第一次建连的那一刻，
那一档必然难看，而难看的原因和你改的东西无关。

测量分两种，回答两个不同的问题。一种是阶梯，一档一档抬高到达率，每档换一张新券，量的是准入侧的
延迟和拒绝行为；另一种是排空，一次性把整批库存高速放进去，然后量数据库追上来要多久，
量的是落库侧的吞吐。后者才是消费端真正的能力上限，因为前者在这台机器上早就被压测客户端自己卡住了。

采集不靠现成的监控，而是在每段测量窗口前后各读一次累计计数器再相减：应用从它 proc 目录下的
用户态加内核态时间，容器从 cgroup 的 CPU 累计纳秒数，除以墙钟时间就是这一段的平均核数。
应用停掉之前还要抓一次指标快照，因为批大小分布和降级计数只活在进程内存里，停了就没了——
有两个里程碑就是因为当时没这层仪表，结论只能写成「批可能没填满」，无法证实。

最后是怎么判断一次运行可不可信，这其实是最该讲的部分。第一看结果目录里的状态文件，不是 DONE
就不分析。第二看压测客户端的丢弃迭代数和实际到达率：开环模型下，如果到点了却没有空闲执行单元去发请求，
说明客户端没打满设定的速率，那一档量到的是客户端的上限不是服务端的。第三看客户端自己吃了几个核，
贴着它分到的核数就要打折看。第四翻当时记下的机器负载和同时在跑的容器，这台机器上还有别的项目
在跑且没绑核，两次数字对不上时先看这个。第五是最终对账必须干净：在册订单数等于上架库存、
去重买家数等于在册订单数、缓存和数据库的剩余库存都归零、没有残留的预占和处理中记录——
对账不干净的话，吞吐数字再好看也不作数。具体的数字和逐里程碑的结论在压测报告里。

### 8.2 「故障演练是怎么做的」

演练有三种：把应用打死、先把消息中间件打死再打死应用、把缓存集群的一个主节点打死。
都是在放行到某一秒、买家正在被准入的时候动手，然后量两件事：系统最终有没有收敛到正确状态，
以及收敛用了多久。

杀应用一定要用不可捕获的那个信号。普通的终止信号是可以被捕获的，虚拟机会跑关闭钩子、框架会优雅停机、
连接会排空、消费者会反注册——那样演练验证的就是优雅停机，而不是崩溃恢复，恰好把最想验的那条路径绕开了。
要模拟的是断电、被内存杀手干掉、容器被强杀这类根本没有机会收尾的情况，所以必须用那个内核直接回收、
一行清理代码都不会跑的信号。对照着说，日常停服走的是另一条路：先发普通信号，每秒轮询最多三十次
等它自己退出，还活着才补一刀。

最值得讲的是复制空窗那一次。背景是：之前杀过一次缓存集群的主节点，结果什么都没丢——因为将死的那些槽
会直接停止接受写入，买家收到的是服务不可用而不是一个假的「你中了」。那次的结论是「安全」，
但它同时意味着最危险的那条路径从来没被触发过：如果主节点收下了写、买家已经被告知中了，
而从节点一个字节都没收到，这时主节点死掉，被提升上来的从节点就会带着「已经卖掉的库存」回来，
缓存层会超发，这时候数据库那条带条件的扣减就是最后一道防线。这条防线从设计之初就在那儿，
却一次都没被真正触发过——一个从没被执行过的兜底，跟没有是一回事。

所以这一轮的任务是把这个不容易出现的故障逼出来。第一次尝试是让从节点睡过去，让它停止应用复制流，
结果恢复目标点还是零，一点没丢。原因想明白之后很有意思：从节点只是停止「应用」这些数据，
字节早就躺在它的接收缓冲区里了，睡醒一股脑全补上。**让接收方停下来是没用的，必须让发送方停下来。**
于是改成三步：先让从节点睡着——睡着的好处不只是停止应用，更关键的是它没法重连；
紧接着从主节点那一侧把所有复制连接掐掉，这样主节点此后收的每一个写都谁也到不了；
维持这个空窗几秒钟，再把主节点打死。

还有第三件事，是前两轮跑失败之后才想明白的：三个条件必须同时成立，这一轮才有意义。
第一，断链的那一刻库存必须还在卖，否则根本没有写落在空窗里；第二，新主节点重新服务的时候
必须还有买家在抢，否则那些「从死人手里回来的库存」根本没人来要；第三，买家总数必须多于库存，
否则缓存永远不会超发，数据库也就永远不会被要求拒绝一批订单，而那正是整件事的目的。
所以参数是算过的，不是随手填的：库存、速率、断链时刻、以及故意拖长的收尾时间，
凑出一个「空窗落在卖货中段、新主大约十几秒后回来、回来之后还有十几秒的需求」的窗口。

结果那一行里我最先看三列：这一轮的空窗到底有没有赌上东西、买家被告知中了但订单永远不会存在的数量
（这就是这套架构选择异步复制所付出的代价，是有界的、可以量化的）、以及最后那道防线被触发了几次。
超卖必须是零。要杀哪个节点是运行时查出来的——谁在服务零号桶就杀谁——不能写死名字，
因为第二轮的时候第一轮被杀的那台已经以从节点的身份回来了，再杀它什么也证明不了。

---

## 九、已知边界

- **没有 failsafe，所以「集成测试」是靠命名约定加 `-Dtest` 点名跑的**，不是构建生命周期的一个阶段。
  好处是一条命令能精确跑一个方法，代价是忘了点名就等于没跑，只能靠看 `Tests run` 的数字防守。
- **CI 只覆盖 MySQL 和 Redis 两种容器**，需要真消息中间件或真搜索引擎的六个 IT 在 CI 里是被排除的，
  只能本机补跑。「CI 绿」不等于「全部集成测试绿」。
- **竞态用例的「两种交错都出现过」是靠时延先手争取来的，不是形式化保证。** 真正能穷举交错的做法是
  确定性调度（如 JPF、Lincheck 这类工具），本项目没有用；现在的做法能挡住「这台机器上另一种顺序
  从没发生过还一直绿」，但挡不住「存在第三种更罕见的交错」。
- **CPU 口径是窗口内的平均核数，会掩盖尖峰。**
- **没有分布式链路追踪系统**，traceId 是自己在边缘和应用里串起来的，靠 grep 查，没有服务拓扑、
  没有跨服务的 span 时间线。单体加三个实例的规模下够用，真上微服务就不够。
- **线上排障那一节里的命令链大部分没有在本项目里执行过**，已经逐条标注。
- 压测是单机的，所有结论都带这个前提；数字与方法论详见第 12 章。

---

## 附：代码索引

- 单元与切片测试入口：`mvn -o test`；Maven 配置在 `pom.xml`（无 surefire / failsafe 配置，
  只有 `spring-boot-maven-plugin`）
- CI：`.github/workflows/ci.yml`（`unit` job = `mvn -B test`；`integration` job 带
  `LOCAL_DEALS_IT_CONTAINERS=true` 并用 `-Dtest='*IT,!...'` 排除六个需要真 broker / ES 的类）
- Testcontainers 接入：`src/test/java/com/localdeals/platform/testsupport/ContainersInitializer.java`，
  经 `src/test/resources/META-INF/spring.factories` 注册
- 隔离栈与集成测试入口：`scripts/stack.sh`（`up` / `env` / `it <pattern>` / `build` /
  `app-start` / `app-stop` / `lb-start` / `lb-stop` / `pin` / `status` / `down`）
- 竞态用例（闩锁 + 先手时延）：`src/test/java/com/localdeals/trade/payment/PaymentRefundIT.java`
  （`paymentAndCloseRacingLeaveExactlyOneConsistentOutcome`、`verifyAndRefundRacingLetExactlyOneWin`、
  `aPaymentArrivingAfterTheCloseIsRefundedAutomatically`、私有 `concurrently` helper）
- 竞态用例（纯闩锁）：`src/test/java/com/localdeals/trade/service/TradeOrderPersistenceIT.java`
  （`concurrentOrdersForTheSameUserAndVoucherLetExactlyOneWin`）、
  `src/test/java/com/localdeals/marketing/MarketingGrantConcurrencyIT.java`（`ready` + `start` 双闩）、
  `src/test/java/com/localdeals/trade/service/CouponVerifyIT.java`
- 竞态用例（把一方钉在临界区里，四道闩）：
  `src/test/java/com/localdeals/platform/utils/SingleFlightLoaderTest.java`
  （`thirtyTwoOverlappingRequestsExecuteOneCallback`、
  `interruptedFollowerRestoresFlagWithoutRemovingLeaderEntry`、
  `followerTimeoutDoesNotCancelLeaderRemoveEntryOrStartSecondLoad`）、
  `platform/utils/CacheClientTest.java`、`platform/service/LocalReadBulkheadTest.java`、
  `platform/websocket/WebSocketSessionIsolationTest.java`（在 mock 的写方法里塞睡眠撑宽窗口）
- 环境门控示例：`M6aMysqlStopFailureIT`（`M6A_ISOLATED` + `M6A_DOCKER_FAULT`）、
  `M6aFlywayIT` / `M6cFlywayIT`、`CanalSyncIT`（`M5A_ISOLATED`）、
  `BlogLikeReliabilityIT`（`LOCAL_DEALS_RUN_ISOLATED_LIKE_IT`）
- 测试 profile（关掉后台消费者与扫描任务）：`src/test/resources/application-test.yaml`
- 压测单一入口：`scripts/bench.sh`（`users` / `step` / `drain` / `profile` / `m3` / `m4` / `m5` /
  `m6` / `m6-consume` / `m8` 及各自 `-smoke`；内部有 `preflight` / `phase` / `scenario_cleanup`）
- 压测脚本：`benchmark/v2/scripts/seckill.js`（准入与排空）、`benchmark/v2/scripts/fullchain.js`（全链路）
- fixture（造用户、券、数订单、对账 SQL）：`benchmark/v2/scripts/fixture.py`
- 应用崩溃演练：`benchmark/v2/scripts/kill-drill.sh`（`BROKER_DOWN=1` 变体）
- Redis 主节点与复制空窗演练：`benchmark/v2/scripts/redis-kill-drill.sh`
  （`REPLICA_SLEEP_S` / `REPLICA_GAP_S` / `DRILL_TAIL_S` / `FAILOVER_TIMEOUT`）
- 全链路对账演练：`benchmark/v2/scripts/fullchain-drill.sh`
- 操作手册：`docs/runbook.md`；方法论与数字：`docs/notes/12-benchmark-and-drills.md`、
  `docs/benchmark-report.md`
