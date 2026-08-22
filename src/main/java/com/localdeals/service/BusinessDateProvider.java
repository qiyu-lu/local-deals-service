package com.localdeals.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** One application-owned business date source for sign-in and daily rewards. */
public class BusinessDateProvider {
    private final Clock clock;
    private final ZoneId zoneId;

    public BusinessDateProvider(Clock clock, ZoneId zoneId) {
        if (clock == null || zoneId == null) {
            throw new IllegalArgumentException("business clock and time zone are required");
        }
        this.clock = clock;
        this.zoneId = zoneId;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zoneId));
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(zoneId));
    }

    public String dailySignInIdempotencyKey() {
        return "TASK_REWARD:DAILY_SIGN_IN:" + today();
    }

    public ZoneId zoneId() {
        return zoneId;
    }
}
