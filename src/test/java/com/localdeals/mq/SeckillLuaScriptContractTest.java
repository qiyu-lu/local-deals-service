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
        assertThat(script).doesNotContain("tonumber(ARGV[3])");

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

        int reservationRead = script.indexOf("redis.call('HGET', reservationKey, userId)");
        int exactReservationGuard = script.indexOf("reservedOrderId ~= orderId");
        int statusRead = script.indexOf("redis.call('HMGET', orderStatusKey");
        int processingGuard = script.indexOf("statusData[1] ~= 'PROCESSING'");
        int stockRestore = script.indexOf("redis.call('INCR', stockKey)");
        int reservationDelete = script.indexOf("redis.call('HDEL', reservationKey, userId)");
        int failedTransition = script.indexOf("'status', 'FAILED'");

        assertThat(reservationRead).isGreaterThanOrEqualTo(0);
        assertThat(exactReservationGuard).isGreaterThan(reservationRead);
        assertThat(statusRead).isGreaterThan(exactReservationGuard);
        assertThat(processingGuard).isGreaterThan(statusRead);
        assertThat(stockRestore).isGreaterThan(processingGuard);
        assertThat(reservationDelete).isGreaterThan(stockRestore);
        assertThat(failedTransition).isGreaterThan(reservationDelete);
        assertThat(script).contains("statusData[2] ~= orderId");
        assertThat(script).contains("statusData[3] ~= userId");
        assertThat(script).contains("statusData[4] ~= voucherId");
        assertThat(script).contains("redis.call('SREM', legacyOrderKey, userId)");
        assertThat(script).contains("return 1");
    }

    @Test
    void consumerValidationScript_isReadOnlyAndRequiresExactProcessingReservation()
            throws IOException {
        String script = readScript("lua/seckill_validate_reservation.lua");

        assertThat(script).contains("redis.call('HMGET', statusKey");
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

    private static String readScript(String path) throws IOException {
        return StreamUtils.copyToString(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
    }
}
