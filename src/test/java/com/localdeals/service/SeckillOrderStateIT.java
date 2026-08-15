package com.localdeals.service;

import com.localdeals.mq.SeckillOrderMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS;
import static com.localdeals.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {RedisAutoConfiguration.class, SeckillOrderStateIT.Config.class})
@ActiveProfiles("test")
class SeckillOrderStateIT {

    private static final Long VOUCHER_ID = 88991L;
    private static final Long USER_ID = 88001L;
    private static final Long ORDER_ID = 90071992547409931L;
    private static final Long SECOND_ORDER_ID = 90071992547409932L;

    private static final DefaultRedisScript<Long> ADMISSION_SCRIPT;

    static {
        ADMISSION_SCRIPT = new DefaultRedisScript<>();
        ADMISSION_SCRIPT.setLocation(new ClassPathResource("lua/seckill_check.lua"));
        ADMISSION_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillOrderStateService stateService;

    @BeforeEach
    void setUp() {
        cleanup();
        stringRedisTemplate.opsForValue().set(stockKey(), "2");
        setActivity(Instant.now().getEpochSecond() - 60, Instant.now().getEpochSecond() + 600);
    }

    @AfterEach
    void cleanup() {
        stringRedisTemplate.delete(Arrays.asList(
                stockKey(), legacyOrderKey(), metaKey(), reservationKey(),
                statusKey(ORDER_ID), statusKey(SECOND_ORDER_ID)));
    }

    @Test
    void admissionSuccessAndCompensationAreExactAndIdempotent() {
        assertThat(admit(USER_ID, ORDER_ID)).isZero();
        assertThat(stringRedisTemplate.opsForValue().get(stockKey())).isEqualTo("1");
        assertThat(stringRedisTemplate.opsForHash().get(reservationKey(), USER_ID.toString()))
                .isEqualTo(ORDER_ID.toString());
        assertThat(stringRedisTemplate.opsForHash().get(statusKey(ORDER_ID), "status"))
                .isEqualTo("PROCESSING");
        SeckillOrderMessage admitted = new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);
        assertThat(stateService.validateForConsumption(admitted))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.PROCESS);

        // A different order for the same user can never inherit the first reservation.
        assertThat(admit(USER_ID, SECOND_ORDER_ID)).isEqualTo(2L);
        assertThat(stringRedisTemplate.hasKey(statusKey(SECOND_ORDER_ID))).isFalse();

        SeckillOrderMessage message = new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);
        assertThat(stateService.compensate(message, "DB_STOCK_EXHAUSTED")).isTrue();
        assertThat(stateService.compensate(message, "DB_STOCK_EXHAUSTED")).isTrue();

        assertThat(stringRedisTemplate.opsForValue().get(stockKey())).isEqualTo("2");
        assertThat(stringRedisTemplate.opsForHash().hasKey(reservationKey(), USER_ID.toString())).isFalse();
        assertThat(stringRedisTemplate.opsForSet().isMember(legacyOrderKey(), USER_ID.toString())).isFalse();
        assertThat(stringRedisTemplate.opsForHash().get(statusKey(ORDER_ID), "status")).isEqualTo("FAILED");
        assertThat(stateService.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.ALREADY_FAILED);
    }

    @Test
    void successfulFinalizationCannotBeCompensated() {
        assertThat(admit(USER_ID, ORDER_ID)).isZero();
        SeckillOrderMessage message = new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);

        assertThat(stateService.markSuccess(message)).isTrue();
        assertThat(stateService.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.ALREADY_SUCCESS);
        assertThat(stateService.markSuccess(message)).isTrue();
        assertThat(stateService.compensate(message, "DB_STOCK_EXHAUSTED")).isFalse();

        assertThat(stringRedisTemplate.opsForValue().get(stockKey())).isEqualTo("1");
        assertThat(stringRedisTemplate.opsForHash().get(statusKey(ORDER_ID), "status")).isEqualTo("SUCCESS");
    }

    @Test
    void processingStatusWithWrongReservationIsPoisonedAndNeverEligibleForPersistence() {
        assertThat(admit(USER_ID, ORDER_ID)).isZero();
        stringRedisTemplate.opsForHash().put(
                reservationKey(), USER_ID.toString(), SECOND_ORDER_ID.toString());

        SeckillOrderMessage message = new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);
        assertThat(stateService.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.POISONED);

        stringRedisTemplate.opsForHash().put(
                reservationKey(), USER_ID.toString(), ORDER_ID.toString());
        stringRedisTemplate.opsForHash().put(statusKey(ORDER_ID), "userId", "999999");
        assertThat(stateService.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.POISONED);
    }

    @Test
    void absentOrderStatusIsRetryableAndNeverEligibleForPersistence() {
        SeckillOrderMessage message = new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);

        assertThat(stateService.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.RETRYABLE_STATE_MISSING);
    }

    @Test
    void missingFutureAndEndedMetadataFailClosedWithoutMutatingStock() {
        stringRedisTemplate.delete(metaKey());
        assertThat(admit(USER_ID, ORDER_ID)).isEqualTo(5L);

        long now = Instant.now().getEpochSecond();
        setActivity(now + 60, now + 120);
        assertThat(admit(USER_ID, ORDER_ID)).isEqualTo(3L);

        setActivity(now - 120, now - 60);
        assertThat(admit(USER_ID, ORDER_ID)).isEqualTo(4L);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey())).isEqualTo("2");
        assertThat(stringRedisTemplate.opsForHash().size(reservationKey())).isZero();
    }

    private long admit(Long userId, Long orderId) {
        Long result = stringRedisTemplate.execute(
                ADMISSION_SCRIPT,
                Arrays.asList(stockKey(), legacyOrderKey(), metaKey(), reservationKey(), statusKey(orderId)),
                userId.toString(), VOUCHER_ID.toString(), orderId.toString(),
                SECKILL_ORDER_STATUS_TTL_SECONDS.toString());
        return result == null ? -1L : result;
    }

    private void setActivity(long beginAt, long endAt) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "ACTIVE");
        metadata.put("beginAt", Long.toString(beginAt));
        metadata.put("endAt", Long.toString(endAt));
        stringRedisTemplate.opsForHash().putAll(metaKey(), metadata);
    }

    private String stockKey() {
        return SECKILL_STOCK_KEY + VOUCHER_ID;
    }

    private String legacyOrderKey() {
        return SECKILL_ORDER_KEY + VOUCHER_ID;
    }

    private String metaKey() {
        return SECKILL_META_KEY + VOUCHER_ID;
    }

    private String reservationKey() {
        return SECKILL_RESERVATION_KEY + VOUCHER_ID;
    }

    private String statusKey(Long orderId) {
        return SECKILL_ORDER_STATUS_KEY + orderId;
    }

    @TestConfiguration
    static class Config {
        @Bean
        SeckillOrderStateService seckillOrderStateService(StringRedisTemplate stringRedisTemplate) {
            return new SeckillOrderStateService(stringRedisTemplate);
        }
    }
}
