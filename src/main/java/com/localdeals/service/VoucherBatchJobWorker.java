package com.localdeals.service;

import com.localdeals.config.VoucherBatchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** MySQL-row-locked batch worker. It is deliberately disabled by default. */
@Slf4j
@Service
public class VoucherBatchJobWorker {
    private final VoucherBatchProperties properties;
    private final VoucherBatchJobService jobService;

    public VoucherBatchJobWorker(VoucherBatchProperties properties, VoucherBatchJobService jobService) {
        this.properties = properties;
        this.jobService = jobService;
    }

    @Scheduled(
            initialDelayString = "#{@voucherBatchProperties.initialDelay.toMillis()}",
            fixedDelayString = "#{@voucherBatchProperties.fixedDelay.toMillis()}")
    public void scheduledRun() {
        if (!properties.isJobWorkerEnabled()) return;
        try {
            runOnce();
        } catch (RuntimeException failure) {
            // A transaction rollback leaves the Job/item durable state available for the next tick.
            log.error("Voucher batch worker tick failed; durable state will be retried", failure);
        }
    }

    public int runOnce() {
        int snapshotCount = jobService.snapshotOne();
        VoucherBatchJobService.BatchRunResult result = jobService.processNextBatch(properties.getBatchSize());
        return snapshotCount + result.getProcessed();
    }
}
