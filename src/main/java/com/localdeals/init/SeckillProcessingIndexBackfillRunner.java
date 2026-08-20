package com.localdeals.init;

import com.localdeals.config.SeckillProperties;
import com.localdeals.mq.SeckillOrderMessage;
import com.localdeals.service.SeckillOrderStateService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;

/**
 * Explicit, one-startup upgrade tool for old exact PROCESSING states.
 *
 * <p>The runner is absent unless the operator enables {@code backfill-on-startup}. It uses
 * Redisson's lazy SCAN iterator over small per-order status hashes; it never enumerates the
 * potentially large per-voucher reservation Hash. StateService's Lua performs the authoritative
 * status/reservation check before persisting and indexing an old PROCESSING state.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(
        prefix = "local-deals.seckill.reconciliation",
        name = "backfill-on-startup",
        havingValue = "true")
public class SeckillProcessingIndexBackfillRunner implements ApplicationRunner {

    private static final String STATUS_PATTERN = SECKILL_ORDER_STATUS_KEY + "*";

    private final RedissonClient redissonClient;
    private final SeckillOrderStateService stateService;
    private final SeckillProperties seckillProperties;

    public SeckillProcessingIndexBackfillRunner(RedissonClient redissonClient,
                                                SeckillOrderStateService stateService,
                                                SeckillProperties seckillProperties) {
        this.redissonClient = redissonClient;
        this.stateService = stateService;
        this.seckillProperties = seckillProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        BackfillSummary summary = backfillOnce();
        log.info("Seckill PROCESSING index backfill completed: scanned={}, indexed={}, " +
                        "alreadyIndexed={}, terminal={}, quarantined={}, unsafe={}",
                summary.scanned, summary.indexed, summary.alreadyIndexed,
                summary.terminal, summary.quarantined, summary.unsafe);
        if (summary.unsafe > 0) {
            // Do not let an apparently successful upgrade hide records which were neither
            // indexed nor durably quarantined. The original Redis keys are deliberately kept.
            throw new IllegalStateException(
                    "Seckill PROCESSING backfill left " + summary.unsafe +
                            " unsafe record(s); inspect the ERROR log before serving traffic");
        }
    }

    BackfillSummary backfillOnce() {
        int scanCount = seckillProperties.getReconciliation().getScanCount();
        RKeys keys = redissonClient.getKeys();
        BackfillSummary summary = new BackfillSummary();
        for (String statusKey : keys.getKeysByPattern(STATUS_PATTERN, scanCount)) {
            summary.scanned++;
            Long keyOrderId = parseOrderId(statusKey);
            if (keyOrderId == null) {
                log.error("Unsafe seckill status key has a non-positive or non-numeric order-id " +
                        "suffix; preserving the source key. key={}", statusKey);
                quarantineRaw(statusKey, "BACKFILL_INVALID_STATUS_KEY", summary);
                continue;
            }

            SeckillOrderStateService.Snapshot snapshot = stateService.find(keyOrderId);
            if (snapshot == null) {
                log.error("Seckill status hash is missing required ownership fields. orderId={}", keyOrderId);
                markUnsafeOrder(keyOrderId, "BACKFILL_STATE_MISSING_OR_INVALID", summary);
                continue;
            }

            // Use the key-derived id. If the Hash contains another order id, the exact Lua must
            // classify it as an ownership mismatch instead of indexing the hidden id.
            SeckillOrderMessage message = new SeckillOrderMessage(
                    snapshot.getVoucherId(), snapshot.getUserId(), keyOrderId);
            SeckillOrderStateService.ProcessingBackfillDecision decision =
                    stateService.backfillProcessingOrder(message);
            switch (decision) {
                case INDEXED:
                    summary.indexed++;
                    break;
                case ALREADY_INDEXED:
                    summary.alreadyIndexed++;
                    break;
                case TERMINAL:
                    summary.terminal++;
                    break;
                case QUARANTINED:
                    summary.quarantined++;
                    break;
                case OWNERSHIP_MISMATCH:
                case STATE_INVALID:
                case RESERVATION_MISMATCH:
                    log.error("Unsafe seckill state rejected by exact backfill. orderId={}, decision={}",
                            keyOrderId, decision);
                    markUnsafeOrder(keyOrderId, "BACKFILL_" + decision.name(), summary);
                    break;
                default:
                    log.error("Unknown PROCESSING backfill decision. orderId={}, decision={}",
                            keyOrderId, decision);
                    markUnsafeOrder(keyOrderId, "BACKFILL_UNKNOWN_DECISION", summary);
            }
        }
        return summary;
    }

    private void markUnsafeOrder(Long orderId, String reason, BackfillSummary summary) {
        // A canonical order id can have an in-flight MQ consumer. When its owner cannot be
        // proved, this startup runner cannot acquire the same user lock safely; creating a
        // consumer-visible quarantine here would race a DB commit. Preserve the source evidence
        // and fail startup instead.
        summary.unsafe++;
        log.error("Unsafe canonical PROCESSING state requires stopped-consumer/manual review. " +
                "orderId={}, reason={}", orderId, reason);
    }

    private void quarantineRaw(String rawMember, String reason, BackfillSummary summary) {
        if (rawMember != null && stateService.quarantineProcessingMember(rawMember, reason)) {
            summary.quarantined++;
        } else {
            summary.unsafe++;
            log.error("Unable to durably quarantine unsafe raw PROCESSING evidence. member={}, reason={}",
                    rawMember, reason);
        }
    }

    private Long parseOrderId(String statusKey) {
        if (statusKey == null || !statusKey.startsWith(SECKILL_ORDER_STATUS_KEY)) {
            return null;
        }
        String suffix = statusKey.substring(SECKILL_ORDER_STATUS_KEY.length());
        if (suffix.isEmpty() || suffix.charAt(0) == '0') {
            return null;
        }
        for (int index = 0; index < suffix.length(); index++) {
            char current = suffix.charAt(index);
            if (current < '0' || current > '9') {
                return null;
            }
        }
        try {
            long orderId = Long.parseLong(suffix);
            return orderId > 0L ? orderId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static final class BackfillSummary {
        private int scanned;
        private int indexed;
        private int alreadyIndexed;
        private int terminal;
        private int quarantined;
        private int unsafe;

        int getScanned() {
            return scanned;
        }

        int getIndexed() {
            return indexed;
        }

        int getAlreadyIndexed() {
            return alreadyIndexed;
        }

        int getTerminal() {
            return terminal;
        }

        int getQuarantined() {
            return quarantined;
        }

        int getUnsafe() {
            return unsafe;
        }
    }
}
