package com.localdeals.mq;

import cn.hutool.json.JSONUtil;
import com.localdeals.dto.BlogDoc;
import com.localdeals.dto.ShopDoc;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

/**
 * Consumes Canal FlatMessage events from {@code mysql-sync-topic} (produced by Canal Server
 * watching MySQL binlog for {@code tb_shop} and {@code tb_blog}) and keeps the corresponding
 * Elasticsearch indices ({@code shop_index} / {@code blog_index}) eventually consistent with MySQL.
 */
@Slf4j
@Service
@RocketMQMessageListener(topic = "mysql-sync-topic", consumerGroup = "es-sync-consumer-group")
public class EsSyncConsumer implements RocketMQListener<String> {

    private static final IndexCoordinates SHOP_INDEX = IndexCoordinates.of("shop_index");
    private static final IndexCoordinates BLOG_INDEX = IndexCoordinates.of("blog_index");

    @Resource
    private ElasticsearchRestTemplate esTemplate;

    @Override
    public void onMessage(String message) {
        CanalMessage msg;
        try {
            msg = JSONUtil.toBean(message, CanalMessage.class);
        } catch (Exception e) {
            log.error("Failed to parse Canal message: {}", message, e);
            return;
        }
        if (msg == null || Boolean.TRUE.equals(msg.getIsDdl()) || msg.getData() == null || msg.getTable() == null) {
            return;
        }

        switch (msg.getTable()) {
            case "tb_shop":
                handleShop(msg);
                break;
            case "tb_blog":
                handleBlog(msg);
                break;
            default:
                log.debug("Ignoring Canal message for unhandled table={}", msg.getTable());
        }
    }

    private void handleShop(CanalMessage msg) {
        boolean isDelete = "DELETE".equalsIgnoreCase(msg.getType());
        for (Map<String, Object> row : msg.getData()) {
            String id = strVal(row, "id");
            if (id == null) {
                continue;
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
        }
    }

    private void handleBlog(CanalMessage msg) {
        boolean isDelete = "DELETE".equalsIgnoreCase(msg.getType());
        for (Map<String, Object> row : msg.getData()) {
            String id = strVal(row, "id");
            if (id == null) {
                continue;
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
        }
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
}
