package com.localdeals.trade.service;

import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.TradeOrderMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Gives a closed or refunded order's unit back to the Redis admission layer and lets the user
 * buy again. The DB stock was already restored in the transition's transaction; this is the
 * after-commit half, tracked by {@code trade_order.release_pending}.
 */
@Service
public class ReservationReleaseService {

    private final StringRedisTemplate stringRedisTemplate;
    private final TradeOrderMapper tradeOrderMapper;

    public ReservationReleaseService(StringRedisTemplate stringRedisTemplate, TradeOrderMapper tradeOrderMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.tradeOrderMapper = tradeOrderMapper;
    }

    /** @return true when Redis is released (now or earlier) and the pending flag is cleared. */
    public boolean release(TradeOrder order) {
        throw new UnsupportedOperationException("M2: not implemented yet");
    }
}
