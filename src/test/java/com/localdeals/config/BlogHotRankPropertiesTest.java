package com.localdeals.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlogHotRankPropertiesTest {

    @Test
    void defaultsAreFailClosedAndBounded() {
        BlogHotRankProperties properties = new BlogHotRankProperties();

        assertThat(properties.isReadEnabled()).isFalse();
        assertThat(properties.isRefreshEnabled()).isFalse();
        assertThat(properties.getTopK()).isEqualTo(1_000);
        assertThat(properties.getPageSize()).isEqualTo(10);
        assertThat(properties.getInitialDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getFixedDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getMaxStale()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void rejectsNonPositiveTopK() {
        BlogHotRankProperties properties = new BlogHotRankProperties();
        properties.setTopK(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-k must be between");
    }

    @Test
    void rejectsPageSizeOutsideTopK() {
        BlogHotRankProperties properties = new BlogHotRankProperties();
        properties.setTopK(5);
        properties.setPageSize(6);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("page-size");
    }

    @Test
    void rejectsNonPositiveDelays() {
        BlogHotRankProperties zeroInitialDelay = new BlogHotRankProperties();
        zeroInitialDelay.setInitialDelay(Duration.ZERO);
        assertThatThrownBy(zeroInitialDelay::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initial-delay");

        BlogHotRankProperties negativeFixedDelay = new BlogHotRankProperties();
        negativeFixedDelay.setFixedDelay(Duration.ofMillis(-1));
        assertThatThrownBy(negativeFixedDelay::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixed-delay");

        BlogHotRankProperties staleNoGreaterThanRefresh = new BlogHotRankProperties();
        staleNoGreaterThanRefresh.setMaxStale(Duration.ofSeconds(30));
        assertThatThrownBy(staleNoGreaterThanRefresh::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-stale");
    }
}
