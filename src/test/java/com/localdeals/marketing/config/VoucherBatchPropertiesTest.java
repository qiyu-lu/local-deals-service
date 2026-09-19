package com.localdeals.marketing.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherBatchPropertiesTest {
    @Test
    void workersRunByDefaultAndBatchBoundsArePositive() {
        VoucherBatchProperties properties = new VoucherBatchProperties();

        assertThat(properties.isJobWorkerEnabled()).isTrue();
        assertThat(properties.isNotificationWorkerEnabled()).isTrue();
        assertThat(properties.getBatchSize()).isEqualTo(50);
        assertThat(properties.getNotificationBatchSize()).isEqualTo(50);
        properties.validate();
    }
}
