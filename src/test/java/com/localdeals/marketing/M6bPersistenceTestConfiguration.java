package com.localdeals.marketing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** Reuses the narrow M6A persistence context and supplies a controllable clock for M6B. */
@Configuration
@Import(M6aPersistenceTestConfiguration.class)
public class M6bPersistenceTestConfiguration {
    @Bean
    @Primary
    public MutableBusinessClock m6bBusinessClock() {
        return new MutableBusinessClock(Instant.parse("2026-08-21T16:00:00Z"),
                ZoneId.of("UTC"));
    }
}

final class MutableBusinessClock extends Clock {
    private volatile Instant instant;
    private final ZoneId zone;

    MutableBusinessClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    void setInstant(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
        return new DelegatingClock(this, requestedZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    private static final class DelegatingClock extends Clock {
        private final MutableBusinessClock source;
        private final ZoneId zone;

        private DelegatingClock(MutableBusinessClock source, ZoneId zone) {
            this.source = source;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new DelegatingClock(source, requestedZone);
        }

        @Override
        public Instant instant() {
            return source.instant();
        }
    }
}
