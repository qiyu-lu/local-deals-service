package com.localdeals.trade.service;

import com.localdeals.trade.config.OrderProperties;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.TradeOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fallback for lost timer messages, Redis releases that failed after commit, and refunds
 * the channel has not confirmed. Safe on
 * every instance at once: closing is a compare-and-set and the release Lua is idempotent.
 */
@Slf4j
@Component
public class OrderTimeoutScanner {

    private final TradeOrderMapper tradeOrderMapper;
    private final OrderCloseService closeService;
    private final ReservationReleaseService releaseService;
    private final RefundService refundService;
    private final OrderProperties properties;

    public OrderTimeoutScanner(TradeOrderMapper tradeOrderMapper, OrderCloseService closeService,
                               ReservationReleaseService releaseService, RefundService refundService,
                               OrderProperties properties) {
        this.tradeOrderMapper = tradeOrderMapper;
        this.closeService = closeService;
        this.releaseService = releaseService;
        this.refundService = refundService;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "#{@orderProperties.scan.initialDelay.toMillis()}",
            fixedDelayString = "#{@orderProperties.scan.fixedDelay.toMillis()}")
    public void scheduledScan() {
        if (properties.getScan().isEnabled()) {
            scanOnce();
        }
    }

    /** One bounded pass; returns how many orders it closed or released. */
    public int scanOnce() {
        OrderProperties.Scan scan = properties.getScan();
        int done = 0;
        for (Long orderNo : tradeOrderMapper.selectOverduePending(scan.getGrace().getSeconds(), scan.getBatchSize())) {
            try {
                if (closeService.closeIfExpired(orderNo, "SCAN") == OrderCloseService.Outcome.CLOSED) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.error("Fallback close failed; next scan retries. orderNo={}", orderNo, e);
            }
        }
        for (TradeOrder order : tradeOrderMapper.selectReleasePending(
                scan.getReleaseRetryAfter().getSeconds(), scan.getBatchSize())) {
            if (releaseService.release(order)) {
                done++;
            }
        }
        try {
            done += refundService.retryStale(scan.getBatchSize());
        } catch (RuntimeException e) {
            log.error("Refund retry pass failed; next scan retries", e);
        }
        return done;
    }
}
