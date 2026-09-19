# ADR 0002：M1 升级到 Java 21 / Spring Boot 3.5

- 状态：已采纳（2026-09-19，tag `v2.0-m1`）
- 关联：[V2 计划 M1](../plan/v2-high-concurrency-plan.md)、[M1 同场复测](../../benchmark/v2/m1/comparison.md)

## 背景

M0 结束时项目跑在 Java 8 / Boot 2.3.12 / Spring Data ES 4.0（RestHighLevelClient 7.6）/
MyBatis-Plus 3.4 上。后面 M2–M8 每个里程碑都要加代码，先升级可以避免迁移两次；M8 的虚拟线程
对比实验也需要 Java 21。MyBatis-Plus 3.4 的 lambda 查询在 JDK 17 上初始化失败，所以隔离栈和 CI
都被迫锁在 Java 8。

## 备选

1. **Boot 3.5.x（采纳）**：3.x 的最后一条线。rocketmq-spring 2.3.x、`mybatis-plus-spring-boot3-starter`
   都是面向 Boot 3 / Spring 6 构建和测试的。
2. **Boot 4.x**：已发布（4.1.1），但它换到 Spring 7、Jackson 3，自动配置也拆成了模块，第三方
   starter 的适配要单独再做一遍。一次同时跨 javax→jakarta 和 Boot 4 两道坎，出问题时很难分清是
   哪一层的原因。
3. **停在 Java 8，跳过 M1**：计划允许在超出时间盒时这么做，但这次一天之内就做完了，没有理由保留。

ES 客户端方面，计划给了一个退路：先把搜索收敛到一个接口后面，临时用低级 RestClient。实际上
搜索代码只有两处查询和一个同步消费者，直接迁到 Spring Data ES 5.5 的 `NativeQuery` 就行，
退路没有用上。

## 决策

- **平台版本**：Java 21（Temurin）、Boot 3.5.16、MyBatis-Plus 3.5.17、mysql-connector-j、
  Flyway 11.7（加 `flyway-mysql`）、rocketmq-spring 2.3.6（client 5.3.2）、Redisson 3.52（留在 3.x，
  项目只用到 `RLock`，没有必要去 4.x）、Hutool 5.8、ES server 与 client 都是 **8.18.8**，IK 同版本。
- **Broker 保持 4.9.4**：client 5.x 的 remoting 协议兼容 4.9，`SeckillWithRocketMQIT`、
  `SeckillOrderRetryIT` 都是绿的。Broker 升到 5.x 放到 M2，因为定时消息要用它。这样 M1 复测时
  只有应用这一个变量在变。
- **先锁住会静默失效的地方**（red 提交）：`PlatformBaselineTest` 锁运行时；`Boot3ConfigurationKeysTest`
  按 Boot 3 的读法绑定 `application.yaml`。`spring.redis.*` 这类旧 key 在 Boot 3 下会被**静默忽略**，
  应用悄悄连到 localhost 的默认值上，启动时什么都不报。测试代码自己也踩了这个坑：
  `ContainersInitializer`（CI 的 Testcontainers 入口）和两个 IT 也写着旧 key，一并修掉了。
- **库行为变化不靠改断言硬过**：
  - Hutool 5.8 解析 JSON 数组时默认丢掉 null 元素，于是损坏的缓存 `[null]` 被当成“空列表命中”
    返回，本该回源修复。`ShopTypeServiceImplTest` 抓到了这个问题，修法是解析时设
    `ignoreNullValue=false`，属于生产代码的修复。
  - MyBatis-Plus 3.5 从 mapper 的 MyBatis 代理反推实体类，Mockito mock 不是这种代理。这只影响测试
    的注入方式，所以加了 `MybatisPlusMocks.injectMapper`，生产代码不动。
- **迁移中发现的旧缺陷，单独按 TDD 修**：`searchShops` 连续两次 `withFilter`，builder 只保留最后
  一个，“类型 + 距离”的搜索会漏掉类型过滤（旧 builder 也是这样）。两个条件合进同一个 `bool.filter`
  后修复。`ShopSearchAfterIT` 在 V1 和 M0 上一直是红的，ADR 0001 记的原因是“依赖 ES 已有数据”；
  真实原因是它只往 ES 写数据，没往 MySQL 写。改成自带数据之后，它是唯一一个端到端验证
  IK + geo_distance + 距离排序的测试。
- **环境**：ES 8 在磁盘高水位 90% 以上就不再分配分片，而这台宿主机的系统盘正好在 90%。沿用
  `ad16b03` 的做法，把数据目录挪到可配置的数据盘（`LOCAL_DEALS_ES_DATA_ROOT`），不去调高水位。
  镜像改用带版本号的 tag `local-deals-elasticsearch:8.18.8-ik`：8.x 会把 7.x 的数据目录升级，而且
  不可逆，开发栈的旧容器在有人主动重建之前继续用 7.17。
- **不开虚拟线程**：`spring.threads.virtual.enabled` 留给 M8 做 on/off 对比，M1 只换运行时，不换
  线程模型。

## 结果

- `mvn test`：Java 8 上 303 个 → Java 21 上 311 个全绿（多出的 8 个是 red 提交），编译警告 0。
- 隔离栈 `*IT`：Java 8 时 35 过 2 挂（`ShopSearchAfterIT`）；Java 21 时执行的 38 个全绿。
- 同场复测（对照组是 `v2.0-m0` 的 jar 跑在 Java 8 上）：准入天花板 14.5k → 17.5k req/s（+21%），
  10k 档 p99 73.7 → 51.8 ms，同吞吐下 CPU 少 20%；拐点仍是 1 万 req/s。落库 180.8 vs 155.6 单/s
  （中位数），区间重合，测不出差别。

## 代价

- **准入的提升没有归因**：JDK、GC 默认值（Parallel → G1）、MQ/Redis/Tomcat 客户端是一起换的。
  要讲清“为什么快了”，得补火焰图和 GC 单变量对照；这次没做，所以不声称原因。
- **仍有没覆盖到的 IT**：51 个测试方法挂在 `M5*/M6*_ISOLATED` 和 sentinel 守卫后面，升级前后
  都是跳过的，其中包括 `ManagementEndpointSecurityIT`（Actuator）、`CanalSyncIT`、营销隔离 IT。
  这些正是 Boot 3 改动可能波及的地方，是本次升级最大的剩余风险。ADR 0001 已经把“统一隔离栈
  门控”记为待办。
- **Boot 3.5 的开源支持已在 2026-06 结束**，迟早还要升一次 Boot 4。届时的差异面是 Spring 7 /
  Jackson 3 / 模块化自动配置，不再和 jakarta 迁移搅在一起。
- **开发栈没有升级**：`local-deals-es` 仍是 7.17 镜像。它和 8.18 的客户端不兼容，开发环境要跑搜索
  必须重建 ES 容器（索引可以从 MySQL 重建）。
- **测试专用的适配层**：`MybatisPlusMocks` 依赖 MyBatis-Plus 的私有字段名 `entityClass`，
  升级 MyBatis-Plus 时可能又要跟着改。
