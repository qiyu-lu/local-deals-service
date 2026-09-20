package com.localdeals.platform.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        return Redisson.create(buildConfig(redisProperties));
    }

    /**
     * Follows the same properties as the rest of the application: configured cluster nodes mean
     * a Cluster, and nothing else means the single server. Redisson holds the locks, so it has
     * to know about a failover as much as Lettuce does.
     */
    static Config buildConfig(RedisProperties redisProperties) {
        Config config = new Config();
        boolean clustered = redisProperties.getCluster() != null
                && !CollectionUtils.isEmpty(redisProperties.getCluster().getNodes());
        if (clustered) {
            ClusterServersConfig cluster = config.useClusterServers()
                    .setScanInterval(5_000)
                    .setKeepAlive(true);
            for (String node : redisProperties.getCluster().getNodes()) {
                cluster.addNodeAddress("redis://" + node);
            }
            if (StringUtils.hasText(redisProperties.getPassword())) {
                cluster.setPassword(redisProperties.getPassword());
            }
            return config;
        }
        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setKeepAlive(true);
        if (StringUtils.hasText(redisProperties.getPassword())) {
            singleServerConfig.setPassword(redisProperties.getPassword());
        }
        return config;
    }
}
