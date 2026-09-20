package com.localdeals.platform.config;

import io.lettuce.core.cluster.ClusterClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 runs Redis as a Cluster. Two clients have to follow a failover: Lettuce, which serves every
 * template and script, and Redisson, which serves the locks.
 */
class RedisClusterConfigTest {

    private static final List<String> NODES =
            List.of("127.0.0.1:27001", "127.0.0.1:27002", "127.0.0.1:27003");

    @Test
    void lettuceRefreshesTheTopologyPeriodicallyAndAfterARedirect() {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder();

        new RedisClusterConfig().clusterTopologyRefreshCustomizer().customize(builder);

        ClusterClientOptions options = (ClusterClientOptions) builder.build().getClientOptions()
                .orElseThrow(() -> new AssertionError("no client options were set"));
        assertThat(options.getTopologyRefreshOptions().isPeriodicRefreshEnabled()).isTrue();
        assertThat(options.getTopologyRefreshOptions().getRefreshPeriod())
                .isLessThanOrEqualTo(Duration.ofSeconds(30));
        // Without adaptive refresh a MOVED after a failover is answered by the old topology
        // until the next period elapses.
        assertThat(options.getTopologyRefreshOptions().getAdaptiveRefreshTriggers()).isNotEmpty();
        // A slot that is briefly without a master must fail fast instead of piling up commands.
        assertThat(options.isValidateClusterNodeMembership()).isTrue();
    }

    @Test
    void redissonUsesTheClusterWhenNodesAreConfiguredAndOneServerOtherwise() {
        RedisProperties standalone = new RedisProperties();
        standalone.setHost("127.0.0.1");
        standalone.setPort(26379);
        standalone.setPassword("secret");

        assertThat(RedissonConfig.buildConfig(standalone).isClusterConfig()).isFalse();

        RedisProperties clustered = new RedisProperties();
        clustered.setPassword("secret");
        clustered.setCluster(new RedisProperties.Cluster());
        clustered.getCluster().setNodes(NODES);

        Config config = RedissonConfig.buildConfig(clustered);
        assertThat(config.isClusterConfig()).isTrue();
        assertThat(config.useClusterServers().getNodeAddresses())
                .containsExactlyElementsOf(NODES.stream().map(node -> "redis://" + node).toList());
        assertThat(config.useClusterServers().getPassword()).isEqualTo("secret");
    }
}
