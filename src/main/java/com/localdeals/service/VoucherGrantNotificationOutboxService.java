package com.localdeals.service;

import com.localdeals.config.VoucherBatchProperties;
import com.localdeals.entity.VoucherGrantNotificationOutbox;
import com.localdeals.mapper.VoucherGrantNotificationOutboxMapper;
import com.localdeals.websocket.WebSocketNotifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** Publishes a bounded due prefix and records accepted Redis publishes transactionally. */
@Service
public class VoucherGrantNotificationOutboxService {
    private static final String EVENT_TYPE = "VOUCHER_GRANTED";
    private static final String REDIS_PUBLISH_FAILED = "REDIS_PUBLISH_FAILED";

    private final VoucherGrantNotificationOutboxMapper outboxMapper;
    private final WebSocketNotifier webSocketNotifier;
    private final VoucherBatchProperties properties;

    public VoucherGrantNotificationOutboxService(VoucherGrantNotificationOutboxMapper outboxMapper,
            WebSocketNotifier webSocketNotifier, VoucherBatchProperties properties) {
        this.outboxMapper = outboxMapper;
        this.webSocketNotifier = webSocketNotifier;
        this.properties = properties;
    }

    @Transactional
    public FlushResult processNextBatch(int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("notification batchSize 必须为正数");
        List<VoucherGrantNotificationOutbox> rows = outboxMapper.selectDueForUpdate(batchSize);
        int published = 0;
        int retried = 0;
        for (VoucherGrantNotificationOutbox row : rows) {
            if (!EVENT_TYPE.equals(row.getEventType())) {
                throw new IllegalStateException("unsupported voucher grant notification event type");
            }
            try {
                webSocketNotifier.notifyVoucherGranted(row);
            } catch (RuntimeException redisFailure) {
                markRetry(row);
                retried++;
                continue;
            }
            if (outboxMapper.markPublished(row.getId()) != 1) {
                throw new IllegalStateException("notification outbox publish marker update failed");
            }
            published++;
        }
        return new FlushResult(rows.size(), published, retried);
    }

    private void markRetry(VoucherGrantNotificationOutbox row) {
        int currentAttempts = row.getAttempts() == null ? 0 : row.getAttempts();
        int attempts = Math.min(properties.getNotificationMaxAttempts(), currentAttempts + 1);
        LocalDateTime nextAttempt = LocalDateTime.now().plus(backoff(attempts));
        if (outboxMapper.markRetry(row.getId(), attempts, nextAttempt, REDIS_PUBLISH_FAILED) != 1) {
            throw new IllegalStateException("notification outbox retry marker update failed");
        }
    }

    private Duration backoff(int attempts) {
        long multiplier = 1L << Math.min(20, Math.max(0, attempts - 1));
        long baseMillis = Math.max(1L, multiplier * 1000L);
        return Duration.ofMillis(Math.min(properties.getNotificationMaxBackoff().toMillis(), baseMillis));
    }

    public static final class FlushResult {
        private final int selected;
        private final int published;
        private final int retried;

        FlushResult(int selected, int published, int retried) {
            this.selected = selected;
            this.published = published;
            this.retried = retried;
        }

        public int getSelected() {
            return selected;
        }

        public int getPublished() {
            return published;
        }

        public int getRetried() {
            return retried;
        }
    }
}
