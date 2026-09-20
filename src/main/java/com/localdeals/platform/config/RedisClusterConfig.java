package com.localdeals.platform.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Keeps Lettuce's view of the Cluster current.
 *
 * <p>Without a refresh policy the client keeps routing to the master it learned at startup: after
 * a failover every command for that slot answers MOVED until the connection is rebuilt by hand.
 * Periodic refresh bounds how long a stale view can last, and the adaptive triggers refresh as
 * soon as a redirect or a persistent reconnect says the topology moved — which is what the
 * failure drill of this milestone exercises.</p>
 *
 * <p>The options are harmless for a standalone Redis: {@link ClusterClientOptions} is only
 * consulted by a cluster client.</p>
 */
@Configuration
public class RedisClusterConfig {

    /** How long a stale topology may survive without any redirect to trigger a refresh. */
    private static final Duration REFRESH_PERIOD = Duration.ofSeconds(10);

    @Bean
    public LettuceClientConfigurationBuilderCustomizer clusterTopologyRefreshCustomizer() {
        ClusterTopologyRefreshOptions refresh = ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(REFRESH_PERIOD)
                .enableAllAdaptiveRefreshTriggers()
                .adaptiveRefreshTriggersTimeout(Duration.ofSeconds(5))
                .dynamicRefreshSources(true)
                .build();
        ClusterClientOptions options = ClusterClientOptions.builder()
                .topologyRefreshOptions(refresh)
                .validateClusterNodeMembership(true)
                // A slot without a master fails the command instead of queueing it until the
                // command timeout; the caller already treats Redis as a layer that may fail.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();
        return builder -> builder.clientOptions(options);
    }
}
