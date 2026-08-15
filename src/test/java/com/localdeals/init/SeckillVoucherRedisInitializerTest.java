package com.localdeals.init;

import com.localdeals.entity.SeckillVoucher;
import com.localdeals.service.ISeckillVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SeckillVoucherRedisInitializerTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private ISeckillVoucherService seckillVoucherService;
    private StringRedisTemplate stringRedisTemplate;
    private SeckillVoucherRedisInitializer initializer;

    @BeforeEach
    void setUp() {
        seckillVoucherService = mock(ISeckillVoucherService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        initializer = new SeckillVoucherRedisInitializer(
                seckillVoucherService, stringRedisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsAllRowsAndUsesShanghaiEpochValues() {
        LocalDateTime beginTime = LocalDateTime.of(2026, 8, 15, 10, 30);
        LocalDateTime endTime = LocalDateTime.of(2026, 8, 15, 12, 0);
        SeckillVoucher voucher = voucher(101L, 25, beginTime, endTime);
        when(seckillVoucherService.list()).thenReturn(Collections.singletonList(voucher));
        when(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(15L);

        assertThat(initializer.initializeFromDatabase()).isEqualTo(1);

        verify(seckillVoucherService).list();
        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(Arrays.asList(
                        SECKILL_STOCK_KEY + 101L,
                        SECKILL_META_KEY + 101L)),
                org.mockito.ArgumentMatchers.eq("25"),
                org.mockito.ArgumentMatchers.eq(Long.toString(
                        beginTime.atZone(BUSINESS_ZONE).toEpochSecond())),
                org.mockito.ArgumentMatchers.eq(Long.toString(
                        endTime.atZone(BUSINESS_ZONE).toEpochSecond()))
        );
    }

    @Test
    void rejectsEveryInvalidRowBeforeWritingRedis() {
        when(seckillVoucherService.list()).thenReturn(Arrays.asList(
                voucher(null, 2,
                        LocalDateTime.of(2026, 8, 15, 10, 0),
                        LocalDateTime.of(2026, 8, 15, 11, 0)),
                voucher(102L, -1,
                        LocalDateTime.of(2026, 8, 15, 12, 0),
                        LocalDateTime.of(2026, 8, 15, 11, 0)),
                null
        ));

        assertThatThrownBy(initializer::initializeFromDatabase)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 invalid seckill voucher record");
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void databaseFailureFailsFast() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(seckillVoucherService.list()).thenThrow(failure);

        assertThatThrownBy(initializer::initializeFromDatabase).isSameAs(failure);
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureFailsFast() {
        SeckillVoucher voucher = voucher(
                103L,
                8,
                LocalDateTime.of(2026, 8, 15, 10, 0),
                LocalDateTime.of(2026, 8, 15, 11, 0));
        when(seckillVoucherService.list()).thenReturn(Collections.singletonList(voucher));
        RedisConnectionFailureException failure =
                new RedisConnectionFailureException("redis unavailable");
        when(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(initializer::initializeFromDatabase).isSameAs(failure);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullScriptResultFailsFast() {
        SeckillVoucher voucher = voucher(
                104L,
                8,
                LocalDateTime.of(2026, 8, 15, 10, 0),
                LocalDateTime.of(2026, 8, 15, 11, 0));
        when(seckillVoucherService.list()).thenReturn(Collections.singletonList(voucher));
        when(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(null);

        assertThatThrownBy(initializer::initializeFromDatabase)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("voucher 104");
        verify(stringRedisTemplate, never()).delete(any(String.class));
    }

    private SeckillVoucher voucher(Long id,
                                    Integer stock,
                                    LocalDateTime beginTime,
                                    LocalDateTime endTime) {
        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(id);
        voucher.setStock(stock);
        voucher.setBeginTime(beginTime);
        voucher.setEndTime(endTime);
        return voucher;
    }
}
