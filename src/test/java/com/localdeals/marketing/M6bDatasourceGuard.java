package com.localdeals.marketing;

import org.springframework.test.context.DynamicPropertyRegistry;

final class M6bDatasourceGuard {
    private M6bDatasourceGuard() {
    }

    static void register(DynamicPropertyRegistry registry) {
        IsolatedMarketingDatasourceGuard.register(registry, "M6B");
    }
}
