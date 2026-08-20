package com.localdeals.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;

/**
 * Positive and negative TTLs for the two cache resources hardened in M5B.
 */
@Data
@Component
@ConfigurationProperties(prefix = "local-deals.cache")
public class BoundedCacheProperties {

    private Duration shopDetailTtl = Duration.ofSeconds(30);
    private Duration shopDetailEmptyTtl = Duration.ofSeconds(30);
    private Duration shopTypeTtl = Duration.ofMinutes(100);
    private Duration shopTypeEmptyTtl = Duration.ofSeconds(30);

    @PostConstruct
    public void validate() {
        requirePositive(shopDetailTtl, "shop-detail-ttl");
        requirePositive(shopDetailEmptyTtl, "shop-detail-empty-ttl");
        requirePositive(shopTypeTtl, "shop-type-ttl");
        requirePositive(shopTypeEmptyTtl, "shop-type-empty-ttl");
        requireNoLongerThan(shopDetailEmptyTtl, shopDetailTtl,
                "shop-detail-empty-ttl", "shop-detail-ttl");
        requireNoLongerThan(shopTypeEmptyTtl, shopTypeTtl,
                "shop-type-empty-ttl", "shop-type-ttl");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.toMillis() <= 0L) {
            throw new IllegalStateException("local-deals.cache." + name + " must be positive");
        }
    }

    private static void requireNoLongerThan(Duration emptyTtl, Duration positiveTtl,
                                            String emptyName, String positiveName) {
        if (emptyTtl.compareTo(positiveTtl) > 0) {
            throw new IllegalStateException("local-deals.cache." + emptyName
                    + " must not exceed local-deals.cache." + positiveName);
        }
    }
}
