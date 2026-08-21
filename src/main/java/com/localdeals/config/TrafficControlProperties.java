package com.localdeals.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;

/** Static and deliberately small M5C admission-control configuration. */
@Data
@Component
@ConfigurationProperties(prefix = "local-deals.traffic")
public class TrafficControlProperties {
    private Seckill seckill = new Seckill();
    private Read read = new Read();
    private Search search = new Search();

    @PostConstruct
    public void validate() {
        if (seckill == null || read == null || search == null) {
            throw new IllegalStateException("local-deals.traffic sections must not be null");
        }
        requireDurationRange(seckill.window, Duration.ofSeconds(1), Duration.ofSeconds(60),
                "seckill.window");
        requireLimit(seckill.activityLimit, 1_000_000, "seckill.activity-limit");
        requireLimit(seckill.userLimit, 10_000, "seckill.user-limit");
        requireLimit(seckill.ipLimit, 100_000, "seckill.ip-limit");
        requireConcurrency(read.dbMaxConcurrent, "read.db-max-concurrent");
        requireConcurrency(read.searchMaxConcurrent, "read.search-max-concurrent");
        requireDurationRange(read.dbMaxWait, Duration.ZERO, Duration.ofMillis(100),
                "read.db-max-wait");
        requireDurationRange(read.searchMaxWait, Duration.ZERO, Duration.ofMillis(100),
                "read.search-max-wait");
        requireDurationRange(read.sharedLoadWait, Duration.ofMillis(100), Duration.ofSeconds(2),
                "read.shared-load-wait");
        requireDurationRange(search.connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(2),
                "search.connect-timeout");
        requireDurationRange(search.socketTimeout, Duration.ofMillis(100), Duration.ofSeconds(2),
                "search.socket-timeout");
    }

    private static void requireLimit(int value, int maximum, String name) {
        if (value < 1 || value > maximum) {
            throw new IllegalStateException("local-deals.traffic." + name +
                    " must be between 1 and " + maximum);
        }
    }

    private static void requireConcurrency(int value, String name) {
        requireLimit(value, 64, name);
    }

    private static void requireDurationRange(Duration value, Duration minimum,
                                             Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalStateException("local-deals.traffic." + name +
                    " must be between " + minimum + " and " + maximum);
        }
    }

    @Data
    public static class Seckill {
        private boolean enabled = true;
        private Duration window = Duration.ofSeconds(1);
        private int activityLimit = 300;
        private int userLimit = 2;
        private int ipLimit = 100;
    }

    @Data
    public static class Read {
        private int dbMaxConcurrent = 4;
        private Duration dbMaxWait = Duration.ofMillis(20);
        private Duration sharedLoadWait = Duration.ofMillis(750);
        private int searchMaxConcurrent = 4;
        private Duration searchMaxWait = Duration.ofMillis(20);
    }

    @Data
    public static class Search {
        private Duration connectTimeout = Duration.ofMillis(500);
        private Duration socketTimeout = Duration.ofSeconds(1);
    }
}
