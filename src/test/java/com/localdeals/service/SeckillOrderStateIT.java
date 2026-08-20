package com.localdeals.service;

import com.localdeals.config.SeckillProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_TTL_SECONDS;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_INDEX_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_QUARANTINE_REASON_KEY;
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
        stringRedisTemplate.opsForZSet().remove(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString(), SECOND_ORDER_ID.toString(),
                "malformed-id", "01", "+1", "", " ");
        stringRedisTemplate.opsForZSet().remove(
                SECKILL_PROCESSING_QUARANTINE_KEY, ORDER_ID.toString(), SECOND_ORDER_ID.toString(),
                "malformed-id", "01", "+1", "", " ");
        stringRedisTemplate.opsForHash().delete(
                SECKILL_PROCESSING_QUARANTINE_REASON_KEY,
                ORDER_ID.toString(), SECOND_ORDER_ID.toString(),
                "malformed-id", "01", "+1", "", " ");
    }

    @Test
    void admissionSuccessAndCompensationAreExactAndIdempotent() {
        assertThat(admit(USER_ID, ORDER_ID)).isZero();
        assertThat(stringRedisTemplate.opsForValue().get(stockKey())).isEqualTo("1");
        assertThat(stringRedisTemplate.opsForHash().get(reservationKey(), USER_ID.toString()))
                .isEqualTo(ORDER_ID.toString());
        assertThat(stringRedisTemplate.opsForHash().get(statusKey(ORDER_ID), "status"))
                .isEqualTo("PROCESSING");
        assertThat(stringRedisTemplate.getExpire(statusKey(ORDER_ID))).isEqualTo(-1L);
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString())).isNotNull();
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
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString())).isNull();
        assertThat(stringRedisTemplate.getExpire(statusKey(ORDER_ID))).isPositive();
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
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString())).isNull();
        assertThat(stringRedisTemplate.getExpire(statusKey(ORDER_ID))).isPositive();
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
    void quarantinedProcessingReservationIsPoisonedForLateConsumerDelivery() {
        assertThat(admit(USER_ID, ORDER_ID)).isZero();
        SeckillOrderMessage message = new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);

        assertThat(stateService.quarantineProcessingOrder(ORDER_ID, "ORDER_ID_CONFLICT")).isTrue();
        assertThat(stateService.validateForConsumption(message))
                .isEqualTo(SeckillOrderStateService.ReservationDecision.POISONED);
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString())).isNull();
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_QUARANTINE_KEY, ORDER_ID.toString())).isNotNull();
        assertThat(stateService.backfillProcessingOrder(message))
                .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.QUARANTINED);
        assertThat(stateService.claimForReconciliation(message).getDecision())
                .isEqualTo(SeckillOrderStateService.ReconciliationClaimDecision.QUARANTINED);
        assertThat(stateService.compensate(message, "PROCESSING_TIMEOUT")).isFalse();
        assertThat(stringRedisTemplate.opsForValue().get(stockKey())).isEqualTo("1");
        assertThat(stringRedisTemplate.opsForHash().get(reservationKey(), USER_ID.toString()))
                .isEqualTo(ORDER_ID.toString());
        assertThat(stringRedisTemplate.opsForHash().get(statusKey(ORDER_ID), "status"))
                .isEqualTo("PROCESSING");
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString())).isNull();
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

    @Test
    void dueClaimUsesRedisTimeAndMovesScoreWithoutExpiringProcessingState() {
        assertThat(admit(USER_ID, ORDER_ID)).isZero();
        stringRedisTemplate.opsForZSet().add(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString(), Instant.now().getEpochSecond() - 1);

        assertThat(stateService.findDueOrderIds(10)).contains(ORDER_ID);
        SeckillOrderStateService.ReconciliationClaim claim = stateService.claimForReconciliation(
                new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID));

        assertThat(claim.getDecision())
                .isEqualTo(SeckillOrderStateService.ReconciliationClaimDecision.CLAIMED);
        assertThat(claim.getRedisNow()).isPositive();
        assertThat(claim.getCreatedAt()).isPositive();
        assertThat(claim.getReconcileAttempts()).isEqualTo(1L);
        assertThat(stringRedisTemplate.opsForHash().get(statusKey(ORDER_ID), "reconcileAttempts"))
                .isEqualTo("1");
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString()))
                .isGreaterThanOrEqualTo((double) claim.getRedisNow() + 60D);
        assertThat(stringRedisTemplate.getExpire(statusKey(ORDER_ID))).isEqualTo(-1L);
        assertThat(stateService.findDueOrderIds(10)).doesNotContain(ORDER_ID);
    }

    @Test
    void exactLegacyProcessingBackfillPersistsStatusAndDoesNotOverwriteExistingScore() {
        assertThat(admit(USER_ID, ORDER_ID)).isZero();
        stringRedisTemplate.opsForZSet().remove(SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString());
        stringRedisTemplate.expire(statusKey(ORDER_ID), 30, java.util.concurrent.TimeUnit.SECONDS);

        SeckillOrderMessage message = new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);
        assertThat(stateService.backfillProcessingOrder(message))
                .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.INDEXED);
        assertThat(stringRedisTemplate.getExpire(statusKey(ORDER_ID))).isEqualTo(-1L);
        Double firstScore = stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString());
        assertThat(firstScore).isNotNull();

        assertThat(stateService.backfillProcessingOrder(message))
                .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.ALREADY_INDEXED);
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString())).isEqualTo(firstScore);
    }

    @Test
    void malformedDueMemberIsQuarantinedWithoutBlockingValidMember() {
        long dueAt = Instant.now().getEpochSecond() - 1;
        for (String malformed : Arrays.asList("malformed-id", "01", "+1", "", " ")) {
            stringRedisTemplate.opsForZSet().add(SECKILL_PROCESSING_INDEX_KEY, malformed, dueAt);
        }
        stringRedisTemplate.opsForZSet().add(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString(), dueAt);

        assertThat(stateService.findDueOrderIds(10)).containsExactly(ORDER_ID);
        for (String malformed : Arrays.asList("malformed-id", "01", "+1", "", " ")) {
            assertThat(stringRedisTemplate.opsForZSet().score(
                    SECKILL_PROCESSING_INDEX_KEY, malformed)).isNull();
            assertThat(stringRedisTemplate.opsForZSet().score(
                    SECKILL_PROCESSING_QUARANTINE_KEY, malformed)).isNotNull();
            assertThat(stringRedisTemplate.opsForHash().get(
                    SECKILL_PROCESSING_QUARANTINE_REASON_KEY, malformed))
                    .isEqualTo("INVALID_PROCESSING_INDEX_MEMBER");
        }
    }

    @Test
    void malformedAttemptCountersReturnStateInvalidInsteadOfBreakingClaimParsing() {
        assertThat(admit(USER_ID, ORDER_ID)).isZero();
        stringRedisTemplate.opsForZSet().add(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString(), Instant.now().getEpochSecond() - 1);

        for (String malformed : Arrays.asList(
                "not-a-number", "1.5", "1e3", "9223372036854775807")) {
            stringRedisTemplate.opsForHash().put(
                    statusKey(ORDER_ID), "reconcileAttempts", malformed);
            SeckillOrderStateService.ReconciliationClaim claim = stateService.claimForReconciliation(
                    new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID));

            assertThat(claim.getDecision())
                    .as("counter %s", malformed)
                    .isEqualTo(SeckillOrderStateService.ReconciliationClaimDecision.STATE_INVALID);
            assertThat(claim.getReconcileAttempts()).isZero();
        }
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString())).isNotNull();
    }

    @Test
    void malformedCreatedAtCannotDriveClaimOrBackfillDeadlines() {
        assertThat(admit(USER_ID, ORDER_ID)).isZero();
        stringRedisTemplate.opsForZSet().add(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString(), Instant.now().getEpochSecond() - 1);
        SeckillOrderMessage message = new SeckillOrderMessage(VOUCHER_ID, USER_ID, ORDER_ID);

        for (String malformed : Arrays.asList(
                "01", "1.5", "1e3", "-1", "9223372036854775807", "9999999999")) {
            stringRedisTemplate.opsForHash().put(statusKey(ORDER_ID), "createdAt", malformed);

            assertThat(stateService.claimForReconciliation(message).getDecision())
                    .as("claim createdAt %s", malformed)
                    .isEqualTo(SeckillOrderStateService.ReconciliationClaimDecision.STATE_INVALID);
            assertThat(stateService.backfillProcessingOrder(message))
                    .as("backfill createdAt %s", malformed)
                    .isEqualTo(SeckillOrderStateService.ProcessingBackfillDecision.STATE_INVALID);
        }
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString())).isNotNull();
        assertThat(stringRedisTemplate.opsForHash().get(reservationKey(), USER_ID.toString()))
                .isEqualTo(ORDER_ID.toString());
        assertThat(stringRedisTemplate.opsForValue().get(stockKey())).isEqualTo("1");
    }

    @Test
    void unresolvedCanonicalMemberIsDeferredWithoutBusinessStateMutation() {
        long dueAt = Instant.now().getEpochSecond() - 1;
        // A terminal-looking field without exact ownership is not sufficient evidence to drop
        // the durable index member.
        stringRedisTemplate.opsForHash().put(statusKey(ORDER_ID), "status", "SUCCESS");
        stringRedisTemplate.opsForZSet().add(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString(), dueAt);

        assertThat(stateService.find(ORDER_ID)).isNull();
        assertThat(stateService.findDueOrderIds(10)).contains(ORDER_ID);
        assertThat(stateService.deferProcessingOrder(ORDER_ID)).isTrue();

        assertThat(stateService.findDueOrderIds(10)).doesNotContain(ORDER_ID);
        assertThat(stringRedisTemplate.opsForZSet().score(
                SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString()))
                .isGreaterThan((double) dueAt);
        assertThat(stringRedisTemplate.opsForHash().get(statusKey(ORDER_ID), "status"))
                .isEqualTo("SUCCESS");
        assertThat(stringRedisTemplate.opsForHash().hasKey(statusKey(ORDER_ID), "orderId")).isFalse();
        assertThat(stringRedisTemplate.opsForHash().size(reservationKey())).isZero();
        assertThat(stringRedisTemplate.opsForValue().get(stockKey())).isEqualTo("2");
    }

    @Test
    void fullBatchOfUnresolvedMembersCannotStarveTheNextValidDueOrder() {
        long now = Instant.now().getEpochSecond();
        List<String> unresolvedMembers = new ArrayList<>();
        try {
            for (long offset = 0; offset < 100; offset++) {
                String member = Long.toString(90071992547411000L + offset);
                unresolvedMembers.add(member);
                stringRedisTemplate.opsForZSet().add(
                        SECKILL_PROCESSING_INDEX_KEY, member, now - 2);
            }
            assertThat(admit(USER_ID, ORDER_ID)).isZero();
            stringRedisTemplate.opsForZSet().add(
                    SECKILL_PROCESSING_INDEX_KEY, ORDER_ID.toString(), now - 1);

            List<Long> firstBatch = stateService.findDueOrderIds(100);
            assertThat(firstBatch).hasSize(100).doesNotContain(ORDER_ID);
            firstBatch.forEach(orderId ->
                    assertThat(stateService.deferProcessingOrder(orderId)).isTrue());

            assertThat(stateService.findDueOrderIds(100)).contains(ORDER_ID);
        } finally {
            stringRedisTemplate.opsForZSet().remove(
                    SECKILL_PROCESSING_INDEX_KEY, unresolvedMembers.toArray());
        }
    }

    private long admit(Long userId, Long orderId) {
        Long result = stringRedisTemplate.execute(
                ADMISSION_SCRIPT,
                Arrays.asList(stockKey(), legacyOrderKey(), metaKey(), reservationKey(),
                        statusKey(orderId), SECKILL_PROCESSING_INDEX_KEY),
                userId.toString(), VOUCHER_ID.toString(), orderId.toString(),
                "120");
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
        SeckillProperties seckillProperties() {
            return new SeckillProperties();
        }

        @Bean
        SeckillOrderStateService seckillOrderStateService(StringRedisTemplate stringRedisTemplate,
                                                          SeckillProperties seckillProperties) {
            return new SeckillOrderStateService(stringRedisTemplate, seckillProperties);
        }
    }
}
