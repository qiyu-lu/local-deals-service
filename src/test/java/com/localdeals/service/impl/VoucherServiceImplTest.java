package com.localdeals.service.impl;

import com.localdeals.entity.Voucher;
import com.localdeals.mapper.VoucherMapper;
import com.localdeals.service.ISeckillVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoucherServiceImplTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private VoucherServiceImpl service;
    private ISeckillVoucherService seckillVoucherService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private HashOperations<String, Object, Object> hashOperations;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        VoucherMapper voucherMapper = mock(VoucherMapper.class);
        seckillVoucherService = mock(ISeckillVoucherService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        hashOperations = mock(HashOperations.class);

        when(voucherMapper.insert(any(Voucher.class))).thenReturn(1);
        when(seckillVoucherService.save(any())).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        service = new VoucherServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", voucherMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);

        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void defersStockAndMetadataPreheatUntilTransactionCommit() {
        LocalDateTime beginTime = LocalDateTime.of(2026, 8, 15, 10, 30);
        LocalDateTime endTime = LocalDateTime.of(2026, 8, 15, 12, 0);
        Voucher voucher = voucher(101L, 25, beginTime, endTime);

        service.addSeckillVoucher(voucher);

        verify(valueOperations, never()).set(any(), any());
        verify(hashOperations, never()).putAll(any(), anyMap());

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.get(0).afterCommit();

        verify(valueOperations).set(SECKILL_STOCK_KEY + 101L, "25");
        ArgumentCaptor<Map<Object, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(org.mockito.ArgumentMatchers.eq(SECKILL_META_KEY + 101L),
                metadataCaptor.capture());
        assertThat(metadataCaptor.getValue()).containsOnly(
                org.assertj.core.api.Assertions.entry("status", "ACTIVE"),
                org.assertj.core.api.Assertions.entry("beginAt",
                        Long.toString(beginTime.atZone(BUSINESS_ZONE).toEpochSecond())),
                org.assertj.core.api.Assertions.entry("endAt",
                        Long.toString(endTime.atZone(BUSINESS_ZONE).toEpochSecond()))
        );
    }

    @Test
    void redisFailureAfterCommitDoesNotEscapeTheCallback() {
        Voucher voucher = voucher(
                102L,
                10,
                LocalDateTime.of(2026, 8, 15, 10, 30),
                LocalDateTime.of(2026, 8, 15, 12, 0)
        );
        doThrow(new IllegalStateException("redis unavailable"))
                .when(valueOperations)
                .set(SECKILL_STOCK_KEY + 102L, "10");

        service.addSeckillVoucher(voucher);
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().get(0);

        assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
    }

    @Test
    void failedSeckillRowInsertDoesNotRegisterRedisPreheat() {
        Voucher voucher = voucher(
                103L,
                10,
                LocalDateTime.of(2026, 8, 15, 10, 30),
                LocalDateTime.of(2026, 8, 15, 12, 0)
        );
        when(seckillVoucherService.save(any())).thenReturn(false);

        assertThatThrownBy(() -> service.addSeckillVoucher(voucher))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("库存记录保存失败");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(valueOperations, never()).set(any(), any());
        verify(hashOperations, never()).putAll(any(), anyMap());
    }

    private Voucher voucher(Long id, Integer stock, LocalDateTime beginTime, LocalDateTime endTime) {
        Voucher voucher = new Voucher();
        voucher.setId(id);
        voucher.setStock(stock);
        voucher.setBeginTime(beginTime);
        voucher.setEndTime(endTime);
        return voucher;
    }
}
