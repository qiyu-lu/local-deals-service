package com.localdeals.mq;

import cn.hutool.json.JSONUtil;
import com.localdeals.dto.BlogDoc;
import com.localdeals.dto.ShopDoc;
import com.localdeals.observability.LocalDealsMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Consumes Canal FlatMessage events from the configured ES sync topic (produced by Canal Server
 * watching MySQL binlog for {@code tb_shop} and {@code tb_blog}) and keeps the corresponding
 * Elasticsearch indices ({@code shop_index} / {@code blog_index}) eventually consistent with MySQL.
 */
@Slf4j
@Service
@RocketMQMessageListener(
        topic = "${local-deals.es-sync.topic:mysql-sync-topic}",
        consumerGroup = "${local-deals.es-sync.consumer-group:es-sync-consumer-group}")
public class EsSyncConsumer implements RocketMQListener<String> {

    private static final IndexCoordinates SHOP_INDEX = IndexCoordinates.of("shop_index");
    private static final IndexCoordinates BLOG_INDEX = IndexCoordinates.of("blog_index");

    private final ElasticsearchRestTemplate esTemplate;
    private final LocalDealsMetrics metrics;

    public EsSyncConsumer(ElasticsearchRestTemplate esTemplate, LocalDealsMetrics metrics) {
        this.esTemplate = esTemplate;
        this.metrics = metrics;
    }

    @Override
    public void onMessage(String message) {
        CanalMessage msg;
        try {
            msg = JSONUtil.toBean(message, CanalMessage.class);
        } catch (Exception e) {
            log.error("Failed to parse Canal message; delivery will be retried", e);
            metrics.recordEsMessage(LocalDealsMetrics.EsTable.IGNORED,
                    LocalDealsMetrics.EsOperation.OTHER,
                    LocalDealsMetrics.EsMessageResult.FAILURE);
            throw new IllegalArgumentException("Malformed Canal message, will retry", e);
        }
        LocalDealsMetrics.EsTable table = tableOf(msg == null ? null : msg.getTable());
        LocalDealsMetrics.EsOperation operation = operationOf(msg == null ? null : msg.getType());
        if (msg != null && (Boolean.TRUE.equals(msg.getIsDdl()) ||
                table == LocalDealsMetrics.EsTable.IGNORED)) {
            metrics.recordEsMessage(table, operation, LocalDealsMetrics.EsMessageResult.IGNORED);
            return;
        }
        if (msg == null || msg.getData() == null || msg.getData().isEmpty()) {
            metrics.recordEsMessage(table, operation, LocalDealsMetrics.EsMessageResult.FAILURE);
            throw new IllegalArgumentException("Target Canal message has no rows, will retry");
        }

        long startedAt = System.nanoTime();
        ApplyStats stats;
        try {
            stats = table == LocalDealsMetrics.EsTable.SHOP
                    ? handleShop(msg, table, operation)
                    : handleBlog(msg, table, operation);
        } finally {
            metrics.recordEsDuration(table, operation, System.nanoTime() - startedAt);
        }
        LocalDealsMetrics.EsMessageResult result = stats.failures == 0
                ? LocalDealsMetrics.EsMessageResult.SUCCESS
                : (stats.successes == 0
                        ? LocalDealsMetrics.EsMessageResult.FAILURE
                        : LocalDealsMetrics.EsMessageResult.PARTIAL_FAILURE);
        metrics.recordEsMessage(table, operation, result);
        if (stats.failures > 0) {
            throw new IllegalStateException("Failed to apply " + stats.failures +
                    " row(s) from target Canal message; delivery will be retried", stats.firstFailure);
        }
    }

    private ApplyStats handleShop(CanalMessage msg,
                                  LocalDealsMetrics.EsTable table,
                                  LocalDealsMetrics.EsOperation operation) {
        boolean isDelete = "DELETE".equalsIgnoreCase(msg.getType());
        ApplyStats stats = new ApplyStats();
        for (Map<String, Object> row : msg.getData()) {
            try {
                String id = strVal(row, "id");
                if (id == null) {
                    throw new IllegalArgumentException("Canal shop row is missing id");
                }
                if (isDelete) {
                    esTemplate.delete(id, SHOP_INDEX);
                    log.debug("Deleted shop from ES. id={}", id);
                } else {
                    ShopDoc doc = rowToShopDoc(row);
                    IndexQuery query = new IndexQueryBuilder().withId(id).withObject(doc).build();
                    esTemplate.index(query, SHOP_INDEX);
                    log.debug("Upserted shop in ES. id={}", id);
                }
                stats.success(metrics, table, operation);
            } catch (Exception e) {
                stats.failure(metrics, table, operation, e);
                log.warn("Failed to apply Canal row for table={}; delivery will be retried",
                        msg.getTable(), e);
            }
        }
        return stats;
    }

    private ApplyStats handleBlog(CanalMessage msg,
                                  LocalDealsMetrics.EsTable table,
                                  LocalDealsMetrics.EsOperation operation) {
        boolean isDelete = "DELETE".equalsIgnoreCase(msg.getType());
        ApplyStats stats = new ApplyStats();
        for (Map<String, Object> row : msg.getData()) {
            try {
                String id = strVal(row, "id");
                if (id == null) {
                    throw new IllegalArgumentException("Canal blog row is missing id");
                }
                if (isDelete) {
                    esTemplate.delete(id, BLOG_INDEX);
                    log.debug("Deleted blog from ES. id={}", id);
                } else {
                    BlogDoc doc = rowToBlogDoc(row);
                    IndexQuery query = new IndexQueryBuilder().withId(id).withObject(doc).build();
                    esTemplate.index(query, BLOG_INDEX);
                    log.debug("Upserted blog in ES. id={}", id);
                }
                stats.success(metrics, table, operation);
            } catch (Exception e) {
                stats.failure(metrics, table, operation, e);
                log.warn("Failed to apply Canal row for table={}; delivery will be retried",
                        msg.getTable(), e);
            }
        }
        return stats;
    }

    private ShopDoc rowToShopDoc(Map<String, Object> row) {
        ShopDoc doc = new ShopDoc();
        doc.setId(Long.valueOf(strVal(row, "id")));
        doc.setName(strVal(row, "name"));
        doc.setAddress(strVal(row, "address"));

        String typeId = strVal(row, "type_id");
        if (typeId != null) {
            doc.setTypeId(Long.valueOf(typeId));
        }
        String avgPrice = strVal(row, "avg_price");
        if (avgPrice != null) {
            doc.setAvgPrice(Long.valueOf(avgPrice));
        }
        String score = strVal(row, "score");
        if (score != null) {
            doc.setScore(Integer.valueOf(score));
        }
        String sold = strVal(row, "sold");
        if (sold != null) {
            doc.setSold(Integer.valueOf(sold));
        }
        String y = strVal(row, "y");
        String x = strVal(row, "x");
        if (y != null && x != null) {
            // ES geo_point expects "lat,lon"; MySQL column y = latitude, x = longitude.
            doc.setLocation(y + "," + x);
        }
        return doc;
    }

    private BlogDoc rowToBlogDoc(Map<String, Object> row) {
        BlogDoc doc = new BlogDoc();
        doc.setId(Long.valueOf(strVal(row, "id")));
        doc.setTitle(strVal(row, "title"));
        doc.setContent(strVal(row, "content"));

        String userId = strVal(row, "user_id");
        if (userId != null) {
            doc.setUserId(Long.valueOf(userId));
        }
        String liked = strVal(row, "liked");
        if (liked != null) {
            doc.setLiked(Integer.valueOf(liked));
        }
        return doc;
    }

    private String strVal(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static LocalDealsMetrics.EsTable tableOf(String table) {
        if ("tb_shop".equals(table)) {
            return LocalDealsMetrics.EsTable.SHOP;
        }
        if ("tb_blog".equals(table)) {
            return LocalDealsMetrics.EsTable.BLOG;
        }
        return LocalDealsMetrics.EsTable.IGNORED;
    }

    private static LocalDealsMetrics.EsOperation operationOf(String operation) {
        if ("INSERT".equalsIgnoreCase(operation)) {
            return LocalDealsMetrics.EsOperation.INSERT;
        }
        if ("UPDATE".equalsIgnoreCase(operation)) {
            return LocalDealsMetrics.EsOperation.UPDATE;
        }
        if ("DELETE".equalsIgnoreCase(operation)) {
            return LocalDealsMetrics.EsOperation.DELETE;
        }
        return LocalDealsMetrics.EsOperation.OTHER;
    }

    private static final class ApplyStats {
        private int successes;
        private int failures;
        private RuntimeException firstFailure;

        private void success(LocalDealsMetrics metrics, LocalDealsMetrics.EsTable table,
                             LocalDealsMetrics.EsOperation operation) {
            successes++;
            metrics.recordEsRow(table, operation, LocalDealsMetrics.EsRowResult.SUCCESS);
        }

        private void failure(LocalDealsMetrics metrics, LocalDealsMetrics.EsTable table,
                             LocalDealsMetrics.EsOperation operation, Exception cause) {
            failures++;
            if (firstFailure == null) {
                firstFailure = cause instanceof RuntimeException
                        ? (RuntimeException) cause : new IllegalStateException(cause);
            }
            metrics.recordEsRow(table, operation, LocalDealsMetrics.EsRowResult.FAILURE);
        }
    }
}
