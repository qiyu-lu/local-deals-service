package com.localdeals.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDateProviderTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void businessDateAndTaskKeyUseConfiguredZoneAtMidnightBoundary() {
        BusinessDateProvider beforeMidnight = new BusinessDateProvider(
                Clock.fixed(Instant.parse("2026-08-21T15:59:59Z"), SHANGHAI), SHANGHAI);
        BusinessDateProvider afterMidnight = new BusinessDateProvider(
                Clock.fixed(Instant.parse("2026-08-21T16:00:00Z"), SHANGHAI), SHANGHAI);

        assertThat(beforeMidnight.today().toString()).isEqualTo("2026-08-21");
        assertThat(afterMidnight.today().toString()).isEqualTo("2026-08-22");
        assertThat(afterMidnight.dailySignInIdempotencyKey())
                .isEqualTo("TASK_REWARD:DAILY_SIGN_IN:2026-08-22");
    }
}
