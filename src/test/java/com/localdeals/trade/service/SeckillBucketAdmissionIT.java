package com.localdeals.trade.service;

import com.localdeals.platform.config.TrafficControlProperties;
import com.localdeals.trade.config.SeckillProperties;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static com.localdeals.trade.service.SeckillAdmissionService.ACCEPTED;
import static com.localdeals.trade.service.SeckillAdmissionService.OUT_OF_STOCK;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What splitting a voucher's stock buys and what it costs, against a real Redis.
 *
 * <p>Buys: the buyers of one bucket never touch another bucket's keys, so the hot row of M4
 * becomes K rows. Costs: a buyer whose own bucket is empty is refused while the voucher still
 * has stock elsewhere. M5 ships the simple version without borrowing from a neighbour, so that
 * waste is measured here and quantified in the benchmark.</p>
 */
@SpringBootTest(classes = RedisAutoConfiguration.class)
@ActiveProfiles("test")
class SeckillBucketAdmissionIT {

    private static final SeckillBucketRouter ROUTER = new SeckillBucketRouter(4);
    private static final long VOUCHER = 77_401L;
    private static final long STOCK = 8L;
    private static final long ORDER_BASE = 1L << 57;

    @Resource
    private StringRedisTemplate redis;

    private SeckillAdmissionService service;

    @BeforeEach
    void setUp() {
        cleanup();
        TrafficControlProperties traffic = new TrafficControlProperties();
        traffic.getSeckill().setEnabled(false);
        service = new SeckillAdmissionService(redis, new SeckillProperties(), traffic, ROUTER);
        long now = Instant.now().getEpochSecond();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "ACTIVE");
        metadata.put("beginAt", Long.toString(now - 60));
        metadata.put("endAt", Long.toString(now + 600));
        for (int bucket = 0; bucket < ROUTER.count(); bucket++) {
            redis.opsForValue().set(ROUTER.stockKey(VOUCHER, bucket),
                    Long.toString(ROUTER.stockShare(STOCK, bucket)));
            redis.opsForHash().putAll(ROUTER.metaKey(VOUCHER, bucket), metadata);
        }
    }

    @AfterEach
    void cleanup() {
        for (int bucket = 0; bucket < ROUTER.count(); bucket++) {
            redis.delete(java.util.List.of(
                    ROUTER.stockKey(VOUCHER, bucket),
                    ROUTER.metaKey(VOUCHER, bucket),
                    ROUTER.reservationKey(VOUCHER, bucket),
                    ROUTER.processingKey(bucket)));
        }
        for (long user = 0; user < 64; user++) {
            redis.delete(ROUTER.statusKeyOfOrder(order(user)));
        }
    }

    @Test
    void demandSpreadOverTheBucketsSellsTheWholeVoucher() {
        int accepted = 0;
        // Two buyers per bucket, and the shares are two each.
        for (long user = 0; user < STOCK; user++) {
            if (service.admit(VOUCHER, user, order(user), "10.0.0.1").code() == ACCEPTED) {
                accepted++;
            }
        }

        assertThat(accepted).isEqualTo((int) STOCK);
        assertThat(remainingStock()).isZero();
        // The next buyer of any bucket is out of stock.
        assertThat(service.admit(VOUCHER, 40L, order(40L), "10.0.0.1").code()).isEqualTo(OUT_OF_STOCK);
    }

    @Test
    void aBuyerWhoseBucketIsEmptyIsRefusedWhileTheVoucherStillHasStock() {
        // Three buyers, all of bucket 0, which holds two units.
        assertThat(service.admit(VOUCHER, 0L, order(0L), "10.0.0.1").code()).isEqualTo(ACCEPTED);
        assertThat(service.admit(VOUCHER, 4L, order(4L), "10.0.0.1").code()).isEqualTo(ACCEPTED);

        SeckillAdmissionService.Admission third = service.admit(VOUCHER, 8L, order(8L), "10.0.0.1");

        assertThat(third.code()).isEqualTo(OUT_OF_STOCK);
        // This is the cost of not borrowing: six units are still there, in other buckets.
        assertThat(remainingStock()).isEqualTo(6L);
        assertThat(service.admit(VOUCHER, 1L, order(1L), "10.0.0.1").code()).isEqualTo(ACCEPTED);
    }

    @Test
    void oneBuyerOnlyEverTouchesTheirOwnBucket() {
        service.admit(VOUCHER, 5L, order(5L), "10.0.0.1");

        int bucket = ROUTER.bucketOfUser(5L);
        assertThat(redis.opsForHash().get(ROUTER.reservationKey(VOUCHER, bucket), "5"))
                .isEqualTo(Long.toString(order(5L)));
        assertThat(redis.opsForZSet().score(ROUTER.processingKey(bucket),
                Long.toString(order(5L)))).isNotNull();
        for (int other = 0; other < ROUTER.count(); other++) {
            if (other == bucket) {
                continue;
            }
            assertThat(redis.opsForHash().entries(ROUTER.reservationKey(VOUCHER, other))).isEmpty();
            assertThat(redis.opsForZSet().size(ROUTER.processingKey(other))).isZero();
            assertThat(redis.opsForValue().get(ROUTER.stockKey(VOUCHER, other)))
                    .isEqualTo(Long.toString(ROUTER.stockShare(STOCK, other)));
        }
    }

    private long remainingStock() {
        long remaining = 0;
        for (int bucket = 0; bucket < ROUTER.count(); bucket++) {
            remaining += Long.parseLong(redis.opsForValue().get(ROUTER.stockKey(VOUCHER, bucket)));
        }
        return remaining;
    }

    /** An order number of this buyer: distinct per user and carrying their gene. */
    private static long order(long userId) {
        return ORDER_BASE + userId * 1024L + Math.floorMod(userId, 1024L);
    }
}
