package com.localdeals.trade.sharding;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the order domain's two physical databases and the ShardingSphere data source over them.
 *
 * <p>The physical data sources are created here rather than by Boot's auto-configuration for one
 * reason: migrations. Flyway run through a routing data source would have to be told where every
 * statement belongs, so instead each database is migrated directly — ds_0 with the whole history
 * ({@code db/migration}), ds_1 with only the sharded tables ({@code db/shard}) — before the
 * routing data source is handed to anyone. {@code spring.flyway.enabled} is therefore false; the
 * settings that mattered are repeated below where they can be read next to the migration.</p>
 *
 * <p>Pool settings still come from {@code spring.datasource.hikari}, so the benchmark scripts
 * keep their knob. They now apply <em>per database</em>: a pool of 24 is 48 connections.</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ShardingProperties.class)
public class ShardingDataSourceConfiguration {

    static final String PRIMARY_LOCATION = "classpath:db/migration";
    static final String SECOND_LOCATION = "classpath:db/shard";
    /** V16 renames the pre-shard tables; db/shard reads its own slots out of them. */
    static final String LEGACY_SCHEMA_PLACEHOLDER = "legacySchema";
    private static final String RULES = "sharding.yaml";

    @Bean(destroyMethod = "close")
    public HikariDataSource orderDatabase0(DataSourceProperties properties, Environment environment) {
        return pooled(properties, properties.getUrl(), properties.getUsername(),
                properties.getPassword(), "ds-0", environment);
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource orderDatabase1(DataSourceProperties properties, ShardingProperties sharding,
                                           Environment environment) {
        String url = sharding.urlFrom(properties.getUrl());
        String username = sharding.getUsername().isBlank() ? properties.getUsername() : sharding.getUsername();
        String password = sharding.getPassword().isBlank() ? properties.getPassword() : sharding.getPassword();
        return pooled(properties, url, username, password, "ds-1", environment);
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource orderDatabase0, HikariDataSource orderDatabase1,
                                 DataSourceProperties properties, Environment environment) throws IOException {
        migrate(orderDatabase0, PRIMARY_LOCATION, Map.of());
        migrate(orderDatabase1, SECOND_LOCATION,
                Map.of(LEGACY_SCHEMA_PLACEHOLDER, JdbcUrls.schemaOf(properties.getUrl())));

        Map<String, DataSource> nodes = new LinkedHashMap<>();
        nodes.put("ds_0", orderDatabase0);
        nodes.put("ds_1", orderDatabase1);
        // The attached tables' Snowflake needs an id nobody else on this deployment is using,
        // and it only reads it from the rules, so it goes in before they are parsed.
        int workerId = ShardingWorkerId.resolve(environment);
        log.info("Sharded data source: two databases, sharding worker id {}", workerId);
        String rules = ShardingWorkerId.applyTo(
                new ClassPathResource(RULES).getContentAsString(StandardCharsets.UTF_8), workerId);
        try {
            return YamlShardingSphereDataSourceFactory.createDataSource(
                    nodes, rules.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build the sharded data source from " + RULES, e);
        }
    }

    private HikariDataSource pooled(DataSourceProperties properties, String url, String username,
                                    String password, String poolName, Environment environment) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(properties.determineDriverClassName());
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        // The same knob the single-database build had, applied to each database.
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(dataSource));
        dataSource.setPoolName(poolName);
        return dataSource;
    }

    private void migrate(DataSource dataSource, String location, Map<String, String> placeholders) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .validateOnMigrate(true)
                .placeholders(placeholders)
                .load()
                .migrate();
        log.info("Migrated {} from {}", ((HikariDataSource) dataSource).getPoolName(), location);
    }
}
