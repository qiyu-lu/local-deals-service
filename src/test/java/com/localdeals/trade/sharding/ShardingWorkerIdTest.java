package com.localdeals.trade.sharding;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The second Snowflake in this application.
 *
 * <p>M6 gave the attached tables ShardingSphere's SNOWFLAKE key generator, because an
 * AUTO_INCREMENT restarts in every physical table. Its worker id defaults to 0, and two
 * instances that both use 0 mint the same id in the same millisecond: the M8 full-chain smoke
 * produced 24 {@code Duplicate entry ... for key 'order_state_log_3.PRIMARY'} in one run of a
 * thousand orders. One instance never sees it.</p>
 */
class ShardingWorkerIdTest {

    @Test
    void instancesOnOneHostGetDifferentWorkerIdsFromTheirPorts() {
        assertThat(ShardingWorkerId.resolve(environment("38083"))).isEqualTo(38083 % 1024);
        assertThat(ShardingWorkerId.resolve(environment("38084")))
                .isNotEqualTo(ShardingWorkerId.resolve(environment("38083")));
    }

    /** Across hosts the ports repeat, so a deployment there says which id this instance has. */
    @Test
    void anExplicitWorkerIdWins() {
        MockEnvironment environment = environment("38083");
        environment.setProperty("local-deals.sharding.worker-id", "7");

        assertThat(ShardingWorkerId.resolve(environment)).isEqualTo(7);
    }

    @Test
    void aWorkerIdOutsideSnowflakesRangeIsRefusedRatherThanTruncated() {
        MockEnvironment environment = environment("38083");
        environment.setProperty("local-deals.sharding.worker-id", "1024");

        assertThatThrownBy(() -> ShardingWorkerId.resolve(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local-deals.sharding.worker-id");
    }

    @Test
    void theDefaultPortIsUsedWhenNothingSaysOtherwise() {
        assertThat(ShardingWorkerId.resolve(new MockEnvironment())).isEqualTo(8083 % 1024);
    }

    /** The generator has to read it, which it only does from its own props. */
    @Test
    void theRulesGiveTheSnowflakeGeneratorAWorkerId() throws IOException {
        String rules = new String(Files.readAllBytes(Paths.get("src/main/resources/sharding.yaml")),
                StandardCharsets.UTF_8);

        assertThat(rules).contains("type: SNOWFLAKE");
        assertThat(rules).contains(ShardingWorkerId.PLACEHOLDER);
        assertThat(ShardingWorkerId.applyTo(rules, 7))
                .contains("worker-id: 7")
                .doesNotContain(ShardingWorkerId.PLACEHOLDER);
    }

    private static MockEnvironment environment(String port) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", port);
        return environment;
    }
}
