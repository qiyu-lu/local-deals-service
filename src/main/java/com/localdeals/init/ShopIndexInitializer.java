package com.localdeals.init;

import com.localdeals.dto.ShopDoc;
import com.localdeals.entity.Shop;
import com.localdeals.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

@Slf4j
@Component
@Profile("!test")
@DependsOn("flywayInitializer")
public class ShopIndexInitializer {

    @Resource
    private IShopService shopService;

    @Resource
    private ElasticsearchRestTemplate esRestTemplate;

    @PostConstruct
    public void init() {
        IndexCoordinates index = IndexCoordinates.of("shop_index");
        // class-bound IndexOperations 才能基于 ShopDoc 的 @Document/@Field 注解创建索引和 mapping
        IndexOperations ops = esRestTemplate.indexOps(ShopDoc.class);
        if (!ops.exists()) {
            ops.create();
            ops.putMapping(ops.createMapping());
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
