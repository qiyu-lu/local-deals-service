package com.localdeals.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherBatchPropertiesTest {
    @Test
    void workersAreClosedByDefaultAndBatchBoundsArePositive() {
        VoucherBatchProperties properties = new VoucherBatchProperties();

        assertThat(properties.isJobWorkerEnabled()).isFalse();
        assertThat(properties.isNotificationWorkerEnabled()).isFalse();
        assertThat(properties.getBatchSize()).isEqualTo(50);
        assertThat(properties.getNotificationBatchSize()).isEqualTo(50);
        properties.validate();
    }
}
