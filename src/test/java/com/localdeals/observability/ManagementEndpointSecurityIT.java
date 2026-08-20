package com.localdeals.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.server.address=127.0.0.1",
        "local-deals.observability.sampling-enabled=true",
        "local-deals.observability.initial-delay=1h",
        "local-deals.observability.sampling-interval=15s"
})
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "M5A_ISOLATED", matches = "true")
class ManagementEndpointSecurityIT {

    @LocalServerPort
    private int businessPort;

    @Value("${local.management.port}")
    private int managementPort;

    private final TestRestTemplate client = new TestRestTemplate();

    @Test
    void managementEndpointsAreSeparatedAndMinimallyExposed() {
        ResponseEntity<String> businessPrometheus =
                get(businessPort, "/actuator/prometheus", null);
        assertThat(businessPrometheus.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(businessPrometheus.getBody()))
                .doesNotContain("local_deals_", "jvm_memory_used_bytes");

        ResponseEntity<String> health = get(managementPort, "/actuator/health", null);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody())
                .contains("\"status\"")
                .doesNotContain("components", "jdbc:mysql", "localhost:9200", "stackTrace");

        ResponseEntity<String> prometheus = get(managementPort, "/actuator/prometheus", null);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody())
                .contains("local_deals_blog_like_command_total")
                .contains("http_server_requests_seconds")
                .contains("hikaricp_connections_active")
                .contains("jvm_memory_used_bytes")
                .contains("process_cpu_usage")
                .doesNotContain("13800138000", "username=", "token=");

        assertNotExposed("/actuator/info");
        assertNotExposed("/actuator/metrics");
        assertNotExposed("/actuator/env");
        assertNotExposed("/actuator/configprops");
        assertNotExposed("/actuator/loggers");
        assertNotExposed("/actuator/heapdump");

        HttpHeaders consumerToken = new HttpHeaders();
        consumerToken.set("authorization", "not-a-monitoring-credential");
        ResponseEntity<String> tokenAttempt =
                get(businessPort, "/actuator/prometheus", consumerToken);
        assertThat(tokenAttempt.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(tokenAttempt.getBody())).doesNotContain("local_deals_");
    }

    private ResponseEntity<String> get(int port, String path, HttpHeaders headers) {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        if (headers == null) {
            return client.getForEntity(uri, String.class);
        }
        return client.exchange(new RequestEntity<Void>(headers,
                org.springframework.http.HttpMethod.GET, uri), String.class);
    }

    private void assertNotExposed(String path) {
        ResponseEntity<String> response = get(managementPort, path, null);
        assertThat(response.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(response.getBody()))
                .doesNotContain("propertySources", "contexts", "threads", "local_deals_");
    }
}
