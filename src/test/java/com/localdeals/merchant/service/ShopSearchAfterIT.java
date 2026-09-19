package com.localdeals.merchant.service;

import com.localdeals.platform.dto.Result;
import com.localdeals.merchant.dto.ShopDoc;
import com.localdeals.merchant.entity.Shop;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopSearchAfterIT {

    @Autowired
    private IShopService shopService;

    @Autowired
    private ElasticsearchOperations esRestTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setupIndex() {
        // 搜索只从 ES 取排好序的 id，再回 MySQL 查整行，所以两边都要有这两家店
        deleteFixtureShops();
        insertFixtureShop(9001L, "小龙坎火锅", "中山路1号", 120.15, 30.33);
        insertFixtureShop(9002L, "海底捞火锅", "解放路88号", 120.16, 30.34);

        // 确保索引存在（若已存在先删后建）；使用 class-bound IndexOperations 才能基于 @Document/@Field 注解创建 mapping
        IndexOperations ops = esRestTemplate.indexOps(ShopDoc.class);
        if (ops.exists()) ops.delete();
        // 索引由 ShopDoc 的 @Document 注解定义 mapping
        ops.create();
        ops.putMapping(ops.createMapping());

        // 写入两条测试数据
        com.localdeals.merchant.dto.ShopDoc doc1 = new com.localdeals.merchant.dto.ShopDoc();
        doc1.setId(9001L); doc1.setName("小龙坎火锅"); doc1.setAddress("中山路1号");
        doc1.setTypeId(1L); doc1.setAvgPrice(100L); doc1.setScore(45);
        doc1.setSold(200); doc1.setLocation("30.33,120.15");

        com.localdeals.merchant.dto.ShopDoc doc2 = new com.localdeals.merchant.dto.ShopDoc();
        doc2.setId(9002L); doc2.setName("海底捞火锅"); doc2.setAddress("解放路88号");
        doc2.setTypeId(1L); doc2.setAvgPrice(150L); doc2.setScore(48);
        doc2.setSold(500); doc2.setLocation("30.34,120.16");

        IndexQuery q1 = new IndexQueryBuilder().withId("9001").withObject(doc1).build();
        IndexQuery q2 = new IndexQueryBuilder().withId("9002").withObject(doc2).build();
        esRestTemplate.index(q1, org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.of("shop_index"));
        esRestTemplate.index(q2, org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.of("shop_index"));

        // 刷新索引，保证立即可查
        esRestTemplate.indexOps(
            org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.of("shop_index")).refresh();
    }

    @AfterAll
    void tearDown() {
        IndexOperations ops = esRestTemplate.indexOps(ShopDoc.class);
        if (ops.exists()) ops.delete();
        deleteFixtureShops();
    }

    private void insertFixtureShop(long id, String name, String address, double x, double y) {
        jdbcTemplate.update("INSERT INTO tb_shop (id, merchant_id, name, type_id, images, address, x, y, "
                        + "sold, comments, score) SELECT ?, id, ?, 1, '', ?, ?, ?, 0, 0, 40 "
                        + "FROM tb_merchant WHERE code = 'LEGACY_UNASSIGNED'",
                id, name, address, x, y);
    }

    private void deleteFixtureShops() {
        jdbcTemplate.update("DELETE FROM tb_shop WHERE id IN (9001, 9002)");
    }

    @Test
    void searchShops_keyword_returnsIkTokenizedResults() {
        long start = System.currentTimeMillis();
        Result result = shopService.searchShops("火锅", null, null, null, null, 1);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.getSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Shop> shops = (List<Shop>) result.getData();
        System.out.println("ES search '火锅' results: " + shops.size() + ", elapsed: " + elapsed + "ms");
        assertThat(shops).extracting(Shop::getId).contains(9001L, 9002L);
    }

    @Test
    void searchShops_geoAndKeyword_returnsShopsWithinRadius() {
        // 以 (30.33, 120.15) 为圆心，5km 内搜索火锅
        Result result = shopService.searchShops("火锅", 120.15, 30.33, 5000, null, 1);
        assertThat(result.getSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<Shop> shops = (List<Shop>) result.getData();
        // 9002 距圆心约 1.4 km，同在半径内；按距离升序
        assertThat(shops).extracting(Shop::getId).containsExactly(9001L, 9002L);
    }
}
