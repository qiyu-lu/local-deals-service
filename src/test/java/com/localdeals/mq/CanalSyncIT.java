package com.localdeals.mq;

import com.localdeals.dto.ShopDoc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies EsSyncConsumer processes Canal FlatMessage JSON and updates Elasticsearch correctly.
 * Tests call onMessage() directly rather than going through the Canal→RocketMQ pipeline,
 * since Canal's Docker image requires additional configuration for RocketMQ delivery.
 */
@SpringBootTest
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "M5A_ISOLATED", matches = "true")
class CanalSyncIT {

    private static final long TEST_SHOP_ID = 999901L;
    private static final IndexCoordinates SHOP_INDEX = IndexCoordinates.of("shop_index");

    @Resource
    private EsSyncConsumer esSyncConsumer;

    @Resource
    private ElasticsearchRestTemplate esTemplate;

    @BeforeEach
    void setup() {
        // The shop_index initializer is disabled under the "test" profile, so the index
        // must exist before EsSyncConsumer can index into it.
        IndexOperations ops = esTemplate.indexOps(ShopDoc.class);
        if (!ops.exists()) {
            ops.create();
            ops.putMapping(ops.createMapping());
        }
        cleanupEsDoc();
    }

    @AfterEach
    void cleanup() {
        cleanupEsDoc();
    }

    private void cleanupEsDoc() {
        try {
            esTemplate.delete(String.valueOf(TEST_SHOP_ID), SHOP_INDEX);
        } catch (Exception ignored) {
            // Document may not exist yet; nothing to clean up.
        }
    }

    @Test
    void onMessage_insert_createsEsDocument() {
        String json = "{\"database\":\"local_deals\",\"table\":\"tb_shop\",\"type\":\"INSERT\",\"isDdl\":false," +
            "\"data\":[{\"id\":\"999901\",\"name\":\"CanalTestShop\",\"type_id\":\"1\"," +
            "\"images\":\"test.jpg\",\"address\":\"Test Address\",\"x\":\"120.15\",\"y\":\"30.33\"," +
            "\"avg_price\":\"100\",\"sold\":\"0\",\"comments\":\"0\",\"score\":\"40\"}]}";
        esSyncConsumer.onMessage(json);
        ShopDoc doc = findShopDoc();
        assertThat(doc).isNotNull();
        assertThat(doc.getName()).isEqualTo("CanalTestShop");
        assertThat(doc.getLocation()).isEqualTo("30.33,120.15");
        assertThat(doc.getTypeId()).isEqualTo(1L);
    }

    @Test
    void onMessage_update_updatesEsDocument() {
        // seed with INSERT first
        String insertJson = "{\"database\":\"local_deals\",\"table\":\"tb_shop\",\"type\":\"INSERT\",\"isDdl\":false," +
            "\"data\":[{\"id\":\"999901\",\"name\":\"OldName\",\"type_id\":\"1\"," +
            "\"images\":\"test.jpg\",\"address\":\"Test Address\",\"x\":\"120.15\",\"y\":\"30.33\"," +
            "\"avg_price\":\"100\",\"sold\":\"0\",\"comments\":\"0\",\"score\":\"40\"}]}";
        esSyncConsumer.onMessage(insertJson);
        // now UPDATE
        String updateJson = "{\"database\":\"local_deals\",\"table\":\"tb_shop\",\"type\":\"UPDATE\",\"isDdl\":false," +
            "\"data\":[{\"id\":\"999901\",\"name\":\"NewName\",\"type_id\":\"1\"," +
            "\"images\":\"test.jpg\",\"address\":\"New Address\",\"x\":\"120.15\",\"y\":\"30.33\"," +
            "\"avg_price\":\"200\",\"sold\":\"5\",\"comments\":\"2\",\"score\":\"45\"}]," +
            "\"old\":[{\"name\":\"OldName\",\"avg_price\":\"100\"}]}";
        esSyncConsumer.onMessage(updateJson);
        ShopDoc doc = findShopDoc();
        assertThat(doc).isNotNull();
        assertThat(doc.getName()).isEqualTo("NewName");
    }

    @Test
    void onMessage_delete_removesEsDocument() {
        // seed
        String insertJson = "{\"database\":\"local_deals\",\"table\":\"tb_shop\",\"type\":\"INSERT\",\"isDdl\":false," +
            "\"data\":[{\"id\":\"999901\",\"name\":\"ToDelete\",\"type_id\":\"1\"," +
            "\"images\":\"test.jpg\",\"address\":\"Test Address\",\"x\":\"120.15\",\"y\":\"30.33\"," +
            "\"avg_price\":\"100\",\"sold\":\"0\",\"comments\":\"0\",\"score\":\"40\"}]}";
        esSyncConsumer.onMessage(insertJson);
        // verify seeded
        assertThat(findShopDoc()).isNotNull();
        // now DELETE
        String deleteJson = "{\"database\":\"local_deals\",\"table\":\"tb_shop\",\"type\":\"DELETE\",\"isDdl\":false," +
            "\"data\":[{\"id\":\"999901\",\"name\":\"ToDelete\",\"type_id\":\"1\"," +
            "\"images\":\"test.jpg\",\"address\":\"Test Address\",\"x\":\"120.15\",\"y\":\"30.33\"," +
            "\"avg_price\":\"100\",\"sold\":\"0\",\"comments\":\"0\",\"score\":\"40\"}]}";
        esSyncConsumer.onMessage(deleteJson);
        assertThat(findShopDoc()).isNull();
    }

    private ShopDoc findShopDoc() {
        try {
            return esTemplate.get(String.valueOf(TEST_SHOP_ID), ShopDoc.class, SHOP_INDEX);
        } catch (Exception e) {
            return null;
        }
    }
}
