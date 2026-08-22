package com.localdeals.service;

import com.localdeals.config.VoucherBatchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Redis Pub/Sub notification worker; persistence remains the user-facing source of truth. */
@Slf4j
@Service
public class VoucherGrantNotificationOutboxWorker {
    private final VoucherBatchProperties properties;
    private final VoucherGrantNotificationOutboxService outboxService;

    public VoucherGrantNotificationOutboxWorker(VoucherBatchProperties properties,
            VoucherGrantNotificationOutboxService outboxService) {
        this.properties = properties;
        this.outboxService = outboxService;
    }

    @Scheduled(
            initialDelayString = "#{@voucherBatchProperties.initialDelay.toMillis()}",
            fixedDelayString = "#{@voucherBatchProperties.fixedDelay.toMillis()}")
    public void scheduledRun() {
        if (!properties.isNotificationWorkerEnabled()) return;
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("Voucher grant notification outbox tick failed; rows remain durable", failure);
        }
    }

    public VoucherGrantNotificationOutboxService.FlushResult runOnce() {
        return outboxService.processNextBatch(properties.getNotificationBatchSize());
    }
}
