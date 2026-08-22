package com.localdeals.observability;

import com.localdeals.config.ObservabilityProperties;
import com.localdeals.mapper.VoucherGrantNotificationOutboxMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;

/** Read-only sampling of the durable voucher-grant notification outbox. */
@Component
@ConditionalOnProperty(
        prefix = "local-deals.observability",
        name = "sampling-enabled",
        havingValue = "true")
public class VoucherGrantNotificationBacklogCollector {
    private final ObservabilityProperties properties;
    private final VoucherGrantNotificationOutboxMapper outboxMapper;
    private final LocalDealsMetrics metrics;

    public VoucherGrantNotificationBacklogCollector(ObservabilityProperties properties,
            VoucherGrantNotificationOutboxMapper outboxMapper, LocalDealsMetrics metrics) {
        this.properties = properties;
        this.outboxMapper = outboxMapper;
        this.metrics = metrics;
    }

    @Scheduled(
            initialDelayString = "#{@observabilityProperties.initialDelay.toMillis()}",
            fixedDelayString = "#{@observabilityProperties.samplingInterval.toMillis()}")
    public void collect() {
        if (!properties.isSamplingEnabled()) return;
        try {
            long pending = outboxMapper.countPending();
            if (pending < 0L) throw new IllegalStateException("negative voucher grant outbox count");
            if (pending == 0L) {
                // Empty is a real count, but there is no real oldest row to age.
                metrics.updateVoucherGrantOutboxBacklog(0L, Double.NaN);
                return;
            }
            LocalDateTime oldest = outboxMapper.oldestPendingCreateTime();
            if (oldest == null) throw new IllegalStateException("pending outbox has no oldest row");
            double age = Math.max(0D, Duration.between(
                    oldest.atZone(ZoneId.systemDefault()).toInstant(), Instant.now()).toMillis() / 1000D);
            metrics.updateVoucherGrantOutboxBacklog(pending, age);
        } catch (RuntimeException failure) {
            metrics.failVoucherGrantOutboxCollector();
        }
    }
}
