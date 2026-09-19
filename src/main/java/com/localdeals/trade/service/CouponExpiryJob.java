package com.localdeals.trade.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks lapsed coupons EXPIRED in bounded batches (idx(status, valid_to)). */
@Slf4j
@Component
public class CouponExpiryJob {

    private static final int BATCH = 500;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final CouponService couponService;
    private final boolean enabled;

    public CouponExpiryJob(CouponService couponService,
                           @Value("${local-deals.coupon.expiry-enabled:true}") boolean enabled) {
        this.couponService = couponService;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${local-deals.coupon.expiry-initial-delay:30s}",
            fixedDelayString = "${local-deals.coupon.expiry-fixed-delay:60s}")
    public void expire() {
        if (!enabled) {
            return;
        }
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int expired = couponService.expireDue(BATCH);
            total += expired;
            if (expired < BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("Expired {} coupons", total);
        }
    }
}
