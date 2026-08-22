package com.localdeals.marketing;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Fail-closed datasource and Redis target validation for M6C isolated tests. */
final class M6cDatasourceGuard {
    private M6cDatasourceGuard() {
    }

    static void register(DynamicPropertyRegistry registry) {
        IsolatedMarketingDatasourceGuard.register(registry, "M6C");
    }
}
