package com.localdeals.trade.service;

import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.TradeOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;

import static com.localdeals.platform.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * Gives a closed or refunded order's unit back to the Redis admission layer and lets the user
 * buy again. The DB stock was already restored in the transition's transaction; this is the
 * after-commit half, tracked by {@code trade_order.release_pending} so the scan can finish it
 * when Redis was unavailable at commit time.
 */
@Slf4j
@Service
public class ReservationReleaseService {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>();

    static {
        RELEASE_SCRIPT.setLocation(new ClassPathResource("lua/seckill_release.lua"));
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final TradeOrderMapper tradeOrderMapper;
    private final ISeckillVoucherService seckillVoucherService;

    public ReservationReleaseService(StringRedisTemplate stringRedisTemplate, TradeOrderMapper tradeOrderMapper,
                                     ISeckillVoucherService seckillVoucherService,
                                     SeckillSoldOutRegistry soldOutRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.tradeOrderMapper = tradeOrderMapper;
        this.seckillVoucherService = seckillVoucherService;
    }

    /**
     * The order's unit goes back: DB stock in the caller's transaction (only the winner of the
     * CLOSE / REFUND_SUCCESS transition calls this, so it happens once), Redis after commit.
     */
    public void returnUnit(TradeOrder order) {
        if (!seckillVoucherService.update().setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId()).update()) {
            throw new IllegalStateException("Seckill stock row is missing. voucherId=" + order.getVoucherId());
        }
        releaseAfterCommit(order);
    }

    /** @return true when Redis is released (now or earlier) and the pending flag is cleared. */
    public boolean release(TradeOrder order) {
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT,
                    Arrays.asList(SECKILL_STOCK_KEY + order.getVoucherId(),
                            SECKILL_RESERVATION_KEY + order.getVoucherId()),
                    order.getUserId().toString(), order.getOrderNo().toString());
            tradeOrderMapper.clearReleasePending(order.getOrderNo());
            return true;
        } catch (RuntimeException e) {
            log.warn("Redis release failed; the order scan will retry. orderNo={}", order.getOrderNo(), e);
            return false;
        }
    }

    /** Runs {@link #release} once the surrounding transaction has committed. */
    public void releaseAfterCommit(TradeOrder order) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                release(order);
            }
        });
    }
}
