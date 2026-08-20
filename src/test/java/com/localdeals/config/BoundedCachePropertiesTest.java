package com.localdeals.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedCachePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsSafeDefaults() {
        contextRunner.run(context -> {
            BoundedCacheProperties properties = context.getBean(BoundedCacheProperties.class);

            assertThat(properties.getShopDetailTtl()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getShopDetailEmptyTtl()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getShopTypeTtl()).isEqualTo(Duration.ofMinutes(100));
            assertThat(properties.getShopTypeEmptyTtl()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void bindsDurationOverrides() {
        contextRunner.withPropertyValues(
                "local-deals.cache.shop-detail-ttl=45s",
                "local-deals.cache.shop-detail-empty-ttl=5s",
                "local-deals.cache.shop-type-ttl=2h",
                "local-deals.cache.shop-type-empty-ttl=1m")
                .run(context -> {
                    BoundedCacheProperties properties = context.getBean(BoundedCacheProperties.class);
                    assertThat(properties.getShopDetailTtl()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(properties.getShopDetailEmptyTtl()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.getShopTypeTtl()).isEqualTo(Duration.ofHours(2));
                    assertThat(properties.getShopTypeEmptyTtl()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void rejectsNonPositiveAndOverlongEmptyTtls() {
        contextRunner.withPropertyValues("local-deals.cache.shop-detail-ttl=0ms")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                "local-deals.cache.shop-detail-ttl=5s",
                "local-deals.cache.shop-detail-empty-ttl=6s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BoundedCacheProperties.class)
    static class TestConfiguration {
    }
}
