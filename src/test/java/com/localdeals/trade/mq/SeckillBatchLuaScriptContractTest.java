package com.localdeals.trade.mq;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Resource-contract tests for the M4 batch scripts. No Spring, no Redis. */
class SeckillBatchLuaScriptContractTest {

    @Test
    void batchClaim_validatesOwnershipBeforeLeasingAndNeverResurrectsAnIndexMember()
            throws IOException {
        String script = readScript("lua/seckill_batch_claim.lua");

        // Same ownership guards as the single-message validation it replaces.
        assertThat(script).contains("'status', 'orderId', 'userId', 'voucherId'");
        assertThat(script).contains("'claimOwner'", "'claimExpireAt'");
        // The lease only ever moves an existing member, so a finalized order is not re-queued.
        assertThat(script).contains("'ZADD', processingIndexKey, 'XX'");
        assertThat(script).contains("redis.call('TIME')");

        int ownershipGuard = script.indexOf("state[2] ~= orderId");
        int claimWrite = script.indexOf("'claimOwner', owner");
        assertThat(ownershipGuard).isGreaterThanOrEqualTo(0);
        assertThat(claimWrite).isGreaterThan(ownershipGuard);
    }

    @Test
    void batchClaim_answersOnceForEveryMessageInTheBatch() throws IOException {
        String script = readScript("lua/seckill_batch_claim.lua");

        assertThat(script).contains("local results = {}");
        assertThat(script).contains("return results");
        // Three ARGV per message, two status/reservation KEYS per message.
        assertThat(script).contains("#ARGV");
        assertThat(script).contains("KEYS[");
    }

    @Test
    void batchMarkSuccess_finalizesEachOrderAndReleasesItsClaim() throws IOException {
        String script = readScript("lua/seckill_batch_mark_success.lua");

        assertThat(script).contains("'status', 'SUCCESS'");
        assertThat(script).contains("redis.call('ZREM', processingIndexKey, orderId)");
        assertThat(script).contains("HDEL", "claimOwner");
        assertThat(script).contains("local results = {}");
        assertThat(script).contains("return results");
    }

    @Test
    void reconcileClaim_yieldsToAConsumerThatIsPersistingTheOrder() throws IOException {
        String script = readScript("lua/seckill_reconcile_claim.lua");

        // A live consumer claim must not be reconciled, no matter what the due score says.
        assertThat(script).contains("claimOwner");
        assertThat(script).contains("claimExpireAt");
    }

    private String readScript(String location) throws IOException {
        return StreamUtils.copyToString(
                new ClassPathResource(location).getInputStream(), StandardCharsets.UTF_8);
    }
}
