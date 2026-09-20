package com.localdeals.trade.service;

import com.localdeals.platform.config.TrafficControlProperties;
import com.localdeals.trade.config.SeckillProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.List;

import static com.localdeals.platform.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_PROCESSING_INDEX_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillAdmissionServiceTest {

    private static final long VOUCHER_ID = 17L;
    private static final long USER_ID = 23L;
    private static final long ORDER_ID = 9007199254740993L;

    private StringRedisTemplate redis;
    private TrafficControlProperties traffic;
    private SeckillAdmissionService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        traffic = new TrafficControlProperties();
        service = new SeckillAdmissionService(redis, new SeckillProperties(), traffic);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void oneScriptCarriesRateLimitsActivityDuplicateStockAndReservation() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Arrays.asList(0L, 99L));

        SeckillAdmissionService.Admission admission = service.admit(VOUCHER_ID, USER_ID, ORDER_ID, "203.0.113.9");

        assertThat(admission.code()).isEqualTo(SeckillAdmissionService.ACCEPTED);
        assertThat(admission.remainingStock()).isEqualTo(99L);
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        assertThat(keys.getValue()).hasSize(7);
        assertThat(keys.getValue().subList(0, 5)).containsExactly(
                SECKILL_STOCK_KEY + VOUCHER_ID,
                SECKILL_META_KEY + VOUCHER_ID,
                SECKILL_RESERVATION_KEY + VOUCHER_ID,
                SECKILL_ORDER_STATUS_KEY + ORDER_ID,
                SECKILL_PROCESSING_INDEX_KEY);
        assertThat((String) keys.getValue().get(5)).isEqualTo("traffic:seckill:{17}:user:23:");
        assertThat((String) keys.getValue().get(6))
                .startsWith("traffic:seckill:{17}:ip:")
                .doesNotContain("203.0.113.9")
                .hasSize("traffic:seckill:{17}:ip:".length() + 64 + 1);
        // The order id stays a string end to end; a Lua number would round it.
        assertThat(args.getAllValues()).containsExactly(
                "23", "17", "9007199254740993", "120", "1000", "2", "100");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void disabledTrafficControlPassesZeroLimits() {
        traffic.getSeckill().setEnabled(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Arrays.asList(1L, 0L));

        SeckillAdmissionService.Admission admission = service.admit(VOUCHER_ID, USER_ID, ORDER_ID, "203.0.113.9");

        assertThat(admission.code()).isEqualTo(SeckillAdmissionService.OUT_OF_STOCK);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(redis).execute(any(RedisScript.class), anyList(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        assertThat(args.getAllValues().subList(5, 7)).containsExactly("0", "0");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void malformedScriptReplyIsAnInfrastructureError() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null, Arrays.asList(0L));

        assertThatThrownBy(() -> service.admit(VOUCHER_ID, USER_ID, ORDER_ID, "203.0.113.9"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.admit(VOUCHER_ID, USER_ID, ORDER_ID, "203.0.113.9"))
                .isInstanceOf(IllegalStateException.class);
    }
}
