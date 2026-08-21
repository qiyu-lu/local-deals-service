package com.localdeals.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchTimeoutConfigurationTest {
    @Test
    void clientUsesValidatedTrafficTimeoutsInsteadOfLegacyHardCoding() {
        TrafficControlProperties properties = new TrafficControlProperties();
        properties.getSearch().setConnectTimeout(Duration.ofMillis(321));
        properties.getSearch().setSocketTimeout(Duration.ofMillis(987));
        properties.validate();
        ElasticsearchConfig configuration = new ElasticsearchConfig(properties);
        ReflectionTestUtils.setField(configuration, "esUri", "http://127.0.0.1:19200");

        ClientConfiguration client = configuration.clientConfiguration();

        assertThat(client.getConnectTimeout()).isEqualTo(Duration.ofMillis(321));
        assertThat(client.getSocketTimeout()).isEqualTo(Duration.ofMillis(987));
        assertThat(client.getEndpoints()).hasSize(1);
        assertThat(client.getEndpoints().get(0).getHostString()).isEqualTo("127.0.0.1");
        assertThat(client.getEndpoints().get(0).getPort()).isEqualTo(19200);
    }
}
