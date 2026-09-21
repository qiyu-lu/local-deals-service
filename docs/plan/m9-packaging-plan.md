# M9 执行计划：改名、文档收口、学习笔记

> 本文件是 [V2 重构计划](v2-high-concurrency-plan.md) 里 M9 的展开，供**其他会话**按会话逐个执行。
> 写于 2026-09-21，基于 `v2/main` @ `f7f0bbe` 的实际状态。以代码为准。
> 前置：M0–M6、M8 已完成；「拆 `tb_seckill_voucher` 热点行」2026-09-21 决定**不做**，作为已知边界保留。

## 0. 怎么用

分三个会话，顺序执行，全部在同一个分支 `v2/m9-packaging`（从 `v2/main` 拉）上：

| 会话 | 任务 | 是否开多 agent | 预计 |
| --- | --- | --- | --- |
| S1 | 任务 A 改名 + 任务 B 文档收口 | 否（两件事都改 README，上下文相互依赖） | 半天 |
| S2 | 任务 C 学习笔记 | **是**（12 章互相独立，见 3.4） | 一天 |
| S3 | 收尾：ADR 0010、进度表、合并、打 tag | 否 | 一小时 |

### 提示词（每个会话用这一条，换会话号即可）

```text
读 docs/plan/m9-packaging-plan.md，执行会话 S<n>。
先看 git log v2/main..HEAD 和 git status 判断做到哪一步，再读本会话任务引用的文件确认现状仍成立，
不成立的地方先告诉我再动手。规则沿用 docs/plan/v2-high-concurrency-plan.md 第 0、2 节：
每类改动独立提交，提交前 mvn test 全绿（纯文档提交跑 scripts/check-docs.py 即可），不要 push。
只有会删除无法找回的东西、或要动 GitHub 远端时才先问我。
```

### 通用规则

- **数字只抄不算**：README、笔记里出现的任何性能/演练数字，只能来自 `docs/benchmark-report.md` 或进度表，
  并且原样抄。不要从冒烟目录取数，不要四舍五入成更好看的数。
- **笔记里的事实只来自代码**：TTL、重试次数、状态名、先后顺序、失败时的行为，都要在代码里看到才能写。
  ADR 与旧设计文档只能当线索，和代码冲突时以代码为准，并在该章「已知边界」里记一句。
- 本计划没有覆盖的取舍：给推荐方案和理由后继续做。

## 1. 任务 A：改名与去教程化（S1）

### 1.1 现状（2026-09-21 走查）

- GitHub 远端已经是 `qiyu-lu/local-deals-service`，`pom.xml` 的 artifactId / name 也是 `local-deals-service`，
  包名是 `com.localdeals`。**只有本地目录还叫 `hm-dianping`。**
- 仍带教程痕迹的已跟踪文件（`git grep -n "hm-dianping\|hmdp\|黑马" -- ':!benchmark'`）：
  - `README.md:1` 标题「HM Dianping 本地生活平台」，`README.md:5`「基于黑马点评教程原型」在首屏；
  - `frontend/user/*.html` 10 个页面的 `<title>黑马点评</title>`，`login.html` / `login2.html` 的
    「《黑马点评用户服务协议》」，`shop-detail.html:162` 的 `copyright ©2021 hmdp.com`；
  - `frontend/nginx.conf:35` 的 `root /usr/share/nginx/html/hmdp` 与 `docker-compose.yml:114` 的挂载点（两处必须一起改）。
- `benchmark/**/run.log`、`scenarios.csv` 里有绝对路径 `/home/.../hm-dianping/...`：**不改**。它们是某次运行的原始记录，
  改了就不再是原始记录。

### 1.2 决策

- **仓库名定为 `local-deals-service`，不再改成主计划里举例的 `local-deals-platform`。** 理由：远端、artifactId、
  镜像名已经一致，再改一次只是制造不一致。「平台」这个词放进定位语，不放进仓库名。
- 定位语（README 首句、GitHub description 共用）：**本地生活商户券营销与交易平台——面向万级并发的秒杀、订单闭环与故障恢复**。

### 1.3 步骤

1. 前端去教程名：`<title>` 与协议文案改为「本地优选」（或沿用管理端已有的产品名——先看 `frontend/admin/index.html`
   用的是什么，两端保持一致）；copyright 行改为项目名 + 当前年份或直接删除；`login2.html` 若无任何入口引用则删除。
   提交：`chore(frontend): drop the tutorial's name from the user pages`。
2. nginx 根目录与 compose 挂载点 `hmdp` → `app`，两处同一个提交；改完用 `scripts/stack.sh` 起一次栈，
   `curl -I` 首页返回 200 才算数。`scripts/bench.sh` 若自己生成 nginx 配置并引用了该路径，一并改并跑一次
   `m8-fullchain` 的冒烟参数确认没断（1–2 分钟）。提交：`chore(deploy): rename the static root`。
3. README 标题与首段在任务 B 里整体重写，这里不单独改。
4. **需要我手动做的事（会话只列清单，不执行）**：
   - 本地目录改名：`mv ~/IdeaProjects/hm-dianping ~/IdeaProjects/local-deals-service`。
     注意 Claude Code 的项目记忆按路径存放，改名后要同时
     `mv ~/.claude/projects/-home-sd101t-IdeaProjects-hm-dianping ~/.claude/projects/-home-sd101t-IdeaProjects-local-deals-service`，
     否则记忆与历史会话全部丢失。IDEA 重新 Open 一次即可。**放在 S3 合并打 tag 之后再做**，避免执行中途路径变化。
   - GitHub 上的 description、topics（`seckill` `rocketmq` `redis-cluster` `shardingsphere` `spring-boot-3`）。
   - 是否把 `v2/main` 合回 `main` 并 push：S3 结束时由我决定，会话不要做。

验收：`git grep -n "hm-dianping\|hmdp\|黑马" -- ':!benchmark' ':!docs/plan'` 只剩 README 文末致谢那一句。

## 2. 任务 B：文档收口（S1）

### 2.1 目标形态

```text
README.md                      首屏即结论
docs/README.md                 索引
docs/adr/0001…0010             每个里程碑一页
docs/benchmark-report.md       已定稿，不动
docs/runbook.md                由 guides/environment-setup.md 改写
docs/plan/                     两份计划，历史记录，不计入行数预算
docs/notes/                    任务 C 的产物，不计入行数预算
```

行数预算：`docs/README.md + adr/ + benchmark-report.md + runbook.md` ≤ 2000 行（当前约 1400，加 runbook 与 ADR 0010 后约 1650）。

### 2.2 步骤

1. **`docs/runbook.md`**（新写，约 150 行，替代 `docs/guides/environment-setup.md`）。只写「怎么操作」，不写「为什么」：
   - 开发栈起停（`scripts/stack.sh`）、隔离栈、JDK 21 设置、Quick Start 走通一次秒杀→支付→核销的 curl 序列；
   - 压测：`scripts/bench.sh` 的场景名清单、每个场景的预计耗时、结果目录怎么读（`status` → `manifest.json` → csv）；
   - 故障演练怎么复现：`kill -9` 应用、杀 Redis master、复制空窗（`m8-window`）；
   - 排障：按 traceId 从 nginx access log → 应用日志 → `trade_order.trace_id` 反查的三条命令；
   - 从旧 guide 搬过来仍然成立的「常见问题」（ES 磁盘水位、端口段、Docker bridge 重试等）——逐条在当前脚本里验证还成立再搬。
   - `figure/手机模式.png` 只被旧 guide 引用：runbook 若不再需要这张图，连同 `figure/` 一起删。
   提交后 `git rm -r docs/guides`。
2. **删除 `docs/design/` 四篇**。它们描述的是 V1；秒杀那篇已被 ADR 0003–0006 取代，RBAC / 点赞 Outbox / 指标三篇的内容
   会由任务 C 的对应章节按当前代码重写。tag `v2.0-m8` 里永久可查（`git show v2.0-m8:docs/design/<file>`）。
   删之前 `git grep -n "docs/design\|design/"` 清掉所有指向它们的链接。
3. **`README.md` 重写**（目标 ≤ 220 行）。首屏（不滚动能看到的部分）依次是：
   1. 一句话定位（1.2 的定位语）+ 一句业务闭环（商户建活动 → 用户抢/领/买 → 券资产 → 核销 → 退款/过期）；
   2. 目标架构图：用主计划第 3 节那张 mermaid，但**按实际落地修正**——去掉没做的 Canal → ES 订单读模型，
      Redis 标成 3 主 3 从分桶，DB 标成 2 库 × 4 表 + 单库的库存表；
   3. 实测数字表（5–6 行，全部抄自 benchmark-report 第 2 节）：准入拐点 5k → 20k req/s、half message = 请求数 → 0、
      落库 156 → 2089 单/s（单实例）/ 3026（3 实例）、10 万人抢 1000 件三方对账闭合；
   4. 故障演练结论三行：应用 `kill -9` 收敛时间、Redis master 宕机丢失预占 0、复制空窗 Redis 多放 10.3% 而 MySQL 超卖恒 0。
   首屏之后：订单状态机图（现有的保留）、模块划分（trade / marketing / merchant / content / platform 各一句）、
   Quick Start（指向 runbook）、**已知边界**（抄 benchmark-report 第 4 节的要点：准入曲线受 k6 限制、写路径上限是库存那一行、
   单机压测、mock 支付、未覆盖的故障类型）、文档索引、文末一句致谢「业务原型源自黑马点评教程」。
   现有 README 里与上面重复或已过时的段落（M1/M2 逐里程碑叙述）删掉，不要保留两套说法。
4. **`docs/README.md` 索引重写**：补上 benchmark-report、runbook、ADR 0006–0010、M5/M6/M8 的复测与扫描链接；
   删掉 design 与 guides 两行和「目标形态（M9）」那句。
5. 主计划 `v2-high-concurrency-plan.md` 只改进度表 M9 行（S3 做），正文不动——它是历史记录。
6. 每一步后 `python3 scripts/check-docs.py` 必须通过；最后 `wc -l` 核对行数预算并写进提交 message。

## 3. 任务 C：学习笔记（S2）

### 3.1 目的与读者

读者只有我自己，场景是秋招面试前复习：**不打开代码就能把每条链路讲出来，并接得住追问。**
所以判断一段文字该不该留的标准是「面试时用不用得上」，不是「代码里有没有」。

每一章包含**两个内容**：

1. **详细流程**——按请求/事件的时间顺序，写清每一步做什么、为什么、失败了会怎样。用于理解和查漏。
2. **口述版**——同一条链路的口语化连贯叙述，省掉不重要的细节只留主干，用于背和讲。

### 3.2 写法规则

- **少用项目私有名字，用语言描述代替。** 类名、方法名、表名、Redis key 字面量、Lua 文件名、配置项名，正文里都不出现。
  - 表：写成「一张订单表，关键字段有订单号（主键，低位嵌了用户 id 的基因）、用户 id（分片键）、状态、过期时间、版本号，
    分别用来……」；
  - Redis：写成「每个库存桶下有四样东西：剩余库存计数、一个记录『谁预占了』的集合、一个按订单号存状态的哈希、
    一个按到期时间排序的处理中索引」；
  - 类：写成「订单状态机」「批量消费者」「对账器」这类角色名。
  - **通用技术名词照常用**：Lua、CAS、`INSERT IGNORE`、ZSET、hash tag、half message、Snowflake、`stock >= n` 条件更新等，
    这些是面试语言，不算私有名字。
  - 每章末尾留一个很短的「代码索引」小节（角色名 → 真实类/表/脚本名，一行一个），正文保持干净，需要回查时有路可走。
- **详细流程的格式**参照附录 A 的样例一：一级是「谁发起、想要什么、到哪一步结束」的一段话；其下用分层列表，
  第一层是主干步骤，第二层是该步骤的分支、参数含义、失败行为。和样例不同的是要按上一条去掉私有名字。
  具体常量（TTL、次数、批大小、线程数）要写，因为会被追问；错误码、日志文案、DTO 字段这类不写。
- **口述版的格式**参照附录 A 的样例二（我自己写的，**学它的叙述方式，不要学它的内容——里面有错**）：
  第一人称、连贯成段、不用列表、不出现任何字面量；每条链路 300–600 字，能在 2–3 分钟内讲完；
  每个「然后」都要带上「为什么」的半句话（例：「用一个 Lua 脚本一次做完，是因为检查冷却和写验证码之间不能被另一个并发申请插进来」）。
  口述版里**必须保留**的：主干顺序、每个原子性/幂等的手段、关键的失败兜底。**可以省**的：参数校验、具体常量、次要分支。
- 每章固定结构：

  ```text
  # NN 标题
  ## 一、这一章解决什么问题          3–5 句：业务上要什么，难点在哪
  ## 二、涉及的数据                 用语言描述的表、缓存结构、消息；只列与本章有关的字段
  ## 三、详细流程                   按 3.2 的格式；一章可含多条链路（3.1、3.2…）
  ## 四、口述版                     每条链路一段，编号与第三节对应
  ## 五、为什么这么设计 / 会被怎么追问   备选方案与取舍；5–8 个追问及要点式回答
  ## 六、已知边界                   没做的、做了但有缺口的，直说
  ## 附：代码索引
  ```

- 每章 200–400 行。宁可把一条链路讲透，也不要罗列所有接口。
- 第五节的素材主要来自对应 ADR 的「备选 / 代价」和主计划里的「执行时发现的计划偏差」——那些是真实踩过的坑，
  比凭空编的追问有价值。

### 3.3 章节划分

产物放 `docs/notes/`。每章列出的是**起点**，agent 自己顺着调用链读下去。

| 章 | 文件 | 范围 | 起点 |
| --- | --- | --- | --- |
| 00 | `00-overview.md` | 项目一句话、业务闭环、架构、各层职责（Redis 可丢 / MySQL 最后防线）、数字总表、瓶颈怎么一层层移动；**口述版 = 3 分钟项目介绍 + 1 分钟精简版** | README（S1 重写后）、benchmark-report、主计划第 1、3 节。**最后写**，见 3.4 |
| 01 | `01-consumer-login.md` | 验证码申请与消费、会话 token、两级拦截器与 token 续期、公开/受保护接口 | `platform` 下的登录服务与拦截器、两个登录 Lua |
| 02 | `02-merchant-admin.md` | 管理员登录与失败锁定、RBAC 权限注解、商户数据隔离（merchant scope）、认证版本号失效、操作审计切面、后台 WS ticket | `merchant/auth`、`merchant/interceptor`、`merchant/audit`，迁移 V5 / V6 / V15 |
| 03 | `03-shop-read-path.md` | 店铺详情缓存（穿透/击穿：空值、逻辑过期、互斥重建）、GEO 附近店铺、ES 搜索与 search_after、图片上传归属 | `merchant` 下店铺服务、缓存工具、ES 相关服务 |
| 04 | `04-seckill-admission.md` | 准入漏斗：本地售罄标记与广播、本地令牌桶、单次 Lua（限频+活动校验+判重+扣减+写预占）、本地 Snowflake 与用户基因、库存分桶与 hash tag、只有成功者才发普通消息 | `trade` 秒杀服务入口、`seckill_check.lua`，ADR 0004 / 0006 |
| 05 | `05-seckill-persistence.md` | 批量消费（分组、`INSERT IGNORE`、每批每券一次条件扣减、退化为逐条）、认领租约、批量标记成功、对账器重驱动与补偿、进程被 kill 后如何收敛、消费参数为什么是那一组 | `trade/mq`、claim / mark_success / reconcile / compensate 各 Lua，ADR 0005 / 0007 |
| 06 | `06-order-lifecycle.md` | 订单状态机与 CAS、限购唯一键的生成列技巧、mock 支付渠道（预下单、HMAC 回调、乱序重试、幂等）、定时消息关单 + 扫描兜底、退款、库存回补的幂等、五类竞态各自怎么解 | `trade/payment`、订单状态机、`seckill_release.lua`、迁移 V14，ADR 0003 |
| 07 | `07-coupon-asset.md` | 券资产统一（购买与营销发放两个来源）、核销 CAS 与商户校验、核销码防枚举与限频、过期任务、退款与核销互斥 | 券资产服务、`coupon_verify_rate.lua`，ADR 0003 |
| 08 | `08-order-sharding.md` | 2 库 × 4 表、基因法为什么让两种查询同片、唯一键片内唯一为何等于全局唯一、库存表为什么不做广播表、被分片拒绝的五类 SQL、LOCAL 事务的代价、附属表主键与多实例 workerId 缺陷 | `trade/sharding`、迁移 V16，ADR 0008 / 0009 |
| 09 | `09-marketing.md` | 活动/标签/定向发放、额度与幂等、批量发放 Job、签到任务奖励、通知 Outbox → WebSocket 多实例扇出与离线兜底 | `marketing`、`platform/websocket`，迁移 V9–V11 |
| 10 | `10-like-outbox-hot-rank.md` | 点赞 Outbox（写事实 + 异步投影）、热榜的发布与刷新、降级回 DB | `content`、两个 hot_rank Lua，迁移 V7 / V8 |
| 11 | `11-observability.md` | traceId 三跳三种机制（HTTP → MQ → DB → 一分钟后的关单日志）、关键指标与它们各自回答什么问题、积压观测 | `platform/observability`，迁移 V17，ADR 0009 |
| 12 | `12-benchmark-and-drills.md` | 压测方法（开环 vs 闭环、分核、预热、一次运行能否采信）、逐里程碑数字、三次演练（kill -9 / 杀 master / 复制空窗）的做法与结论、「为什么没到 3000 单/s」「为什么准入曲线测不出扩展性」这类反问 | benchmark-report、各 `comparison.md`、ADR 0005–0009。**本章数字一律原样抄** |

### 3.4 多 agent 执行方式

- 12 个内容章（01–12）上下文互相独立，**每章一个 agent**；00 总览依赖其他章，由主会话在全部章节回来之后自己写。
- **模型要求：所有 agent 用 Opus 5、medium 推理强度。** Agent 工具的 `model` 参数填 `opus`；推理强度不是调用参数，
  来自 agent 定义文件。所以 S2 先创建 `.claude/agents/m9-note-writer.md`（frontmatter：`model: opus`、medium 推理强度、
  工具给读/搜/写/只读 Bash），正文放 3.1–3.2 的全部规则和附录 A；frontmatter 里推理强度的确切字段名若不确定，
  先问 `claude-code-guide` agent，不要猜。这个文件**不提交**，S2 结束时删除。
- 分两波，每波 6 个并发（同一条消息里发出）：第一波 04 05 06 07 08 12（主线，先拿到先检查），第二波 01 02 03 09 10 11。
- 给每个 agent 的 prompt 只需要：章号、文件名、3.3 表里该行的范围与起点、「只写自己那一个文件，不改任何其他文件，不提交」。
  章节边界有重叠时（例：04 与 05 都会碰到预占状态），**各写各的、点到为止并写一句「详见第 NN 章」**，不要互相等待。
- **校对波**：每章写完后，另起一个**新的** `m9-note-writer` agent（不带写作 agent 的上下文）做校对，prompt 为：
  「逐条找出本章里可被代码证伪的陈述（顺序、常量、失败行为、原子性声明），到代码里核对；错的直接改，
  找不到依据的删掉或移入『已知边界』；检查口述版与详细流程有无矛盾；检查正文是否漏进了私有名字。返回改动清单。」
  口述版是我要背的东西，**背错比没有更糟**，所以这一波不能省。
- 主会话的职责：发任务、读每章的校对清单、抽查每章一条链路（自己对着代码走一遍）、统一术语（同一个角色在各章叫同一个名字，
  在 00 里给一张「角色名表」）、写 00、跑 `check-docs.py`。
- 提交：每章一个提交 `docs(notes): NN <主题>`，00 最后提交。

### 3.5 验收

- 13 个文件齐全，结构符合 3.2；`git grep -nE "Impl|Mapper|tb_|trade_order|\.lua" docs/notes` 的命中只出现在各章「代码索引」小节。
- 每章的校对清单在会话里向我汇报过（改了几处、哪些陈述因无依据被删）。
- 我自测：随机抽两章，只看口述版讲一遍，再用主计划的提示词 D 让会话追问——这一步由我做，不属于 S2。

## 4. 收尾（S3）

1. **ADR 0010**（`docs/adr/0010-m9-packaging.md`，≤ 60 行）：仓库名为什么停在 `local-deals-service`；主干只留哪几类文档、
   为什么 V1 设计文档删而不改；笔记为什么用「角色名 + 代码索引」而不是类名；**不拆库存热点行**的决定与理由
   （三实例 3026 单/s 对目标场景已足够，「测实了、知道怎么拆、为什么没拆」比多一个分桶更值得讲）放在这里落字。
2. 主计划进度表 M9 行打勾，填 tag 与一句话结果（文档行数前后对比、笔记章数）；文件头状态改为「已完成」。
3. `mvn test` 全绿 → `--no-ff` 合回 `v2/main` → tag `v2.0-m9`。不 push。
4. 向我列出 1.3 第 4 步那三件手动的事，结束。

## 附录 A：笔记文风样例

**样例一：详细流程的层次与颗粒度**（V1 时期的旧笔记节选。学它的层次；不要学它满篇的类名与 key 字面量——按 3.2 换成语言描述）

```text
一、消费者申请验证码
消费者调用 POST /user/code，带来手机号，希望取得一轮可以用于登录的验证码。这个请求到验证码状态写入 Redis 并返回为止，
不会在同一次请求里继续登录或访问业务接口。
● 请求先进入消费者拦截器链。POST /user/code 属于公开入口，没有登录也可以进入 UserController，随后由 UserServiceImpl
  检查手机号格式并生成六位数字验证码。
● 服务用手机号拼出三类 Redis key，再把它们连同验证码和两个有效期传给 issue_login_code.lua。
  ○ ARGV[2] 是验证码有效期 120 秒，ARGV[3] 是发送冷却 60 秒。
  ○ 脚本先对冷却 key 执行 EXISTS。已经存在就返回 0，原验证码和失败记录都不改；Java 看到结果不是 1，抛出 429，本次请求结束。
  ○ 冷却不存在时，脚本先写验证码，再建立冷却，最后删除旧失败次数并返回 1。这些命令在一次 Lua 中连续执行，
    另一个并发申请只能等脚本结束后再看到冷却。
  ○ Redis 执行抛异常时，Java 把认证状态记为不可用并返回 503。
● 当前项目没有接入真实短信供应商，成功只表示验证码状态已经写入 Redis，不表示短信已经送达。
```

**样例二：口述版的叙述方式**（我自己写的。学它的口吻与取舍；内容有错，不要照抄——例如「冷却 key 存在」说明的是
60 秒内刚发过，而不是「之前的验证码还没过期」，验证码有效期是 120 秒）

```text
首先消费者申请验证码，通过拦截器，登录接口是公开的所以放行，然后检查手机号格式、生成验证码。接着用手机号拼出三个 Redis key，
分别是这个手机号的发送冷却、验证码本身、登录失败次数，用一个 Lua 脚本一次完成检查和写入：先看冷却的键在不在，
在就直接返回，原来的验证码和失败记录都不动；不在的话就写入验证码并设过期时间、建立冷却并设过期时间，然后清掉失败次数。

然后用户拿到验证码准备登录，输入手机号和验证码，希望换到一个会话 token。先检查手机号格式，合法的话根据手机号得到验证码和
失败次数两个 key，同样交给一个 Lua 脚本：先读失败次数，达到上限 5 次就直接返回，不再读验证码；没到上限就读验证码，
不存在就返回；存在并且和输入一致，就同时删掉验证码和失败次数，保证一个验证码只能被消费一次；不一致的话给失败次数加一，
并让失败次数的过期时间跟验证码剩余时间对齐，累计到上限就把验证码也删掉。
验证码消费成功后，按手机号去用户表查，查到就沿用，查不到就建一个用户；最后生成 token 写进 Redis 并设过期时间，返回给客户端。
```
