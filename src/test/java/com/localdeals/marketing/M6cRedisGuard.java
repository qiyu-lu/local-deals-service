package com.localdeals.marketing;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Resolves and verifies only the run-owned Redis port and sentinel. */
final class M6cRedisGuard {
    private M6cRedisGuard() {
    }

    static int port() {
        String value = required("M6C_REDIS_PORT");
        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("M6C_REDIS_PORT must be numeric", failure);
        }
        if (port < 1024 || port > 65535 || port == 6379 || port == 3306) {
            throw new IllegalStateException("M6C_REDIS_PORT must be an unprivileged non-shared port");
        }
        return port;
    }

    static void assertSentinel() {
        String runId = required("M6C_RUN_ID");
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration("127.0.0.1", port());
        String password = password();
        if (password != null) {
            redis.setPassword(RedisPassword.of(password));
        }
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redis);
        try {
            connectionFactory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
            template.afterPropertiesSet();
            String value = template.opsForValue().get("m6c:sentinel:" + runId);
            if (!runId.equals(value)) throw new IllegalStateException("M6C Redis sentinel mismatch");
        } finally {
            connectionFactory.destroy();
        }
    }

    static String host() {
        return "127.0.0.1";
    }

    static String password() {
        String value = System.getenv("M6C_REDIS_PASSWORD");
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(name + " is required");
        return value.trim();
    }
}
