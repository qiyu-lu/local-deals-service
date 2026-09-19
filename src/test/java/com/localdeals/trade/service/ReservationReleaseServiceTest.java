package com.localdeals.trade.service;

import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.TradeOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReservationReleaseServiceTest {

    private StringRedisTemplate redis;
    private SeckillSoldOutRegistry soldOut;
    private ReservationReleaseService service;
    private final TradeOrder order = new TradeOrder();

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        soldOut = mock(SeckillSoldOutRegistry.class);
        service = new ReservationReleaseService(redis, mock(TradeOrderMapper.class),
                mock(ISeckillVoucherService.class), soldOut);
        order.setOrderNo(1L << 58);
        order.setUserId(23L);
        order.setVoucherId(17L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReturnedUnitClearsTheSoldOutFlagEverywhere() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertThat(service.release(order)).isTrue();

        verify(soldOut).clear(17L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReleaseThatFoundNothingDoesNotBroadcast() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);

        assertThat(service.release(order)).isTrue();

        verifyNoInteractions(soldOut);
    }
}
