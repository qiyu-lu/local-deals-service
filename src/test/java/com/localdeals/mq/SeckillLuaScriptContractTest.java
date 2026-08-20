package com.localdeals.mq;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure resource-contract tests for the Lua scripts. These tests do not start Spring or Redis.
 */
class SeckillLuaScriptContractTest {

    @Test
    void admissionScript_checksActivityBeforeMutatingAndWritesExactProcessingReservation()
            throws IOException {
        String script = readScript("lua/seckill_check.lua");

        assertThat(script).contains("redis.call('TIME')");
        assertThat(script).contains("'status', 'beginAt', 'endAt'");
        assertThat(script).contains("activityStatus ~= 'ACTIVE'");
        assertThat(script).contains("now < beginAt");
        assertThat(script).contains("now > endAt");
        assertThat(script).contains("redis.call('SADD', legacyOrderKey, userId)");
        assertThat(script).contains("redis.call('HSET', reservationKey, userId, orderId)");
        assertThat(script).contains("'status', 'PROCESSING'");
        assertThat(script).contains("'orderId', orderId");
        assertThat(script).contains("'userId', userId");
        assertThat(script).contains("'voucherId', voucherId");
        assertThat(script).contains("'reason', ''");
        assertThat(script).contains("'reconcileAttempts', '0'");
        assertThat(script).contains("redis.call('PERSIST', orderStatusKey)");
        assertThat(script).contains("redis.call('ZADD', processingIndexKey, now + staleAfterSeconds, orderId)");
        assertThat(script).doesNotContain("tonumber(ARGV[3])");
        assertThat(script).doesNotContain("redis.call('EXPIRE', orderStatusKey");

        int metadataRead = script.indexOf("redis.call('HMGET', metaKey");
        int stockDecrement = script.indexOf("redis.call('DECR', stockKey)");
        assertThat(metadataRead).isGreaterThanOrEqualTo(0);
        assertThat(stockDecrement).isGreaterThan(metadataRead);

        assertThat(script).contains("return 0", "return 1", "return 2", "return 3", "return 4", "return 5");
    }

    @Test
    void compensationScript_guardsExactProcessingOrderBeforeSingleStockRestore()
            throws IOException {
        String script = readScript("lua/seckill_compensate.lua");

        int statusRead = script.indexOf("redis.call('HMGET', orderStatusKey");
        int quarantineGuard = script.indexOf("redis.call('ZSCORE', quarantineKey, orderId)");
        int reservationRead = script.indexOf("redis.call('HGET', reservationKey, userId)");
        int exactReservationGuard = script.indexOf("reservedOrderId ~= orderId");
        int processingGuard = script.indexOf("statusData[1] ~= 'PROCESSING'");
        int stockRestore = script.indexOf("redis.call('INCR', stockKey)");
        int reservationDelete = script.indexOf("redis.call('HDEL', reservationKey, userId)");
        int failedTransition = script.indexOf("'status', 'FAILED'");

        assertThat(quarantineGuard).isGreaterThanOrEqualTo(0);
        assertThat(statusRead).isGreaterThan(quarantineGuard);
        assertThat(reservationRead).isGreaterThan(statusRead);
        assertThat(exactReservationGuard).isGreaterThan(reservationRead);
        assertThat(processingGuard).isGreaterThan(exactReservationGuard);
        assertThat(stockRestore).isGreaterThan(processingGuard);
        assertThat(reservationDelete).isGreaterThan(stockRestore);
        assertThat(failedTransition).isGreaterThan(reservationDelete);
        assertThat(script).contains("statusData[2] ~= orderId");
        assertThat(script).contains("statusData[3] ~= userId");
        assertThat(script).contains("statusData[4] ~= voucherId");
        assertThat(script).contains("redis.call('SREM', legacyOrderKey, userId)");
        assertThat(script).contains("redis.call('ZREM', processingIndexKey, orderId)");
        assertThat(script).contains("return 2");
        assertThat(script).contains("return 1");
    }

    @Test
    void consumerValidationScript_isReadOnlyAndRequiresExactProcessingReservation()
            throws IOException {
        String script = readScript("lua/seckill_validate_reservation.lua");

        assertThat(script).contains("redis.call('HMGET', statusKey");
        assertThat(script).contains("redis.call('ZSCORE', quarantineKey, orderId)");
        assertThat(script).contains("state[2] ~= orderId");
        assertThat(script).contains("state[3] ~= userId");
        assertThat(script).contains("state[4] ~= voucherId");
        assertThat(script).contains("state[1] == 'SUCCESS'");
        assertThat(script).contains("state[1] == 'FAILED'");
        assertThat(script).contains("state[1] ~= 'PROCESSING'");
        assertThat(script).contains("redis.call('HGET', reservationKey, userId) ~= orderId");
        assertThat(script).doesNotContain("HSET", "HDEL", "INCR", "DECR", "SADD", "SREM");
        assertThat(script).contains("return 0", "return 1", "return 2", "return 3", "return 4");
    }

    @Test
    void reconciliationScriptsPreserveStringIdsAndOnlyClaimExactDueReservations()
            throws IOException {
        String due = readScript("lua/seckill_reconcile_due.lua");
        assertThat(due).contains("redis.call('TIME')", "redis.call('ZRANGEBYSCORE'");
        assertThat(due).contains("'LIMIT', 0, limit");
        assertThat(due).doesNotContain("ZREM");

        String claim = readScript("lua/seckill_reconcile_claim.lua");
        int claimQuarantineGuard = claim.indexOf("redis.call('ZSCORE', quarantineKey, orderId)");
        int ownershipGuard = claim.indexOf("state[2] ~= orderId");
        int reservationGuard = claim.indexOf("redis.call('HGET', reservationKey, userId) ~= orderId");
        int dueRead = claim.indexOf("redis.call('ZSCORE', processingIndexKey, orderId)");
        int attemptIncrement = claim.indexOf("redis.call('HINCRBY', statusKey, 'reconcileAttempts', 1)");
        int scoreAdvance = claim.indexOf("redis.call('ZADD', processingIndexKey, now + retryDelaySeconds, orderId)");
        assertThat(claimQuarantineGuard).isGreaterThanOrEqualTo(0);
        assertThat(ownershipGuard).isGreaterThan(claimQuarantineGuard);
        assertThat(reservationGuard).isGreaterThan(ownershipGuard);
        assertThat(dueRead).isGreaterThan(reservationGuard);
        assertThat(attemptIncrement).isGreaterThan(dueRead);
        assertThat(scoreAdvance).isGreaterThan(attemptIncrement);
        assertThat(claim).contains("redis.call('PERSIST', statusKey)");
        assertThat(claim).contains("string.match(raw, '^[1-9][0-9]*$')");
        assertThat(claim).contains("parseCreatedAt(state[5], now)", "parsed > now");
        assertThat(claim).doesNotContain("tonumber(ARGV[3])");

        String quarantine = readScript("lua/seckill_reconcile_quarantine.lua");
        assertThat(quarantine).contains(
                "redis.call('ZREM', KEYS[1], orderId)",
                "redis.call('ZADD', KEYS[2], redisTime[1], orderId)",
                "redis.call('HSET', KEYS[3], orderId, reason)");

        String deferUnresolved = readScript("lua/seckill_reconcile_defer_unresolved.lua");
        assertThat(deferUnresolved).contains(
                "redis.call('ZSCORE', KEYS[3], orderId)",
                "state[2] == orderId and state[3] and state[4]",
                "redis.call('ZADD', KEYS[2], tonumber(redisTime[1]) + retryDelaySeconds, orderId)");
        assertThat(deferUnresolved).doesNotContain("HSET", "HDEL", "INCR", "DECR", "SADD", "SREM");

        String backfill = readScript("lua/seckill_reconcile_backfill.lua");
        int backfillQuarantineGuard = backfill.indexOf("redis.call('ZSCORE', KEYS[4], orderId)");
        int persist = backfill.indexOf("redis.call('PERSIST', KEYS[1])");
        int add = backfill.indexOf("redis.call('ZADD', KEYS[3], 'NX'");
        assertThat(backfillQuarantineGuard).isGreaterThanOrEqualTo(0);
        assertThat(persist).isGreaterThan(backfillQuarantineGuard);
        assertThat(add).isGreaterThan(persist);
        assertThat(backfill).contains("redis.call('ZREM', KEYS[3], orderId)");
        assertThat(backfill).contains("parseCreatedAt(state[5], now)", "parsed > now");
        assertThat(backfill).doesNotContain("tonumber(ARGV[3])");
    }

    @Test
    void successTransitionRemovesDueIndexBeforeApplyingTerminalTtl() throws IOException {
        String script = readScript("lua/seckill_mark_success.lua");
        int successTransition = script.indexOf("'status', 'SUCCESS'");
        int indexRemoval = script.lastIndexOf("redis.call('ZREM', processingIndexKey, orderId)");
        int terminalExpire = script.lastIndexOf("redis.call('EXPIRE', statusKey, statusTtlSeconds)");
        assertThat(successTransition).isGreaterThanOrEqualTo(0);
        assertThat(indexRemoval).isGreaterThan(successTransition);
        assertThat(terminalExpire).isGreaterThan(indexRemoval);
    }

    private static String readScript(String path) throws IOException {
        return StreamUtils.copyToString(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
    }
}
