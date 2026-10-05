package com.localdeals.platform.testsupport;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Points every Spring test context at throw-away MySQL 8 and Redis 6.2 containers when
 * {@code LOCAL_DEALS_IT_CONTAINERS=true} (CI). The containers start once per JVM and are shared
 * by all contexts; without the switch this initializer does nothing, so local runs keep using
 * {@code scripts/stack.sh}. Registered through {@code META-INF/spring.factories}.
 */
public class ContainersInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String SWITCH = "LOCAL_DEALS_IT_CONTAINERS";
    private static final String REDIS_PASSWORD = "it-redis";

    private static MySQLContainer<?> mysql;
    private static GenericContainer<?> redis;

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!"true".equals(System.getenv(SWITCH))) {
            return;
        }
        startOnce();
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", mysql.getJdbcUrl() +
                "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        properties.put("spring.datasource.username", mysql.getUsername());
        properties.put("spring.datasource.password", mysql.getPassword());
        properties.put("spring.data.redis.host", redis.getHost());
        properties.put("spring.data.redis.port", redis.getMappedPort(6379));
        properties.put("spring.data.redis.password", REDIS_PASSWORD);
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("testcontainers", properties));
    }

    @SuppressWarnings("resource")
    private static synchronized void startOnce() {
        if (mysql != null) {
            return;
        }
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("local_deals")
                .withUsername("root")
                .withPassword("it-mysql")
                .withCommand("--default-authentication-plugin=mysql_native_password");
        redis = new GenericContainer<>(DockerImageName.parse("redis:6.2"))
                .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
                .withExposedPorts(6379);
        mysql.start();
        redis.start();
        createSecondOrderDatabase();
    }

    /**
     * The order tables are sharded over two databases on one server, and the second one's URL is
     * derived from the first ({@code ShardingProperties#urlFrom}: schema + {@code _1}). The
     * container only creates the first, so every context failed on {@code Unknown database}.
     */
    private static void createSecondOrderDatabase() {
        try (Connection connection = mysql.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + mysql.getDatabaseName() + "_1");
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to create the second order database", e);
        }
    }
}
