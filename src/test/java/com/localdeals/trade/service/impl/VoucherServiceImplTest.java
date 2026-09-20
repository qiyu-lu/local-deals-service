package com.localdeals.trade.service.impl;

import com.localdeals.platform.testsupport.MybatisPlusMocks;
import com.localdeals.trade.entity.Voucher;
import com.localdeals.trade.mapper.VoucherMapper;
import com.localdeals.trade.service.ISeckillVoucherService;
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
        MybatisPlusMocks.injectMapper(service, voucherMapper, Voucher.class);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "seckillBucketRouter", ROUTER);

        TransactionSynchronizationManager.initSynchronization();
    }

    private static final com.localdeals.trade.service.SeckillBucketRouter ROUTER =
            new com.localdeals.trade.service.SeckillBucketRouter(4);

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

        // 25 units over four buckets, and the window replicated into each of them.
        long preheated = 0;
        for (int bucket = 0; bucket < ROUTER.count(); bucket++) {
            verify(valueOperations).set(ROUTER.stockKey(101L, bucket),
                    Long.toString(ROUTER.stockShare(25, bucket)));
            preheated += ROUTER.stockShare(25, bucket);
        }
        assertThat(preheated).isEqualTo(25L);
        ArgumentCaptor<Map<Object, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations, org.mockito.Mockito.times(ROUTER.count()))
                .putAll(org.mockito.ArgumentMatchers.startsWith("sk:{sk:b"), metadataCaptor.capture());
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
                .set(ROUTER.stockKey(102L, 0), Long.toString(ROUTER.stockShare(10, 0)));

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
