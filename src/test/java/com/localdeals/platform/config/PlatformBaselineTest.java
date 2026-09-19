package com.localdeals.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the runtime the V2 milestones are built on: Java 21, Spring Boot 3.5 (Jakarta EE 10) and
 * the Elasticsearch Java client. Written so it compiles on the previous baseline too, where it
 * fails instead of silently passing.
 */
class PlatformBaselineTest {

    @Test
    void runsOnJava21OrNewer() {
        String spec = System.getProperty("java.specification.version");
        int feature = spec.startsWith("1.") ? Integer.parseInt(spec.substring(2)) : Integer.parseInt(spec);

        assertThat(feature).as("java.specification.version=%s", spec).isGreaterThanOrEqualTo(21);
    }

    @Test
    void runsOnSpringBoot35() {
        assertThat(SpringBootVersion.getVersion()).startsWith("3.5.");
    }

    @Test
    void servletApiIsJakartaOnly() {
        assertThat(isPresent("jakarta.servlet.http.HttpServletRequest")).isTrue();
        assertThat(isPresent("javax.servlet.http.HttpServletRequest"))
                .as("javax.servlet must not linger on the classpath").isFalse();
    }

    @Test
    void searchUsesTheElasticsearchJavaClient() {
        assertThat(isPresent("co.elastic.clients.elasticsearch.ElasticsearchClient")).isTrue();
        assertThat(isPresent("org.elasticsearch.client.RestHighLevelClient"))
                .as("the deprecated high-level REST client is gone in 8.x").isFalse();
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, PlatformBaselineTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
