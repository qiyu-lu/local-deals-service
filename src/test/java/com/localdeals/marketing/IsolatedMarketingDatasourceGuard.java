package com.localdeals.marketing;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.URI;
import java.net.URISyntaxException;

/** Shared fail-closed datasource boundary for isolated M6A/M6B persistence tests. */
final class IsolatedMarketingDatasourceGuard {
    private static final int FORBIDDEN_DEFAULT_MYSQL_PORT = 3306;
    private static final String MYSQL_JDBC_PREFIX = "jdbc:mysql://";

    private IsolatedMarketingDatasourceGuard() {
    }

    static void register(DynamicPropertyRegistry registry, String stage) {
        String runId = required(stage + "_RUN_ID", stage);
        validateRunId(runId, stage);
        int port = parsePort(required(stage + "_MYSQL_PORT", stage), stage);
        String url = required("LOCAL_DEALS_DATASOURCE_URL", stage);
        String username = required("LOCAL_DEALS_DATASOURCE_USERNAME", stage);
        String password = required("LOCAL_DEALS_DATASOURCE_PASSWORD", stage);
        validateUrl(url, port, stage);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
    }

    private static String required(String name, String stage) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(stage + " datasource guard requires environment variable " + name);
        }
        return value.trim();
    }

    private static void validateRunId(String runId, String stage) {
        String prefix = stage.toLowerCase(java.util.Locale.ROOT) + "_";
        if (!runId.matches(prefix + "[a-z0-9][a-z0-9_-]{2,40}")) {
            throw new IllegalStateException(stage + "_RUN_ID has invalid format: " + runId);
        }
    }

    private static int parsePort(String value, String stage) {
        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalStateException(stage + "_MYSQL_PORT must be numeric: " + value, error);
        }
        if (port < 1024 || port > 65535 || port == FORBIDDEN_DEFAULT_MYSQL_PORT) {
            throw new IllegalStateException(stage + "_MYSQL_PORT must be an unprivileged non-3306 port: " + port);
        }
        return port;
    }

    private static void validateUrl(String url, int expectedPort, String stage) {
        if (!url.startsWith(MYSQL_JDBC_PREFIX)) {
            throw new IllegalStateException("LOCAL_DEALS_DATASOURCE_URL must be a MySQL JDBC URL");
        }
        URI uri;
        try {
            uri = new URI(url.substring("jdbc:".length()));
        } catch (URISyntaxException error) {
            throw new IllegalStateException("LOCAL_DEALS_DATASOURCE_URL is not a valid URL", error);
        }
        if (uri.getPort() == FORBIDDEN_DEFAULT_MYSQL_PORT || url.contains("localhost:3306")) {
            throw new IllegalStateException("LOCAL_DEALS_DATASOURCE_URL must not target 3306");
        }
        if (!"mysql".equalsIgnoreCase(uri.getScheme()) ||
                !"127.0.0.1".equals(uri.getHost()) || uri.getPort() != expectedPort) {
            throw new IllegalStateException("LOCAL_DEALS_DATASOURCE_URL must target 127.0.0.1:" + expectedPort);
        }
    }
}
