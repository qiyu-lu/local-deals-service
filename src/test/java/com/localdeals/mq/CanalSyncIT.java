package com.localdeals.mq;

import com.localdeals.dto.ShopDoc;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Canal -> RocketMQ -> {@link EsSyncConsumer} -> Elasticsearch pipeline: a row
 * change made directly against MySQL (bypassing the application layer, simulating any writer)
 * must be picked up by Canal's binlog watcher, published as a FlatMessage to RocketMQ topic
 * {@code mysql-sync-topic}, consumed by {@link EsSyncConsumer}, and reflected in {@code shop_index}.
 */
@SpringBootTest
@ActiveProfiles("test")
class CanalSyncIT {

    private static final long TEST_SHOP_ID = 999901L;
    private static final IndexCoordinates SHOP_INDEX = IndexCoordinates.of("shop_index");

    @Resource
    private DataSource dataSource;

    @Resource
    private ElasticsearchRestTemplate esTemplate;

    @BeforeEach
    void setup() throws Exception {
        // The shop_index initializer is disabled under the "test" profile, so the index
        // must exist before Canal's sync consumer can index into it.
        IndexOperations ops = esTemplate.indexOps(ShopDoc.class);
        if (!ops.exists()) {
            ops.create();
            ops.putMapping(ops.createMapping());
        }
        cleanupDbRow();
        cleanupEsDoc();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_shop (id, name, type_id, images, address, x, y, avg_price, sold, comments, score) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, TEST_SHOP_ID);
            ps.setString(2, "CanalTestShop");
            ps.setLong(3, 1L);
            ps.setString(4, "test.jpg");
            ps.setString(5, "Test Address");
            ps.setDouble(6, 120.15);
            ps.setDouble(7, 30.33);
            ps.setLong(8, 100L);
            ps.setInt(9, 0);
            ps.setInt(10, 0);
            ps.setInt(11, 40);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        cleanupDbRow();
        cleanupEsDoc();
    }

    private void cleanupDbRow() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM tb_shop WHERE id = " + TEST_SHOP_ID);
        }
    }

    private void cleanupEsDoc() {
        try {
            esTemplate.delete(String.valueOf(TEST_SHOP_ID), SHOP_INDEX);
        } catch (Exception ignored) {
            // Document may not exist yet; nothing to clean up.
        }
    }

    @Test
    void updateShopInMysql_canalSyncsToEs() throws Exception {
        String newName = "CanalTestShop-Updated-" + System.currentTimeMillis();

        // Wait for the INSERT to propagate through Canal -> RocketMQ -> EsSyncConsumer first,
        // so the document exists in ES before we update it.
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> findShopDoc() != null);

        // Update MySQL directly; Canal captures this via binlog.
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE tb_shop SET name = '" + newName + "' WHERE id = " + TEST_SHOP_ID);
        }

        // Wait for Canal -> RocketMQ -> EsSyncConsumer -> ES pipeline to apply the update.
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    ShopDoc doc = findShopDoc();
                    return doc != null && newName.equals(doc.getName());
                });

        ShopDoc synced = findShopDoc();
        assertThat(synced).isNotNull();
        assertThat(synced.getName()).isEqualTo(newName);
        assertThat(synced.getLocation()).isEqualTo("30.33,120.15");
    }

    @Test
    void deleteShopInMysql_canalSyncsDeleteToEs() throws Exception {
        // Wait for the INSERT to propagate first.
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> findShopDoc() != null);

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM tb_shop WHERE id = " + TEST_SHOP_ID);
        }

        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> findShopDoc() == null);

        assertThat(findShopDoc()).isNull();
    }

    private ShopDoc findShopDoc() {
        SearchHits<ShopDoc> hits = esTemplate.search(
                new NativeSearchQueryBuilder()
                        .withQuery(QueryBuilders.termQuery("_id", String.valueOf(TEST_SHOP_ID)))
                        .build(),
                ShopDoc.class,
                SHOP_INDEX
        );
        if (hits.getTotalHits() == 0) {
            return null;
        }
        SearchHit<ShopDoc> hit = hits.getSearchHit(0);
        return hit.getContent();
    }
}
