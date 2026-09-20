package com.localdeals.trade.service;

import com.localdeals.platform.config.TrafficControlProperties;
import com.localdeals.trade.config.SeckillProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.localdeals.trade.service.SeckillAdmissionService.ACCEPTED;
import static com.localdeals.trade.service.SeckillAdmissionService.DUPLICATE;
import static com.localdeals.trade.service.SeckillAdmissionService.ENDED;
import static com.localdeals.trade.service.SeckillAdmissionService.IP_RATE_LIMITED;
import static com.localdeals.trade.service.SeckillAdmissionService.META_NOT_READY;
import static com.localdeals.trade.service.SeckillAdmissionService.NOT_STARTED;
import static com.localdeals.trade.service.SeckillAdmissionService.ORDER_ID_IN_USE;
import static com.localdeals.trade.service.SeckillAdmissionService.OUT_OF_STOCK;
import static com.localdeals.trade.service.SeckillAdmissionService.USER_RATE_LIMITED;
import static org.assertj.core.api.Assertions.assertThat;

/** The single admission script against a real Redis: one round trip decides everything. */
@SpringBootTest(classes = RedisAutoConfiguration.class)
@ActiveProfiles("test")
class SeckillAdmissionScriptIT {

    /**
     * One bucket: this test is about what the script decides, not about how the stock is split.
     * The bucket behaviour has its own test.
     */
    private static final SeckillBucketRouter ROUTER = new SeckillBucketRouter(1);
    private static final long VOUCHER = 77_301L;
    private static final long ORDER_BASE = 1L << 58;

    @Resource
    private StringRedisTemplate redis;

    private TrafficControlProperties traffic;
    private SeckillAdmissionService service;

    @BeforeEach
    void setUp() {
        cleanup();
        traffic = new TrafficControlProperties();
        traffic.getSeckill().setWindow(Duration.ofSeconds(10));
        traffic.getSeckill().setUserLimit(100);
        traffic.getSeckill().setIpLimit(100);
        service = new SeckillAdmissionService(redis, new SeckillProperties(), traffic, ROUTER);
        redis.opsForValue().set(ROUTER.stockKey(VOUCHER, 0), "2");
        activity("ACTIVE", -60, 600);
    }

    @AfterEach
    void cleanup() {
        Set<String> keys = redis.keys(ROUTER.trafficPrefix(VOUCHER, 0) + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        for (int i = 0; i < 20; i++) {
            redis.delete(ROUTER.statusKey(ORDER_BASE + i, 0));
        }
        redis.delete(java.util.List.of(ROUTER.stockKey(VOUCHER, 0), ROUTER.metaKey(VOUCHER, 0),
                ROUTER.reservationKey(VOUCHER, 0)));
        for (int i = 0; i < 20; i++) {
            redis.opsForZSet().remove(ROUTER.processingKey(0), Long.toString(ORDER_BASE + i));
        }
    }

    @Test
    void acceptedAdmissionReservesAndReportsTheRemainingStock() {
        SeckillAdmissionService.Admission first = service.admit(VOUCHER, 1L, ORDER_BASE + 1, "10.0.0.1");
        SeckillAdmissionService.Admission second = service.admit(VOUCHER, 2L, ORDER_BASE + 2, "10.0.0.1");
        SeckillAdmissionService.Admission third = service.admit(VOUCHER, 3L, ORDER_BASE + 3, "10.0.0.1");

        assertThat(first).isEqualTo(new SeckillAdmissionService.Admission(ACCEPTED, 1L));
        assertThat(second).isEqualTo(new SeckillAdmissionService.Admission(ACCEPTED, 0L));
        assertThat(third).isEqualTo(new SeckillAdmissionService.Admission(OUT_OF_STOCK, 0L));
        assertThat(redis.opsForHash().get(ROUTER.reservationKey(VOUCHER, 0), "1"))
                .isEqualTo(Long.toString(ORDER_BASE + 1));
        assertThat(redis.opsForHash().get(ROUTER.statusKey(ORDER_BASE + 1, 0), "status"))
                .isEqualTo("PROCESSING");
        assertThat(redis.opsForZSet().score(ROUTER.processingKey(0), Long.toString(ORDER_BASE + 1)))
                .isNotNull();
    }

    @Test
    void duplicateBuyerIsRejectedWithoutTakingStock() {
        service.admit(VOUCHER, 1L, ORDER_BASE + 1, "10.0.0.1");

        SeckillAdmissionService.Admission again = service.admit(VOUCHER, 1L, ORDER_BASE + 4, "10.0.0.1");

        assertThat(again.code()).isEqualTo(DUPLICATE);
        assertThat(redis.opsForValue().get(ROUTER.stockKey(VOUCHER, 0))).isEqualTo("1");
    }

    @Test
    void userAndIpLimitsRejectInsideTheSameScriptWithoutTakingStock() {
        traffic.getSeckill().setUserLimit(1);
        traffic.getSeckill().setIpLimit(2);

        assertThat(service.admit(VOUCHER, 1L, ORDER_BASE + 1, "10.0.0.1").code()).isEqualTo(ACCEPTED);
        assertThat(service.admit(VOUCHER, 1L, ORDER_BASE + 5, "10.0.0.2").code()).isEqualTo(USER_RATE_LIMITED);
        assertThat(service.admit(VOUCHER, 2L, ORDER_BASE + 6, "10.0.0.1").code()).isEqualTo(ACCEPTED);
        assertThat(service.admit(VOUCHER, 3L, ORDER_BASE + 7, "10.0.0.1").code()).isEqualTo(IP_RATE_LIMITED);

        assertThat(redis.opsForValue().get(ROUTER.stockKey(VOUCHER, 0))).isEqualTo("0");
        assertThat(redis.hasKey(ROUTER.statusKey(ORDER_BASE + 7, 0))).isFalse();
    }

    @Test
    void zeroLimitsDisableRateLimiting() {
        traffic.getSeckill().setEnabled(false);
        redis.opsForValue().set(ROUTER.stockKey(VOUCHER, 0), "50");

        for (int i = 0; i < 10; i++) {
            assertThat(service.admit(VOUCHER, 100L + i, ORDER_BASE + 10 + i, "10.0.0.1").code())
                    .isEqualTo(ACCEPTED);
        }
        assertThat(redis.keys("traffic:seckill:{" + VOUCHER + "}:*")).isEmpty();
    }

    @Test
    void anOrderIdAlreadyHoldingAStatusIsNeverOverwritten() {
        redis.opsForHash().put(ROUTER.statusKey(ORDER_BASE + 8, 0), "status", "PROCESSING");
        redis.opsForHash().put(ROUTER.statusKey(ORDER_BASE + 8, 0), "userId", "999");

        SeckillAdmissionService.Admission admission = service.admit(VOUCHER, 1L, ORDER_BASE + 8, "10.0.0.1");

        assertThat(admission.code()).isEqualTo(ORDER_ID_IN_USE);
        assertThat(redis.opsForValue().get(ROUTER.stockKey(VOUCHER, 0))).isEqualTo("2");
        assertThat(redis.opsForHash().hasKey(ROUTER.reservationKey(VOUCHER, 0), "1")).isFalse();
        assertThat(redis.opsForHash().get(ROUTER.statusKey(ORDER_BASE + 8, 0), "userId")).isEqualTo("999");
    }

    @Test
    void activityWindowAndMetadataAreCheckedBeforeStock() {
        activity("ACTIVE", 60, 600);
        assertThat(service.admit(VOUCHER, 1L, ORDER_BASE + 1, "10.0.0.1").code()).isEqualTo(NOT_STARTED);
        activity("ACTIVE", -600, -60);
        assertThat(service.admit(VOUCHER, 1L, ORDER_BASE + 1, "10.0.0.1").code()).isEqualTo(ENDED);
        activity("SUSPENDED", -60, 600);
        assertThat(service.admit(VOUCHER, 1L, ORDER_BASE + 1, "10.0.0.1").code()).isEqualTo(ENDED);
        redis.delete(ROUTER.metaKey(VOUCHER, 0));
        assertThat(service.admit(VOUCHER, 1L, ORDER_BASE + 1, "10.0.0.1").code()).isEqualTo(META_NOT_READY);
        assertThat(redis.opsForValue().get(ROUTER.stockKey(VOUCHER, 0))).isEqualTo("2");
    }

    private void activity(String status, long beginOffset, long endOffset) {
        long now = Instant.now().getEpochSecond();
        Map<String, String> meta = new HashMap<>();
        meta.put("status", status);
        meta.put("beginAt", Long.toString(now + beginOffset));
        meta.put("endAt", Long.toString(now + endOffset));
        redis.opsForHash().putAll(ROUTER.metaKey(VOUCHER, 0), meta);
    }
}
