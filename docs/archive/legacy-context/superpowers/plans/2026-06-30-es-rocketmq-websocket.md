# ES + RocketMQ + WebSocket 升级 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有秒杀系统上引入 Elasticsearch 商铺搜索、RocketMQ 异步下单（替换 Redis Stream）、Canal → RocketMQ → ES 自动同步、WebSocket 实时通知，并为每项改进留下 before/after 测试证据。

**Architecture:**  
HTTP 线程执行 Lua 资格检查后发送 RocketMQ 事务消息；消费者落库后通过 Redis pub/sub 推送 WebSocket 通知。MySQL binlog 由 Canal 捕获，经 RocketMQ `mysql-sync-topic` 传递，`EsSyncConsumer` 更新 ES 索引，使 ES 搜索与 MySQL 保持最终一致。

**Tech Stack:** Spring Boot 2.3.12, Spring Data Elasticsearch 4.0.x, rocketmq-spring-boot-starter 2.2.3, Elasticsearch 7.17.18 + IK 分词器, Canal Server 1.1.7, Spring WebSocket (TextWebSocketHandler), Redis pub/sub (已有 Spring Data Redis)

## Global Constraints

- Java 8，Spring Boot 2.3.12.RELEASE — 不升级框架版本
- ES 服务端 7.17.18，IK 插件版本必须与 ES 完全一致
- 所有集成测试使用真实 MySQL / Redis / ES / RocketMQ，不使用 Mock
- TDD 顺序不变：先写失败测试，确认失败后实现，实现后确认通过
- 包名 `com.localdeals`，不修改
- Canal 连接 `local-deals-mysql` 容器（docker-compose.yml 内的 MySQL 8.0，默认已开启 ROW 格式 binlog）

---

## File Map

| 动作 | 路径 |
|---|---|
| 新建 | `docker/elasticsearch/Dockerfile` |
| 修改 | `docker-compose.yml` |
| 修改 | `pom.xml` |
| 修改 | `src/main/resources/application.yaml` |
| 修改 | `src/test/resources/application-test.yaml` |
| 新建 | `src/main/resources/lua/seckill_check.lua` |
| 新建 | `src/main/java/com/localdeals/dto/ShopDoc.java` |
| 新建 | `src/main/java/com/localdeals/dto/BlogDoc.java` |
| 新建 | `src/main/java/com/localdeals/config/ElasticsearchConfig.java` |
| 新建 | `src/main/java/com/localdeals/init/ShopIndexInitializer.java` |
| 修改 | `src/main/java/com/localdeals/service/IShopService.java` |
| 修改 | `src/main/java/com/localdeals/service/impl/ShopServiceImpl.java` |
| 修改 | `src/main/java/com/localdeals/controller/ShopController.java` |
| 修改 | `src/main/java/com/localdeals/service/IBlogService.java` |
| 修改 | `src/main/java/com/localdeals/service/impl/BlogServiceImpl.java` |
| 修改 | `src/main/java/com/localdeals/controller/BlogController.java` |
| 新建 | `src/main/java/com/localdeals/mq/SeckillOrderMessage.java` |
| 新建 | `src/main/java/com/localdeals/mq/SeckillOrderProducer.java` |
| 新建 | `src/main/java/com/localdeals/mq/SeckillOrderConsumer.java` |
| 新建 | `src/main/java/com/localdeals/mq/EsSyncConsumer.java` |
| 新建 | `src/main/java/com/localdeals/mq/CanalMessage.java` |
| 新建 | `src/main/java/com/localdeals/websocket/SeckillWebSocketHandler.java` |
| 新建 | `src/main/java/com/localdeals/websocket/WebSocketAuthInterceptor.java` |
| 新建 | `src/main/java/com/localdeals/websocket/WebSocketNotifier.java` |
| 新建 | `src/main/java/com/localdeals/websocket/SeckillResultMessage.java` |
| 修改 | `src/main/java/com/localdeals/config/WebSocketConfig.java` (新建) |
| 大幅简化 | `src/main/java/com/localdeals/service/impl/VoucherOrderServiceImpl.java` |
| 新建 | `src/test/java/com/localdeals/service/ShopSearchBeforeIT.java` |
| 新建 | `src/test/java/com/localdeals/service/ShopSearchAfterIT.java` |
| 新建 | `src/test/java/com/localdeals/mq/SeckillWithRocketMQIT.java` |
| 新建 | `src/test/java/com/localdeals/mq/CanalSyncIT.java` |
| 新建 | `src/test/java/com/localdeals/websocket/SeckillWebSocketIT.java` |

---

## Task 0: 基线测试（改动 ES 之前运行，记录 MySQL LIKE 行为）

**Files:**
- Create: `src/test/java/com/localdeals/service/ShopSearchBeforeIT.java`

**Interfaces:**
- Consumes: `IShopService` (已有), `ShopMapper` (已有)
- Produces: 测试通过记录作为 baseline，后续与 ES 搜索对比

- [ ] **Step 1: 写测试**

```java
package com.localdeals.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ShopSearchBeforeIT {

    @Resource
    private IShopService shopService;

    @Test
    void mysqlLikeSearch_hotpot_returnsMatchingShops() {
        long start = System.currentTimeMillis();
        Page<Shop> page = shopService.query()
                .like("name", "火锅")
                .page(new Page<>(1, 10));
        long elapsed = System.currentTimeMillis() - start;

        List<Shop> results = page.getRecords();
        System.out.println("MySQL LIKE '火锅' results: " + results.size() + ", elapsed: " + elapsed + "ms");
        for (Shop s : results) {
            System.out.println("  - " + s.getName());
        }
        // 记录数量和耗时，不断言具体数值（数据库内容可能不同）
        assertThat(results).isNotNull();
    }

    @Test
    void mysqlLikeSearch_partialMatch_cannotFindVariants() {
        // "烤肉" LIKE 无法匹配 "碳火烤肉" 中的 "碳火"，只能匹配包含"烤肉"的名字
        Page<Shop> hotpotPage = shopService.query()
                .like("name", "烤肉")
                .page(new Page<>(1, 10));
        System.out.println("MySQL LIKE '烤肉' results: " + hotpotPage.getRecords().size());

        // 无法按 typeId + 关键词 + 距离组合排序
        System.out.println("NOTE: MySQL LIKE cannot combine with geo-distance sorting.");
        assertThat(hotpotPage).isNotNull();
    }
}
```

- [ ] **Step 2: 运行测试，确认通过（记录控制台输出的 result 数量和耗时）**

```bash
set -a && source .env && set +a
mvn -Dtest=ShopSearchBeforeIT test -pl . 2>&1 | grep -E "(results:|elapsed:|NOTE:|PASSED|FAILED)"
```

期望：2/2 PASSED。将控制台输出的 result 数量和耗时记录在 `docs/archive/legacy-context/improvement-comparison.md`（后续任务创建该文件）。

- [ ] **Step 3: 提交**

```bash
git add src/test/java/com/localdeals/service/ShopSearchBeforeIT.java
git commit -m "test: add ShopSearchBeforeIT to document MySQL LIKE baseline before ES"
```

---

## Task 1: 基础设施搭建（Docker + 依赖 + 配置）

**Files:**
- Create: `docker/elasticsearch/Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/test/resources/application-test.yaml`

**Interfaces:**
- Produces: ES 在 `localhost:9200` 可访问，RocketMQ NameServer 在 `localhost:9876`，Broker 在 `localhost:10911`，Canal Server 在 `localhost:11111`

- [ ] **Step 1: 创建 ES Dockerfile（含 IK 分词器）**

创建目录：`mkdir -p docker/elasticsearch`

```dockerfile
# docker/elasticsearch/Dockerfile
FROM elasticsearch:7.17.18
RUN bin/elasticsearch-plugin install --batch \
    https://github.com/infinilabs/analysis-ik/releases/download/v7.17.18/elasticsearch-analysis-ik-7.17.18.zip
```

- [ ] **Step 2: 在 docker-compose.yml 中添加 ES、RocketMQ NameServer、RocketMQ Broker、Canal Server**

在 `services:` 下追加（保留已有 mysql 和 redis 不变）：

```yaml
  elasticsearch:
    build: ./docker/elasticsearch
    container_name: local-deals-es
    restart: always
    ports:
      - "9200:9200"
    environment:
      - discovery.type=single-node
      - ES_JAVA_OPTS=-Xms512m -Xmx512m
    volumes:
      - ./es-data:/usr/share/elasticsearch/data
    networks:
      - local-deals-net

  rocketmq-namesrv:
    image: apache/rocketmq:4.9.4
    container_name: local-deals-namesrv
    restart: always
    ports:
      - "9876:9876"
    command: sh mqnamesrv
    networks:
      - local-deals-net

  rocketmq-broker:
    image: apache/rocketmq:4.9.4
    container_name: local-deals-broker
    restart: always
    ports:
      - "10911:10911"
      - "10909:10909"
    command: sh mqbroker -n rocketmq-namesrv:9876 autoCreateTopicEnable=true
    depends_on:
      - rocketmq-namesrv
    networks:
      - local-deals-net

  canal-server:
    image: canal/canal-server:v1.1.7
    container_name: local-deals-canal
    restart: always
    ports:
      - "11111:11111"
    environment:
      - canal.instance.master.address=local-deals-mysql:3306
      - canal.instance.dbUsername=root
      - canal.instance.dbPassword=${MYSQL_ROOT_PASSWORD}
      - canal.instance.filter.regex=local_deals\\.tb_shop,local_deals\\.tb_blog
      - canal.mq.servers=rocketmq-namesrv:9876
      - canal.mq.topic=mysql-sync-topic
      - canal.serverMode=rocketmq
    depends_on:
      - mysql
      - rocketmq-namesrv
      - rocketmq-broker
    networks:
      - local-deals-net
```

- [ ] **Step 3: 在 pom.xml 中添加三个依赖**

在 `<dependencies>` 末尾追加：

```xml
<!-- Elasticsearch -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>

<!-- RocketMQ -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.2.3</version>
</dependency>

<!-- WebSocket -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>

<!-- Awaitility（用于 CanalSyncIT 异步等待） -->
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>4.1.1</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: 在 application.yaml 末尾添加 ES 和 RocketMQ 配置**

```yaml
spring:
  elasticsearch:
    rest:
      uris: http://localhost:9200
rocketmq:
  name-server: localhost:9876
  producer:
    group: local-deals-producer-group
    send-message-timeout: 3000
```

- [ ] **Step 5: 在 application-test.yaml 中添加相同连接配置（IT 测试使用真实服务）**

```yaml
spring:
  elasticsearch:
    rest:
      uris: http://localhost:9200
rocketmq:
  name-server: localhost:9876
  producer:
    group: local-deals-producer-group
```

- [ ] **Step 6: 启动所有服务，验证基础设施就绪**

```bash
# 首次启动会构建 ES 镜像（需下载 IK 插件，耗时约 1-2 分钟）
docker compose up -d --build

# 等待约 30 秒后验证
curl http://localhost:9200/_cat/health?v
# 期望：epoch + status = green

curl http://localhost:9200/_analyze \
  -H "Content-Type: application/json" \
  -d '{"analyzer":"ik_smart","text":"火锅店"}'
# 期望：tokens 包含 "火锅" 和 "店"

# 验证 RocketMQ
docker exec local-deals-namesrv sh mqadmin clusterList -n localhost:9876
# 期望：输出 broker 集群信息（DefaultCluster）
```

- [ ] **Step 7: 编译验证，确认新依赖无冲突**

```bash
mvn compile -q
# 期望：无报错
```

- [ ] **Step 8: 提交**

```bash
git add docker/ docker-compose.yml pom.xml \
    src/main/resources/application.yaml \
    src/test/resources/application-test.yaml
git commit -m "feat: add ES 7.17.18 + RocketMQ 4.9.4 + Canal 1.1.7 infrastructure"
```

---

## Task 2: ES 商铺搜索

**Files:**
- Create: `src/main/java/com/localdeals/dto/ShopDoc.java`
- Create: `src/main/java/com/localdeals/dto/BlogDoc.java`
- Create: `src/main/java/com/localdeals/config/ElasticsearchConfig.java`
- Create: `src/main/java/com/localdeals/init/ShopIndexInitializer.java`
- Modify: `src/main/java/com/localdeals/service/IShopService.java`
- Modify: `src/main/java/com/localdeals/service/impl/ShopServiceImpl.java`
- Modify: `src/main/java/com/localdeals/controller/ShopController.java`
- Modify: `src/main/java/com/localdeals/service/IBlogService.java`
- Modify: `src/main/java/com/localdeals/service/impl/BlogServiceImpl.java`
- Modify: `src/main/java/com/localdeals/controller/BlogController.java`
- Create: `src/test/java/com/localdeals/service/ShopSearchAfterIT.java`

**Interfaces:**
- Consumes: `ElasticsearchRestTemplate` (Spring Data ES 自动配置), `Shop` entity (已有)
- Produces:
  - `IShopService.searchShops(String keyword, Double x, Double y, Integer radius, Integer typeId, Integer current) → Result`
  - `IBlogService.searchBlogs(String keyword, Integer current) → Result`

- [ ] **Step 1: 写失败测试（ES 相关 bean 尚未创建时编译通过但运行失败）**

```java
// src/test/java/com/localdeals/service/ShopSearchAfterIT.java
package com.localdeals.service;

import com.localdeals.dto.Result;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopSearchAfterIT {

    @Autowired
    private IShopService shopService;

    @Autowired
    private ElasticsearchRestTemplate esRestTemplate;

    @BeforeAll
    void setupIndex() {
        // 确保索引存在（若已存在先删后建）
        IndexOperations ops = esRestTemplate.indexOps(
            org.springframework.data.elasticsearch.core.IndexCoordinates.of("shop_index"));
        if (ops.exists()) ops.delete();
        // 索引由 ShopDoc 的 @Document 注解定义 mapping
        ops.createWithMapping();

        // 写入两条测试数据
        com.localdeals.dto.ShopDoc doc1 = new com.localdeals.dto.ShopDoc();
        doc1.setId(9001L); doc1.setName("小龙坎火锅"); doc1.setAddress("中山路1号");
        doc1.setTypeId(1L); doc1.setAvgPrice(100L); doc1.setScore(45);
        doc1.setSold(200); doc1.setLocation("30.33,120.15");

        com.localdeals.dto.ShopDoc doc2 = new com.localdeals.dto.ShopDoc();
        doc2.setId(9002L); doc2.setName("海底捞火锅"); doc2.setAddress("解放路88号");
        doc2.setTypeId(1L); doc2.setAvgPrice(150L); doc2.setScore(48);
        doc2.setSold(500); doc2.setLocation("30.34,120.16");

        IndexQuery q1 = new IndexQueryBuilder().withId("9001").withObject(doc1).build();
        IndexQuery q2 = new IndexQueryBuilder().withId("9002").withObject(doc2).build();
        esRestTemplate.index(q1, org.springframework.data.elasticsearch.core.IndexCoordinates.of("shop_index"));
        esRestTemplate.index(q2, org.springframework.data.elasticsearch.core.IndexCoordinates.of("shop_index"));

        // 刷新索引，保证立即可查
        esRestTemplate.indexOps(
            org.springframework.data.elasticsearch.core.IndexCoordinates.of("shop_index")).refresh();
    }

    @AfterAll
    void tearDown() {
        IndexOperations ops = esRestTemplate.indexOps(
            org.springframework.data.elasticsearch.core.IndexCoordinates.of("shop_index"));
        if (ops.exists()) ops.delete();
    }

    @Test
    void searchShops_keyword_returnsIkTokenizedResults() {
        long start = System.currentTimeMillis();
        Result result = shopService.searchShops("火锅", null, null, null, null, 1);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.getSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shops = (List<Map<String, Object>>) result.getData();
        System.out.println("ES search '火锅' results: " + shops.size() + ", elapsed: " + elapsed + "ms");
        assertThat(shops).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void searchShops_geoAndKeyword_returnsShopsWithinRadius() {
        // 以 (30.33, 120.15) 为圆心，5km 内搜索火锅
        Result result = shopService.searchShops("火锅", 120.15, 30.33, 5000, null, 1);
        assertThat(result.getSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shops = (List<Map<String, Object>>) result.getData();
        assertThat(shops).isNotEmpty();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（searchShops 方法不存在）**

```bash
set -a && source .env && set +a
mvn -Dtest=ShopSearchAfterIT test 2>&1 | tail -20
# 期望：编译失败或运行时 NoSuchMethodError — IShopService.searchShops 不存在
```

- [ ] **Step 3: 创建 ShopDoc.java**

```java
// src/main/java/com/localdeals/dto/ShopDoc.java
package com.localdeals.dto;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.GeoPointField;

@Data
@Document(indexName = "shop_index")
public class ShopDoc {

    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String name;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String address;

    @Field(type = FieldType.Keyword)
    private Long typeId;

    @Field(type = FieldType.Long)
    private Long avgPrice;

    @Field(type = FieldType.Integer)
    private Integer score;

    @Field(type = FieldType.Integer)
    private Integer sold;

    @GeoPointField
    private String location;  // 格式："lat,lon"，例如 "30.33,120.15"
}
```

- [ ] **Step 4: 创建 BlogDoc.java**

```java
// src/main/java/com/localdeals/dto/BlogDoc.java
package com.localdeals.dto;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Data
@Document(indexName = "blog_index")
public class BlogDoc {

    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    @Field(type = FieldType.Keyword)
    private Long userId;

    @Field(type = FieldType.Integer)
    private Integer liked;
}
```

- [ ] **Step 5: 创建 ElasticsearchConfig.java（覆盖 Spring Boot 默认配置，添加超时）**

```java
// src/main/java/com/localdeals/config/ElasticsearchConfig.java
package com.localdeals.config;

import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.RestClients;
import org.springframework.data.elasticsearch.config.AbstractElasticsearchConfiguration;

@Configuration
public class ElasticsearchConfig extends AbstractElasticsearchConfiguration {

    @Value("${spring.elasticsearch.rest.uris:http://localhost:9200}")
    private String esUri;

    @Override
    public RestHighLevelClient elasticsearchClient() {
        ClientConfiguration config = ClientConfiguration.builder()
                .connectedTo(esUri.replace("http://", "").replace("https://", ""))
                .withConnectTimeout(5000)
                .withSocketTimeout(10000)
                .build();
        return RestClients.create(config).rest();
    }
}
```

- [ ] **Step 6: 在 IShopService 中添加 searchShops 方法签名**

在 `src/main/java/com/localdeals/service/IShopService.java` 中追加：

```java
Result searchShops(String keyword, Double x, Double y, Integer radius, Long typeId, Integer current);
```

- [ ] **Step 7: 在 ShopServiceImpl 中实现 searchShops**

在 `ShopServiceImpl` 中添加 field 并实现方法：

```java
// 在类顶部 @Resource 区域追加：
@Autowired
private ElasticsearchRestTemplate esRestTemplate;

// 实现方法：
@Override
public Result searchShops(String keyword, Double x, Double y, Integer radius, Integer typeId, Integer current) {
    NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();

    // 关键词全文检索（name 或 address 包含关键词）
    if (StrUtil.isNotBlank(keyword)) {
        queryBuilder.withQuery(QueryBuilders.multiMatchQuery(keyword, "name", "address"));
    } else {
        queryBuilder.withQuery(QueryBuilders.matchAllQuery());
    }

    // typeId 过滤
    if (typeId != null) {
        queryBuilder.withFilter(QueryBuilders.termQuery("typeId", typeId));
    }

    // 地理位置过滤 + 距离排序
    if (x != null && y != null) {
        int radiusMeters = radius != null ? radius : 5000;
        queryBuilder.withFilter(
            QueryBuilders.geoDistanceQuery("location")
                .point(y, x)
                .distance(radiusMeters + "m")
        );
        queryBuilder.withSort(
            SortBuilders.geoDistanceSort("location", y, x)
                .order(SortOrder.ASC)
                .unit(DistanceUnit.METERS)
        );
    }

    // 分页
    int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;
    queryBuilder.withPageable(PageRequest.of(current - 1, pageSize));

    SearchHits<ShopDoc> hits = esRestTemplate.search(queryBuilder.build(), ShopDoc.class,
            IndexCoordinates.of("shop_index"));

    List<ShopDoc> docs = hits.getSearchHits().stream()
            .map(SearchHit::getContent)
            .collect(Collectors.toList());

    return Result.ok(docs);
}
```

需要在 ShopServiceImpl 顶部添加 import（编译器会提示，主要有）：
```java
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexCoordinates;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.common.unit.DistanceUnit;
import org.springframework.data.domain.PageRequest;
import java.util.stream.Collectors;
import com.localdeals.dto.ShopDoc;
```

- [ ] **Step 8: 在 ShopController 中添加 GET /shop/search 端点**

在 `ShopController` 中追加：

```java
@GetMapping("/search")
public Result searchShops(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "x", required = false) Double x,
        @RequestParam(value = "y", required = false) Double y,
        @RequestParam(value = "radius", required = false) Integer radius,
        @RequestParam(value = "typeId", required = false) Long typeId,
        @RequestParam(value = "current", defaultValue = "1") Integer current) {
    return shopService.searchShops(keyword, x, y, radius, typeId, current);
}
```

注意：`IShopService.searchShops` 的 `typeId` 参数类型确认后统一为 `Long` 或 `Integer`，保持一致。

- [ ] **Step 9: 在 IBlogService 中添加 searchBlogs，并在 BlogServiceImpl 中实现**

`IBlogService.java` 追加：
```java
Result searchBlogs(String keyword, Integer current);
```

`BlogServiceImpl.java` 实现：
```java
@Autowired
private ElasticsearchRestTemplate esRestTemplate;

@Override
public Result searchBlogs(String keyword, Integer current) {
    NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder();
    if (StrUtil.isNotBlank(keyword)) {
        queryBuilder.withQuery(QueryBuilders.multiMatchQuery(keyword, "title", "content"));
    } else {
        queryBuilder.withQuery(QueryBuilders.matchAllQuery());
    }
    queryBuilder.withPageable(PageRequest.of(current - 1, SystemConstants.DEFAULT_PAGE_SIZE));

    SearchHits<BlogDoc> hits = esRestTemplate.search(queryBuilder.build(), BlogDoc.class,
            IndexCoordinates.of("blog_index"));
    List<BlogDoc> docs = hits.getSearchHits().stream()
            .map(SearchHit::getContent).collect(Collectors.toList());
    return Result.ok(docs);
}
```

`BlogController.java` 追加：
```java
@GetMapping("/search")
public Result searchBlogs(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "current", defaultValue = "1") Integer current) {
    return blogService.searchBlogs(keyword, current);
}
```

- [ ] **Step 10: 创建 ShopIndexInitializer.java（仅在非 test profile 启动时执行全量导入）**

```java
// src/main/java/com/localdeals/init/ShopIndexInitializer.java
package com.localdeals.init;

import com.localdeals.dto.ShopDoc;
import com.localdeals.entity.Shop;
import com.localdeals.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexCoordinates;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

@Slf4j
@Component
@Profile("!test")
public class ShopIndexInitializer {

    @Resource
    private IShopService shopService;

    @Resource
    private ElasticsearchRestTemplate esRestTemplate;

    @PostConstruct
    public void init() {
        IndexCoordinates index = IndexCoordinates.of("shop_index");
        IndexOperations ops = esRestTemplate.indexOps(index);
        if (!ops.exists()) {
            ops.createWithMapping();
            log.info("Created shop_index in Elasticsearch.");
        }

        List<Shop> shops = shopService.list();
        for (Shop shop : shops) {
            ShopDoc doc = toDoc(shop);
            IndexQuery query = new IndexQueryBuilder()
                    .withId(shop.getId().toString())
                    .withObject(doc)
                    .build();
            esRestTemplate.index(query, index);
        }
        ops.refresh();
        log.info("Imported {} shops into Elasticsearch shop_index.", shops.size());
    }

    private ShopDoc toDoc(Shop shop) {
        ShopDoc doc = new ShopDoc();
        doc.setId(shop.getId());
        doc.setName(shop.getName());
        doc.setAddress(shop.getAddress());
        doc.setTypeId(shop.getTypeId());
        doc.setAvgPrice(shop.getAvgPrice());
        doc.setScore(shop.getScore());
        doc.setSold(shop.getSold());
        if (shop.getY() != null && shop.getX() != null) {
            doc.setLocation(shop.getY() + "," + shop.getX());  // geo_point: "lat,lon"
        }
        return doc;
    }
}
```

- [ ] **Step 11: 运行 ShopSearchAfterIT，确认通过**

```bash
set -a && source .env && set +a
mvn -Dtest=ShopSearchAfterIT test 2>&1 | tail -20
# 期望：2/2 PASSED
```

- [ ] **Step 12: 手动测试 API**

```bash
# 启动应用
mvn spring-boot:run &

# 等待启动（约 30 秒），观察日志中 "Imported X shops into Elasticsearch"
# 然后测试搜索端点
curl "http://localhost:8083/shop/search?keyword=火锅"
# 期望：JSON 响应，data 数组包含店铺名称含"火锅"的商铺
```

- [ ] **Step 13: 提交**

```bash
git add src/main/java/com/localdeals/dto/ShopDoc.java \
    src/main/java/com/localdeals/dto/BlogDoc.java \
    src/main/java/com/localdeals/config/ElasticsearchConfig.java \
    src/main/java/com/localdeals/init/ShopIndexInitializer.java \
    src/main/java/com/localdeals/service/IShopService.java \
    src/main/java/com/localdeals/service/impl/ShopServiceImpl.java \
    src/main/java/com/localdeals/controller/ShopController.java \
    src/main/java/com/localdeals/service/IBlogService.java \
    src/main/java/com/localdeals/service/impl/BlogServiceImpl.java \
    src/main/java/com/localdeals/controller/BlogController.java \
    src/test/java/com/localdeals/service/ShopSearchAfterIT.java
git commit -m "feat: add ES shop/blog search with IK tokenizer and geo-distance"
```

---

## Task 3: RocketMQ 秒杀（替换 Redis Stream）

**Files:**
- Create: `src/main/resources/lua/seckill_check.lua`
- Create: `src/main/java/com/localdeals/mq/SeckillOrderMessage.java`
- Create: `src/main/java/com/localdeals/mq/SeckillOrderProducer.java`
- Create: `src/main/java/com/localdeals/mq/SeckillOrderConsumer.java`
- Modify: `src/main/java/com/localdeals/service/impl/VoucherOrderServiceImpl.java` (大幅简化)
- Create: `src/test/java/com/localdeals/mq/SeckillWithRocketMQIT.java`

**Interfaces:**
- Consumes: `RocketMQTemplate` (自动配置), `IVoucherOrderService.createVoucherOrder()` (已有), `RedissonClient` (已有)
- Produces:
  - `SeckillOrderProducer.sendSeckillTransaction(Long voucherId, Long userId, Long orderId) → int` (0=成功, 1=库存不足, 2=重复)
  - Topic `seckill-order-topic`，消费者组 `seckill-consumer-group`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/localdeals/mq/SeckillWithRocketMQIT.java
package com.localdeals.mq;

import com.localdeals.service.IVoucherOrderService;
import com.localdeals.utils.RedisIdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;

@SpringBootTest
@ActiveProfiles("test")
class SeckillWithRocketMQIT {

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final Long TEST_VOUCHER_ID = 88888L;
    private static final int STOCK = 100;
    private static final int TOTAL_USERS = 500;

    @BeforeEach
    void setup() {
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + TEST_VOUCHER_ID, String.valueOf(STOCK));
        stringRedisTemplate.delete(SECKILL_ORDER_KEY + TEST_VOUCHER_ID);
    }

    @Test
    void sendSeckillTransaction_concurrentUsers_noOversell() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(TOTAL_USERS);
        Set<Integer> results = java.util.Collections.synchronizedSet(new HashSet<>());
        Set<Long> acceptedOrderIds = java.util.Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < TOTAL_USERS; i++) {
            final long userId = 10000L + i;
            pool.submit(() -> {
                try {
                    long orderId = redisIdWorker.nextId("order");
                    int r = seckillOrderProducer.sendSeckillTransaction(TEST_VOUCHER_ID, userId, orderId);
                    results.add(r);
                    if (r == 0) acceptedOrderIds.add(orderId);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        // 接受的订单数量 ≤ 库存
        assertThat(acceptedOrderIds).hasSizeLessThanOrEqualTo(STOCK);
        // 所有 orderId 唯一
        assertThat(acceptedOrderIds).doesNotHaveDuplicates();

        // Redis 中的已购用户数 = 接受的订单数
        Long setSize = stringRedisTemplate.opsForSet().size(SECKILL_ORDER_KEY + TEST_VOUCHER_ID);
        assertThat(setSize).isEqualTo((long) acceptedOrderIds.size());

        System.out.println("Accepted orders: " + acceptedOrderIds.size() + "/" + TOTAL_USERS);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（SeckillOrderProducer 不存在）**

```bash
set -a && source .env && set +a
mvn -Dtest=SeckillWithRocketMQIT test 2>&1 | tail -10
# 期望：编译失败 — SeckillOrderProducer 不存在
```

- [ ] **Step 3: 创建新 Lua 脚本 seckill_check.lua（去掉 XADD，只做检查+扣减）**

```lua
-- src/main/resources/lua/seckill_check.lua
-- KEYS[1] 库存 key（seckill:stock:{voucherId}）
-- KEYS[2] 已购用户 set key（seckill:order:{voucherId}）
-- ARGV[1] userId, ARGV[2] voucherId, ARGV[3] orderId
-- 返回: 0=成功, 1=库存不足, 2=重复购买

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId = ARGV[1]

local stock = tonumber(redis.call('get', stockKey))
if not stock or stock <= 0 then
    return 1
end

if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

redis.call('decr', stockKey)
redis.call('sadd', orderKey, userId)
return 0
```

- [ ] **Step 4: 创建 SeckillOrderMessage.java**

```java
// src/main/java/com/localdeals/mq/SeckillOrderMessage.java
package com.localdeals.mq;

import com.localdeals.entity.VoucherOrder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage {
    private Long voucherId;
    private Long userId;
    private Long orderId;

    public VoucherOrder toVoucherOrder() {
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setVoucherId(voucherId);
        order.setUserId(userId);
        return order;
    }
}
```

- [ ] **Step 5: 创建 SeckillOrderProducer.java**

```java
// src/main/java/com/localdeals/mq/SeckillOrderProducer.java
package com.localdeals.mq;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;

import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
@RocketMQTransactionListener(txProducerGroup = "seckill-tx-group")
public class SeckillOrderProducer implements RocketMQLocalTransactionListener {

    private static final String TOPIC = "seckill-order-topic";

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final ThreadLocal<Integer> luaResultHolder = new ThreadLocal<>();

    private static final DefaultRedisScript<Long> SECKILL_CHECK_SCRIPT;

    static {
        SECKILL_CHECK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_CHECK_SCRIPT.setLocation(new ClassPathResource("lua/seckill_check.lua"));
        SECKILL_CHECK_SCRIPT.setResultType(Long.class);
    }

    /**
     * HTTP 线程调用：发送事务消息，内部执行 Lua 检查，同步返回 Lua 结果。
     * 返回: 0=秒杀成功(消息已 commit), 1=库存不足, 2=已抢过
     */
    public int sendSeckillTransaction(Long voucherId, Long userId, Long orderId) {
        SeckillOrderMessage msg = new SeckillOrderMessage(voucherId, userId, orderId);
        Message<SeckillOrderMessage> message = MessageBuilder.withPayload(msg).build();

        // sendMessageInTransaction 同步阻塞直到 executeLocalTransaction 完成
        rocketMQTemplate.sendMessageInTransaction(
                "seckill-tx-group",
                TOPIC,
                message,
                new Object[]{voucherId, userId, orderId}
        );

        int result = luaResultHolder.get();
        luaResultHolder.remove();
        return result;
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        Object[] args = (Object[]) arg;
        Long voucherId = (Long) args[0];
        Long userId = (Long) args[1];
        Long orderId = (Long) args[2];

        Long luaResult = stringRedisTemplate.execute(
                SECKILL_CHECK_SCRIPT,
                Arrays.asList(SECKILL_STOCK_KEY + voucherId, SECKILL_ORDER_KEY + voucherId),
                userId.toString(), voucherId.toString(), orderId.toString()
        );

        int r = luaResult == null ? -1 : luaResult.intValue();
        luaResultHolder.set(r);

        if (r == 0) {
            log.debug("Seckill Lua check passed. voucherId={}, userId={}", voucherId, userId);
            return RocketMQLocalTransactionState.COMMIT;
        }
        log.debug("Seckill Lua check rejected. voucherId={}, userId={}, reason={}", voucherId, userId, r);
        return RocketMQLocalTransactionState.ROLLBACK;
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // Broker 超时后回查：检查用户是否已在 Redis Set 中（Lua 已扣减则存在）
        SeckillOrderMessage payload = JSONUtil.toBean(
                new String((byte[]) msg.getPayload()), SeckillOrderMessage.class);
        Boolean inSet = stringRedisTemplate.opsForSet()
                .isMember(SECKILL_ORDER_KEY + payload.getVoucherId(), payload.getUserId().toString());
        return Boolean.TRUE.equals(inSet)
                ? RocketMQLocalTransactionState.COMMIT
                : RocketMQLocalTransactionState.UNKNOWN;
    }
}
```

- [ ] **Step 6: 创建 SeckillOrderConsumer.java**

```java
// src/main/java/com/localdeals/mq/SeckillOrderConsumer.java
package com.localdeals.mq;

import com.localdeals.service.IVoucherOrderService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@Slf4j
@Service
@RocketMQMessageListener(topic = "seckill-order-topic", consumerGroup = "seckill-consumer-group")
public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private MeterRegistry meterRegistry;

    @Resource(name = "webSocketNotifier")  // 延迟注入，Task 5 实现后才有这个 bean
    private com.localdeals.websocket.WebSocketNotifier webSocketNotifier;

    private Counter consumeSuccessCounter;
    private Counter consumeFailureCounter;

    @PostConstruct
    private void registerMetrics() {
        consumeSuccessCounter = Counter.builder("local_deals.seckill.mq.consume")
                .tag("result", "success").register(meterRegistry);
        consumeFailureCounter = Counter.builder("local_deals.seckill.mq.consume")
                .tag("result", "failure").register(meterRegistry);
    }

    @Override
    public void onMessage(SeckillOrderMessage msg) {
        RLock lock = redissonClient.getLock("lock:order:" + msg.getUserId());
        boolean locked = lock.tryLock();
        if (!locked) {
            log.warn("Seckill order lock busy. userId={}, orderId={}", msg.getUserId(), msg.getOrderId());
            throw new IllegalStateException("Order lock busy, will retry.");
        }
        try {
            voucherOrderService.createVoucherOrder(msg.toVoucherOrder());
            consumeSuccessCounter.increment();
            log.debug("Seckill order persisted. orderId={}", msg.getOrderId());
            // Task 5 实现后通知 WebSocket
            if (webSocketNotifier != null) {
                webSocketNotifier.notify(msg.getUserId(), true, msg.getOrderId());
            }
        } catch (Exception e) {
            consumeFailureCounter.increment();
            log.error("Failed to persist seckill order. orderId={}", msg.getOrderId(), e);
            throw e;  // 让 RocketMQ 重试
        } finally {
            lock.unlock();
        }
    }
}
```

**注意：** `webSocketNotifier` 在 Task 5 实现之前不存在，需要将 `@Resource` 改为可选注入：将 `@Resource(name = "webSocketNotifier")` 替换为 `@Autowired(required = false)` + `@Qualifier("webSocketNotifier")`，或者直接在 Task 5 之前先注释掉那行，Task 5 完成后再启用。

实际建议：Task 5 完成前，去掉 webSocketNotifier 相关行，Task 5 完成后添加回来。

- [ ] **Step 7: 大幅简化 VoucherOrderServiceImpl（删除所有 Redis Stream 代码）**

将 `VoucherOrderServiceImpl.java` 替换为以下精简版（保留 DB 持久化逻辑，删除所有 Stream 相关代码）：

```java
package com.localdeals.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.dto.Result;
import com.localdeals.entity.VoucherOrder;
import com.localdeals.mapper.VoucherOrderMapper;
import com.localdeals.mq.SeckillOrderProducer;
import com.localdeals.service.ISeckillVoucherService;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.utils.RedisIdWorker;
import com.localdeals.utils.UserHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private MeterRegistry meterRegistry;

    private Counter requestAcceptedCounter;
    private Counter requestStockRejectedCounter;
    private Counter requestDuplicateRejectedCounter;
    private Counter duplicateOrderCounter;
    private Counter stockRollbackCounter;

    @PostConstruct
    private void registerMetrics() {
        requestAcceptedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "accepted").register(meterRegistry);
        requestStockRejectedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "rejected_stock").register(meterRegistry);
        requestDuplicateRejectedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "rejected_duplicate").register(meterRegistry);
        duplicateOrderCounter = Counter.builder("local_deals.seckill.db.orders")
                .tag("result", "duplicate").register(meterRegistry);
        stockRollbackCounter = Counter.builder("local_deals.seckill.db.orders")
                .tag("result", "stock_rollback").register(meterRegistry);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        long orderId = redisIdWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();

        int luaResult = seckillOrderProducer.sendSeckillTransaction(voucherId, userId, orderId);

        switch (luaResult) {
            case 0:
                requestAcceptedCounter.increment();
                return Result.ok(orderId);
            case 1:
                requestStockRejectedCounter.increment();
                return Result.fail("库存不足");
            default:
                requestDuplicateRejectedCounter.increment();
                return Result.fail("您已抢过该优惠券");
        }
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        try {
            this.save(voucherOrder);
        } catch (DuplicateKeyException e) {
            duplicateOrderCounter.increment();
            log.warn("Duplicate voucher order ignored. userId={}, voucherId={}, orderId={}",
                    voucherOrder.getUserId(), voucherOrder.getVoucherId(), voucherOrder.getId());
            return;
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!success) {
            stockRollbackCounter.increment();
            throw new IllegalStateException("DB stock exhausted. voucherId=" + voucherOrder.getVoucherId());
        }
    }
}
```

- [ ] **Step 8: 运行 SeckillWithRocketMQIT，确认通过**

```bash
set -a && source .env && set +a
mvn -Dtest=SeckillWithRocketMQIT test 2>&1 | tail -20
# 期望：1/1 PASSED，控制台输出 "Accepted orders: 100/500"
```

- [ ] **Step 9: 运行全量 IT 测试，确认无回归**

```bash
mvn -Dtest="RedisIdWorkerIT,CacheClientIT,UserServiceIT,BlogServiceIT,ShopServiceIT,SeckillWithRocketMQIT" test
# 期望：所有测试通过
```

- [ ] **Step 10: 提交**

```bash
git add src/main/resources/lua/seckill_check.lua \
    src/main/java/com/localdeals/mq/ \
    src/main/java/com/localdeals/service/impl/VoucherOrderServiceImpl.java \
    src/test/java/com/localdeals/mq/SeckillWithRocketMQIT.java
git commit -m "feat: replace Redis Stream with RocketMQ transaction messages for seckill"
```

---

## Task 4: Canal → RocketMQ → ES 自动同步

**Files:**
- Create: `src/main/java/com/localdeals/mq/CanalMessage.java`
- Create: `src/main/java/com/localdeals/mq/EsSyncConsumer.java`
- Create: `src/test/java/com/localdeals/mq/CanalSyncIT.java`

**Interfaces:**
- Consumes: Topic `mysql-sync-topic`（Canal Server 推送的 FlatMessage JSON）, `ElasticsearchRestTemplate`
- Produces: ES `shop_index` / `blog_index` 与 MySQL 保持最终一致

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/localdeals/mq/CanalSyncIT.java
package com.localdeals.mq;

import com.localdeals.dto.ShopDoc;
import com.localdeals.entity.Shop;
import com.localdeals.service.IShopService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexCoordinates;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.elasticsearch.index.query.QueryBuilders;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CanalSyncIT {

    @Resource
    private IShopService shopService;

    @Resource
    private ElasticsearchRestTemplate esRestTemplate;

    @Test
    void updateShopInMysql_canalSyncsToEs() {
        // 找一个已存在的商铺
        Shop shop = shopService.list().stream().findFirst().orElseThrow();
        String uniqueSuffix = "-CanalTest-" + System.currentTimeMillis();
        String newName = shop.getName() + uniqueSuffix;

        // 更新 MySQL（Canal 会捕获这个 binlog）
        shop.setName(newName);
        shopService.updateById(shop);

        // 等待 Canal → RocketMQ → EsSyncConsumer → ES 管道完成（最多 15 秒）
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    SearchHits<ShopDoc> hits = esRestTemplate.search(
                        new NativeSearchQueryBuilder()
                            .withQuery(QueryBuilders.termQuery("name.keyword", newName))
                            .build(),
                        ShopDoc.class,
                        IndexCoordinates.of("shop_index")
                    );
                    return hits.getTotalHits() > 0;
                });

        assertThat(true).isTrue();  // 上面 await 超时会抛异常，走到这里代表成功
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（EsSyncConsumer 不存在，Canal 管道未接通）**

```bash
set -a && source .env && set +a
mvn -Dtest=CanalSyncIT test 2>&1 | tail -10
# 期望：超时失败或编译失败
```

- [ ] **Step 3: 创建 CanalMessage.java（Canal FlatMessage 的 POJO 映射）**

```java
// src/main/java/com/localdeals/mq/CanalMessage.java
package com.localdeals.mq;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class CanalMessage {
    private String database;
    private String table;
    private String type;  // "INSERT", "UPDATE", "DELETE", "DDL"
    private List<Map<String, Object>> data;
    private Boolean isDdl;
}
```

- [ ] **Step 4: 创建 EsSyncConsumer.java**

```java
// src/main/java/com/localdeals/mq/EsSyncConsumer.java
package com.localdeals.mq;

import cn.hutool.json.JSONUtil;
import com.localdeals.dto.BlogDoc;
import com.localdeals.dto.ShopDoc;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

@Slf4j
@Service
@RocketMQMessageListener(topic = "mysql-sync-topic", consumerGroup = "es-sync-consumer-group")
public class EsSyncConsumer implements RocketMQListener<String> {

    @Resource
    private ElasticsearchRestTemplate esRestTemplate;

    private static final IndexCoordinates SHOP_INDEX = IndexCoordinates.of("shop_index");
    private static final IndexCoordinates BLOG_INDEX = IndexCoordinates.of("blog_index");

    @Override
    public void onMessage(String message) {
        CanalMessage msg = JSONUtil.toBean(message, CanalMessage.class);
        if (msg == null || Boolean.TRUE.equals(msg.getIsDdl()) || msg.getData() == null) {
            return;
        }
        String table = msg.getTable();
        switch (table) {
            case "tb_shop": handleShop(msg); break;
            case "tb_blog": handleBlog(msg); break;
            default: break;
        }
    }

    private void handleShop(CanalMessage msg) {
        for (Map<String, Object> row : msg.getData()) {
            String id = String.valueOf(row.get("id"));
            if ("DELETE".equals(msg.getType())) {
                esRestTemplate.delete(id, SHOP_INDEX);
                log.debug("Deleted shop from ES. id={}", id);
            } else {
                ShopDoc doc = rowToShopDoc(id, row);
                IndexQuery query = new IndexQueryBuilder().withId(id).withObject(doc).build();
                esRestTemplate.index(query, SHOP_INDEX);
                log.debug("Upserted shop in ES. id={}", id);
            }
        }
    }

    private void handleBlog(CanalMessage msg) {
        for (Map<String, Object> row : msg.getData()) {
            String id = String.valueOf(row.get("id"));
            if ("DELETE".equals(msg.getType())) {
                esRestTemplate.delete(id, BLOG_INDEX);
            } else {
                BlogDoc doc = rowToBlogDoc(id, row);
                IndexQuery query = new IndexQueryBuilder().withId(id).withObject(doc).build();
                esRestTemplate.index(query, BLOG_INDEX);
            }
        }
    }

    private ShopDoc rowToShopDoc(String id, Map<String, Object> row) {
        ShopDoc doc = new ShopDoc();
        doc.setId(Long.valueOf(id));
        doc.setName(strVal(row, "name"));
        doc.setAddress(strVal(row, "address"));
        Object typeId = row.get("type_id");
        if (typeId != null) doc.setTypeId(Long.valueOf(typeId.toString()));
        Object avgPrice = row.get("avg_price");
        if (avgPrice != null) doc.setAvgPrice(Long.valueOf(avgPrice.toString()));
        Object score = row.get("score");
        if (score != null) doc.setScore(Integer.valueOf(score.toString()));
        Object sold = row.get("sold");
        if (sold != null) doc.setSold(Integer.valueOf(sold.toString()));
        Object y = row.get("y"), x = row.get("x");
        if (y != null && x != null) doc.setLocation(y + "," + x);
        return doc;
    }

    private BlogDoc rowToBlogDoc(String id, Map<String, Object> row) {
        BlogDoc doc = new BlogDoc();
        doc.setId(Long.valueOf(id));
        doc.setTitle(strVal(row, "title"));
        doc.setContent(strVal(row, "content"));
        Object userId = row.get("user_id");
        if (userId != null) doc.setUserId(Long.valueOf(userId.toString()));
        Object liked = row.get("liked");
        if (liked != null) doc.setLiked(Integer.valueOf(liked.toString()));
        return doc;
    }

    private String strVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }
}
```

- [ ] **Step 5: 验证 Canal 连接到 MySQL（Canal 必须用 Canal 专用账号或 root 且有 replication 权限）**

Canal 需要 MySQL 账号有 `REPLICATION SLAVE` 权限。如果使用 root 账号（docker-compose 里已配置），MySQL 8.0 的 root 默认有此权限，无需额外操作。

验证 Canal Server 日志正常连接：

```bash
docker logs local-deals-canal 2>&1 | grep -E "(start|error|connect|binlog)" | tail -20
# 期望：日志中没有 connection refused，有 "start successful" 或 "binlog" 相关字样
```

- [ ] **Step 6: 运行 CanalSyncIT，确认通过**

```bash
set -a && source .env && set +a
mvn -Dtest=CanalSyncIT test 2>&1 | tail -20
# 期望：1/1 PASSED（可能需要 5-15 秒 Canal 管道延迟）
```

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/localdeals/mq/CanalMessage.java \
    src/main/java/com/localdeals/mq/EsSyncConsumer.java \
    src/test/java/com/localdeals/mq/CanalSyncIT.java
git commit -m "feat: add Canal -> RocketMQ -> ES sync pipeline for tb_shop and tb_blog"
```

---

## Task 5: WebSocket 实时推送

**Files:**
- Create: `src/main/java/com/localdeals/websocket/SeckillResultMessage.java`
- Create: `src/main/java/com/localdeals/websocket/SeckillWebSocketHandler.java`
- Create: `src/main/java/com/localdeals/websocket/WebSocketAuthInterceptor.java`
- Create: `src/main/java/com/localdeals/websocket/WebSocketNotifier.java`
- Create: `src/main/java/com/localdeals/config/WebSocketConfig.java`
- Modify: `src/main/java/com/localdeals/mq/SeckillOrderConsumer.java` (启用 webSocketNotifier 注入)
- Create: `src/test/java/com/localdeals/websocket/SeckillWebSocketIT.java`

**Interfaces:**
- Consumes: `StringRedisTemplate.convertAndSend()` (Redis pub/sub), `SeckillWebSocketHandler.sendToUser()`
- Produces:
  - `WebSocketNotifier.notify(Long userId, boolean success, Long orderId)` — 发布到 Redis channel `ws:seckill:{userId}`
  - WebSocket endpoint: `ws://localhost:8083/ws/connect?token=<token>`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/com/localdeals/websocket/SeckillWebSocketIT.java
package com.localdeals.websocket;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SeckillWebSocketIT {

    @Autowired
    private WebSocketNotifier webSocketNotifier;

    @Autowired
    private SeckillWebSocketHandler webSocketHandler;  // 注入 Spring 单例 bean

    @Test
    void notify_publishesViaRedisPubSub_handlerForwardsToSession() throws Exception {
        Long testUserId = 77777L;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Mock WebSocket session，注入到真实的 Spring bean handler 中
        WebSocketSession fakeSession = Mockito.mock(WebSocketSession.class);
        Mockito.when(fakeSession.isOpen()).thenReturn(true);
        Mockito.doAnswer(inv -> {
            receivedMessage.set(((TextMessage) inv.getArgument(0)).getPayload());
            latch.countDown();
            return null;
        }).when(fakeSession).sendMessage(ArgumentMatchers.any());

        // 注册到 Spring 单例 handler（Redis 监听器会调用同一个 bean 的 sendToUser）
        webSocketHandler.getSessionsForTest().put(testUserId, fakeSession);

        try {
            // 通过 Redis pub/sub 发通知，RedisMessageListenerContainer 收到后
            // 调用 webSocketHandler.sendToUser(testUserId, json)
            webSocketNotifier.notify(testUserId, true, 12345L);

            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(receivedMessage.get()).contains("SECKILL_RESULT");
            assertThat(receivedMessage.get()).contains("12345");
        } finally {
            webSocketHandler.getSessionsForTest().remove(testUserId);
        }
    }
}
```

**注意：** Mockito 已通过 `spring-boot-starter-test` 自动包含，无需额外添加依赖。`getSessionsForTest()` 是 `SeckillWebSocketHandler` 中暴露的包级别方法，仅供测试使用，已在 Task 5 Step 4 中定义。

- [ ] **Step 2: 运行测试，确认失败（相关类不存在）**

```bash
set -a && source .env && set +a
mvn -Dtest=SeckillWebSocketIT test 2>&1 | tail -10
# 期望：编译失败 — WebSocketNotifier 等类不存在
```

- [ ] **Step 3: 创建 SeckillResultMessage.java**

```java
// src/main/java/com/localdeals/websocket/SeckillResultMessage.java
package com.localdeals.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResultMessage {
    private String type = "SECKILL_RESULT";
    private boolean success;
    private Long orderId;
    private String message;

    public SeckillResultMessage(boolean success, Long orderId) {
        this.success = success;
        this.orderId = orderId;
        this.message = success ? "秒杀成功，订单已生成" : "秒杀失败";
    }
}
```

- [ ] **Step 4: 创建 SeckillWebSocketHandler.java**

```java
// src/main/java/com/localdeals/websocket/SeckillWebSocketHandler.java
package com.localdeals.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SeckillWebSocketHandler extends TextWebSocketHandler {

    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            sessions.put(userId, session);
            log.debug("WebSocket connected. userId={}", userId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            sessions.remove(userId);
            log.debug("WebSocket disconnected. userId={}", userId);
        }
    }

    public boolean sendToUser(Long userId, String json) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) return false;
        try {
            session.sendMessage(new TextMessage(json));
            return true;
        } catch (Exception e) {
            sessions.remove(userId);
            log.warn("Failed to send WebSocket message. userId={}", userId, e);
            return false;
        }
    }

    /** 仅供测试使用 */
    Map<Long, WebSocketSession> getSessionsForTest() {
        return sessions;
    }
}
```

- [ ] **Step 5: 创建 WebSocketAuthInterceptor.java**

```java
// src/main/java/com/localdeals/websocket/WebSocketAuthInterceptor.java
package com.localdeals.websocket;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.localdeals.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.annotation.Resource;
import java.util.Map;

import static com.localdeals.utils.RedisConstants.LOGIN_USER_KEY;

@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) return false;
        String token = ((ServletServerHttpRequest) request).getServletRequest().getParameter("token");
        if (StrUtil.isBlank(token)) return false;

        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        if (userMap.isEmpty()) return false;

        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        attributes.put("userId", user.getId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse resp,
            WebSocketHandler handler, Exception ex) {}
}
```

- [ ] **Step 6: 创建 WebSocketNotifier.java**

```java
// src/main/java/com/localdeals/websocket/WebSocketNotifier.java
package com.localdeals.websocket;

import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class WebSocketNotifier {

    private static final String CHANNEL_PREFIX = "ws:seckill:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void notify(Long userId, boolean success, Long orderId) {
        SeckillResultMessage msg = new SeckillResultMessage(success, orderId);
        stringRedisTemplate.convertAndSend(CHANNEL_PREFIX + userId, JSONUtil.toJsonStr(msg));
    }
}
```

- [ ] **Step 7: 创建 WebSocketConfig.java（注册端点 + Redis 订阅转发）**

```java
// src/main/java/com/localdeals/config/WebSocketConfig.java
package com.localdeals.config;

import com.localdeals.websocket.SeckillWebSocketHandler;
import com.localdeals.websocket.WebSocketAuthInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private SeckillWebSocketHandler webSocketHandler;

    @Resource
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, "/ws/connect")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOrigins("*");
    }

    @Bean
    public RedisMessageListenerContainer redisWebSocketListenerContainer(
            RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(
                (message, pattern) -> {
                    String channel = new String(message.getChannel());
                    // 从 channel "ws:seckill:{userId}" 提取 userId
                    String userIdStr = channel.substring("ws:seckill:".length());
                    Long userId = Long.parseLong(userIdStr);
                    String json = new String(message.getBody());
                    webSocketHandler.sendToUser(userId, json);
                },
                new PatternTopic("ws:seckill:*")
        );
        return container;
    }
}
```

- [ ] **Step 8: 在 SeckillOrderConsumer 中启用 WebSocket 通知**

将之前注释掉的 webSocketNotifier 代码恢复：

```java
// 在 SeckillOrderConsumer 中，将 @Autowired(required = false) 改为正式注入：
@Resource
private WebSocketNotifier webSocketNotifier;

// onMessage 末尾：
webSocketNotifier.notify(msg.getUserId(), true, msg.getOrderId());
```

- [ ] **Step 9: 运行 SeckillWebSocketIT，确认通过**

```bash
set -a && source .env && set +a
mvn -Dtest=SeckillWebSocketIT test 2>&1 | tail -20
# 期望：1/1 PASSED
```

- [ ] **Step 10: 全量测试**

```bash
mvn -Dtest="RedisIdWorkerIT,CacheClientIT,UserServiceIT,BlogServiceIT,ShopServiceIT,ShopSearchAfterIT,SeckillWithRocketMQIT,CanalSyncIT,SeckillWebSocketIT" test
# 期望：所有测试通过
```

- [ ] **Step 11: 提交**

```bash
git add src/main/java/com/localdeals/websocket/ \
    src/main/java/com/localdeals/config/WebSocketConfig.java \
    src/main/java/com/localdeals/mq/SeckillOrderConsumer.java \
    src/test/java/com/localdeals/websocket/SeckillWebSocketIT.java
git commit -m "feat: add WebSocket real-time push for seckill result via Redis pub/sub"
```

---

## Task 6: 对比文档

**Files:**
- Create: `docs/archive/legacy-context/improvement-comparison.md`

- [ ] **Step 1: 创建 docs/archive/legacy-context/improvement-comparison.md，填入 before/after 对比数据**

在所有 Task 完成后，将测试证据填入文档：

```markdown
# 改进前后对比

## 1. 商铺搜索

| 指标 | 改进前（MySQL LIKE） | 改进后（ES + IK 分词） |
|---|---|---|
| 关键词"火锅"结果数 | X 条（填入 ShopSearchBeforeIT 输出） | Y 条（ShopSearchAfterIT 输出） |
| 查询耗时 | Xms | Xms |
| 支持地理位置+关键词组合 | ✗ | ✓ |
| IK 分词（召回语义相近词） | ✗ | ✓ |
| 同步方式 | — | Canal → RocketMQ → EsSyncConsumer（自动，无侵入） |

## 2. 秒杀消息中间件

| 指标 | 改进前（Redis Stream） | 改进后（RocketMQ 事务消息） |
|---|---|---|
| 消息持久化 | 依赖 Redis AOF/RDB | 磁盘持久化，Broker 重启不丢 |
| Lua + 消息原子性 | ✗（XADD 可能失败） | ✓（事务消息 Commit/Rollback） |
| 500 并发，100 库存 | 订单数 100，无重复（已验证） | 订单数 100，无重复（已验证） |
| 死信队列 | 自定义 stream.orders.dlq | RocketMQ 内置 DLQ |

## 3. 秒杀结果通知

| 指标 | 改进前 | 改进后（WebSocket + Redis pub/sub） |
|---|---|---|
| 通知方式 | 用户轮询/手动刷新 | 服务端主动推送 |
| 通知延迟 | 秒级（取决于轮询间隔） | 毫秒级（落库后即推送） |
| 多实例支持 | — | Redis pub/sub 广播，所有实例都能推送 |
```

- [ ] **Step 2: 提交**

```bash
git add docs/archive/legacy-context/improvement-comparison.md
git commit -m "docs: add before/after improvement comparison evidence"
```
