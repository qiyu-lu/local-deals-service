package com.localdeals.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * There is no pre-V2 production data to migrate, so no upgrade, cutover or feature-rollout gate
 * may stay in the runtime configuration: each feature has exactly one live code path.
 */
class LegacyRolloutGateContractTest {

    @Test
    void noLegacyUpgradeOrRolloutGateRemains() throws IOException {
        String yaml = StreamUtils.copyToString(
                new ClassPathResource("application.yaml").getInputStream(), StandardCharsets.UTF_8);

        assertThat(yaml).doesNotContain("legacy-", "backfill-on-startup", "write-enabled", "read-enabled");
    }
}
