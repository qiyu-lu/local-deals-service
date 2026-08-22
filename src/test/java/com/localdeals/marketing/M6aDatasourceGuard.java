package com.localdeals.marketing;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Small fail-closed boundary for every M6A persistence Spring test.
 * It runs while the DynamicPropertySource is being evaluated, before the
 * datasource or Flyway beans can open a connection.
 */
final class M6aDatasourceGuard {
    private static final int FORBIDDEN_DEFAULT_MYSQL_PORT = 3306;
    private static final String MYSQL_JDBC_PREFIX = "jdbc:mysql://";

    private M6aDatasourceGuard() {
    }

    static void register(DynamicPropertyRegistry registry) {
        IsolatedMarketingDatasourceGuard.register(registry, "M6A");
    }
}
