package com.localdeals.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficControlPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsSafeDefaultsAndOverrides() {
        runner.run(context -> {
            TrafficControlProperties properties = context.getBean(TrafficControlProperties.class);
            assertThat(properties.getSeckill().getWindow()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.getSeckill().getActivityLimit()).isEqualTo(300);
            assertThat(properties.getRead().getDbMaxConcurrent()).isEqualTo(4);
            assertThat(properties.getRead().getSharedLoadWait()).isEqualTo(Duration.ofMillis(750));
            assertThat(properties.getSearch().getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.getSearch().getSocketTimeout()).isEqualTo(Duration.ofSeconds(1));
        });
        runner.withPropertyValues(
                        "local-deals.traffic.seckill.window=2s",
                        "local-deals.traffic.seckill.activity-limit=500",
                        "local-deals.traffic.read.db-max-concurrent=8",
                        "local-deals.traffic.read.shared-load-wait=1s",
                        "local-deals.traffic.search.socket-timeout=1500ms")
                .run(context -> {
                    TrafficControlProperties properties = context.getBean(TrafficControlProperties.class);
                    assertThat(properties.getSeckill().getWindow()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.getSeckill().getActivityLimit()).isEqualTo(500);
                    assertThat(properties.getRead().getDbMaxConcurrent()).isEqualTo(8);
                    assertThat(properties.getRead().getSharedLoadWait()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.getSearch().getSocketTimeout()).isEqualTo(Duration.ofMillis(1500));
                });
    }

    @Test
    void rejectsNullZeroNegativeAndOutOfRangeValues() {
        assertFails("local-deals.traffic.seckill.window=0ms");
        assertFails("local-deals.traffic.seckill.window=61s");
        assertFails("local-deals.traffic.seckill.user-limit=0");
        assertFails("local-deals.traffic.read.db-max-concurrent=65");
        assertFails("local-deals.traffic.read.db-max-wait=101ms");
        assertFails("local-deals.traffic.read.shared-load-wait=99ms");
        assertFails("local-deals.traffic.search.connect-timeout=99ms");
        assertFails("local-deals.traffic.search.socket-timeout=2001ms");
    }

    private void assertFails(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TrafficControlProperties.class)
    static class TestConfiguration {
    }
}
