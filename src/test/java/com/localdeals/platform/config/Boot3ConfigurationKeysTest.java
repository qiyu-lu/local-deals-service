package com.localdeals.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot 3 moved several keys (for example {@code spring.redis.*} to
 * {@code spring.data.redis.*}). A key under the old name is silently ignored and the application
 * falls back to localhost defaults, so the configuration is bound here the way Boot 3 reads it.
 */
class Boot3ConfigurationKeysTest {

    private static final List<String> REMOVED_PREFIXES = Arrays.asList(
            "spring.redis.", "spring.elasticsearch.rest.");

    @Test
    void redisSettingsBindUnderSpringDataRedis() throws IOException {
        Map<String, Object> env = new HashMap<>();
        env.put("LOCAL_DEALS_REDIS_HOST", "redis.internal");
        env.put("LOCAL_DEALS_REDIS_PORT", "16379");
        env.put("LOCAL_DEALS_REDIS_PASSWORD", "secret");
        Binder binder = binder("application.yaml", env);

        assertThat(binder.bind("spring.data.redis.host", String.class).get()).isEqualTo("redis.internal");
        assertThat(binder.bind("spring.data.redis.port", Integer.class).get()).isEqualTo(16379);
        assertThat(binder.bind("spring.data.redis.password", String.class).get()).isEqualTo("secret");
        assertThat(binder.bind("spring.data.redis.timeout", Duration.class).get())
                .isEqualTo(Duration.ofMillis(500));
        assertThat(binder.bind("spring.data.redis.lettuce.pool.max-active", Integer.class).get())
                .isEqualTo(10);
    }

    @Test
    void elasticsearchUriBindsUnderTheBoot3KeyAndCanBeOverridden() throws IOException {
        Map<String, Object> env = new HashMap<>();
        env.put("SPRING_ELASTICSEARCH_URIS", "http://es.internal:19200");

        assertThat(binder("application.yaml", new HashMap<>())
                .bind("spring.elasticsearch.uris", Bindable.listOf(String.class)).get())
                .containsExactly("http://localhost:9200");
        assertThat(binder("application.yaml", env)
                .bind("spring.elasticsearch.uris", Bindable.listOf(String.class)).get())
                .containsExactly("http://es.internal:19200");
    }

    @Test
    void datasourceUsesTheConnectorJDriver() throws IOException {
        assertThat(binder("application.yaml", new HashMap<>())
                .bind("spring.datasource.driver-class-name", String.class).get())
                .isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    void noConfigurationFileUsesARemovedKey() throws IOException {
        for (String file : Arrays.asList("application.yaml", "application-test.yaml")) {
            assertThat(keys(file)).as(file)
                    .noneMatch(key -> REMOVED_PREFIXES.stream().anyMatch(key::startsWith));
        }
    }

    /** Environment variables are simulated with a system-environment-style source ahead of the yaml. */
    private static Binder binder(String file, Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("env", env));
        for (PropertySource<?> source : load(file)) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment);
    }

    private static List<String> keys(String file) throws IOException {
        List<String> keys = new ArrayList<>();
        for (PropertySource<?> source : load(file)) {
            keys.addAll(Arrays.asList(((EnumerablePropertySource<?>) source).getPropertyNames()));
        }
        return keys;
    }

    private static List<PropertySource<?>> load(String file) throws IOException {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
    }
}
