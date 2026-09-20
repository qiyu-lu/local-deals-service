package com.localdeals.trade.init;

import com.localdeals.trade.entity.SeckillVoucher;
import com.localdeals.trade.service.ISeckillVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.localdeals.trade.service.SeckillBucketRouter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class SeckillVoucherRedisInitializerIT {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final SeckillBucketRouter ROUTER = new SeckillBucketRouter(4);
    private static final Long EMPTY_VOUCHER_ID = 88971L;
    private static final Long LIVE_VOUCHER_ID = 88972L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanup() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Long voucherId : Arrays.asList(EMPTY_VOUCHER_ID, LIVE_VOUCHER_ID)) {
            for (int bucket = 0; bucket < ROUTER.count(); bucket++) {
                keys.add(ROUTER.stockKey(voucherId, bucket));
                keys.add(ROUTER.metaKey(voucherId, bucket));
            }
        }
        stringRedisTemplate.delete(keys);
    }

    @Test
    void initializesMissingFieldsWithoutOverwritingLiveState() {
        cleanup();
        LocalDateTime emptyBegin = LocalDateTime.of(2026, 8, 15, 10, 0);
        LocalDateTime emptyEnd = LocalDateTime.of(2026, 8, 15, 11, 0);
        LocalDateTime liveBegin = LocalDateTime.of(2026, 8, 16, 10, 0);
        LocalDateTime liveEnd = LocalDateTime.of(2026, 8, 16, 11, 0);

        stringRedisTemplate.opsForValue().set(stockKey(LIVE_VOUCHER_ID), "999");
        Map<String, String> liveMetadata = new HashMap<>();
        liveMetadata.put("status", "SUSPENDED");
        liveMetadata.put("endAt", "123456789");
        stringRedisTemplate.opsForHash().putAll(metaKey(LIVE_VOUCHER_ID), liveMetadata);

        ISeckillVoucherService service = mock(ISeckillVoucherService.class);
        when(service.list()).thenReturn(Arrays.asList(
                voucher(EMPTY_VOUCHER_ID, 25, emptyBegin, emptyEnd),
                voucher(LIVE_VOUCHER_ID, 50, liveBegin, liveEnd)
        ));
        SeckillVoucherRedisInitializer initializer =
                new SeckillVoucherRedisInitializer(service, stringRedisTemplate, ROUTER);

        assertThat(initializer.initializeFromDatabase()).isEqualTo(2);

        // The 25 units are split over the buckets and add up again.
        long total = 0;
        for (int bucket = 0; bucket < ROUTER.count(); bucket++) {
            total += Long.parseLong(
                    stringRedisTemplate.opsForValue().get(ROUTER.stockKey(EMPTY_VOUCHER_ID, bucket)));
        }
        assertThat(total).isEqualTo(25L);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(EMPTY_VOUCHER_ID)))
                .isEqualTo(Long.toString(ROUTER.stockShare(25, 0)));
        assertThat(stringRedisTemplate.opsForHash().entries(metaKey(EMPTY_VOUCHER_ID)))
                .containsOnly(
                        org.assertj.core.api.Assertions.entry("status", "ACTIVE"),
                        org.assertj.core.api.Assertions.entry("beginAt", epoch(emptyBegin)),
                        org.assertj.core.api.Assertions.entry("endAt", epoch(emptyEnd))
                );

        assertThat(stringRedisTemplate.opsForValue().get(stockKey(LIVE_VOUCHER_ID)))
                .isEqualTo("999");
        assertThat(stringRedisTemplate.opsForHash().entries(metaKey(LIVE_VOUCHER_ID)))
                .containsOnly(
                        org.assertj.core.api.Assertions.entry("status", "SUSPENDED"),
                        org.assertj.core.api.Assertions.entry("beginAt", epoch(liveBegin)),
                        org.assertj.core.api.Assertions.entry("endAt", "123456789")
                );

        // A second pass must not replace any value, even if the database values changed.
        when(service.list()).thenReturn(Collections.singletonList(
                voucher(LIVE_VOUCHER_ID, 1, liveBegin.plusDays(3), liveEnd.plusDays(3))));
        assertThat(initializer.initializeFromDatabase()).isZero();
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(LIVE_VOUCHER_ID)))
                .isEqualTo("999");
        assertThat(stringRedisTemplate.opsForHash().entries(metaKey(LIVE_VOUCHER_ID)))
                .containsOnly(
                        org.assertj.core.api.Assertions.entry("status", "SUSPENDED"),
                        org.assertj.core.api.Assertions.entry("beginAt", epoch(liveBegin)),
                        org.assertj.core.api.Assertions.entry("endAt", "123456789")
                );
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

    private String epoch(LocalDateTime time) {
        return Long.toString(time.atZone(BUSINESS_ZONE).toEpochSecond());
    }

    /** Bucket 0 stands for the rest: the backfill writes every bucket the same way. */
    private String stockKey(Long voucherId) {
        return ROUTER.stockKey(voucherId, 0);
    }

    private String metaKey(Long voucherId) {
        return ROUTER.metaKey(voucherId, 0);
    }
}
