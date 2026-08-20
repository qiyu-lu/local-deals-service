package com.localdeals.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class BlogLikePropertiesTest {

    @Test
    void defaultsAreValid() {
        BlogLikeProperties properties = new BlogLikeProperties();
        properties.validate();

        assertThat(properties.isWriteEnabled()).isFalse();
        assertThat(properties.isWorkerEnabled()).isFalse();
        assertThat(properties.getCleanupFixedDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getCleanupBatchSize()).isEqualTo(2_000);
        assertThat(properties.getCleanupMaxBatches()).isEqualTo(5);
    }

    @Test
    void invalidDurationsAndBatchSizeFailFast() {
        BlogLikeProperties zeroDelay = new BlogLikeProperties();
        zeroDelay.setFixedDelay(Duration.ZERO);
        assertThatThrownBy(zeroDelay::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixed-delay");

        BlogLikeProperties subMillisecondDelay = new BlogLikeProperties();
        subMillisecondDelay.setFixedDelay(Duration.ofNanos(1));
        assertThatThrownBy(subMillisecondDelay::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixed-delay");

        BlogLikeProperties oversizedBatch = new BlogLikeProperties();
        oversizedBatch.setBatchSize(5_001);
        assertThatThrownBy(oversizedBatch::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");

        BlogLikeProperties zeroRetention = new BlogLikeProperties();
        zeroRetention.setProcessedRetention(Duration.ZERO);
        assertThatThrownBy(zeroRetention::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("processed-retention");

        BlogLikeProperties unsafeBackfill = new BlogLikeProperties();
        unsafeBackfill.setLegacyBackfillOnStartup(true);
        unsafeBackfill.setWorkerEnabled(true);
        unsafeBackfill.setWriteEnabled(false);
        assertThatThrownBy(unsafeBackfill::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires");
    }
}
