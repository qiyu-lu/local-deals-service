package com.localdeals.observability;

import com.localdeals.config.ObservabilityProperties;
import com.localdeals.mapper.VoucherGrantNotificationOutboxMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoucherGrantNotificationBacklogCollectorTest {

    @Test
    void emptyBacklogReportsRealZeroCountButNoSyntheticOldestAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);
        VoucherGrantNotificationOutboxMapper mapper = mock(VoucherGrantNotificationOutboxMapper.class);
        when(mapper.countPending()).thenReturn(0L);
        ObservabilityProperties properties = enabledProperties();
        VoucherGrantNotificationBacklogCollector collector =
                new VoucherGrantNotificationBacklogCollector(properties, mapper, metrics);

        collector.collect();

        assertThat(registry.get("local_deals.marketing.voucher_grant.outbox.pending").gauge().value())
                .isEqualTo(0D);
        assertThat(registry.get("local_deals.marketing.voucher_grant.outbox.oldest_age").gauge().value())
                .isNaN();
    }

    @Test
    void pendingAndOldestAgeComeFromDurableRows() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);
        VoucherGrantNotificationOutboxMapper mapper = mock(VoucherGrantNotificationOutboxMapper.class);
        when(mapper.countPending()).thenReturn(2L);
        when(mapper.oldestPendingCreateTime()).thenReturn(LocalDateTime.now().minusSeconds(3));
        VoucherGrantNotificationBacklogCollector collector =
                new VoucherGrantNotificationBacklogCollector(enabledProperties(), mapper, metrics);

        collector.collect();

        assertThat(registry.get("local_deals.marketing.voucher_grant.outbox.pending").gauge().value())
                .isEqualTo(2D);
        assertThat(registry.get("local_deals.marketing.voucher_grant.outbox.oldest_age").gauge().value())
                .isGreaterThanOrEqualTo(2D);
    }

    @Test
    void databaseSamplingFailureDoesNotBecomeZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalDealsMetrics metrics = new LocalDealsMetrics(registry);
        VoucherGrantNotificationOutboxMapper mapper = mock(VoucherGrantNotificationOutboxMapper.class);
        when(mapper.countPending()).thenThrow(new IllegalStateException("db unavailable"));
        VoucherGrantNotificationBacklogCollector collector =
                new VoucherGrantNotificationBacklogCollector(enabledProperties(), mapper, metrics);

        collector.collect();

        assertThat(registry.get("local_deals.marketing.voucher_grant.outbox.pending").gauge().value())
                .isNaN();
        assertThat(registry.get("local_deals.marketing.voucher_grant.outbox.oldest_age").gauge().value())
                .isNaN();
    }

    private ObservabilityProperties enabledProperties() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setSamplingEnabled(true);
        return properties;
    }
}
