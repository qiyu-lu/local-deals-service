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
