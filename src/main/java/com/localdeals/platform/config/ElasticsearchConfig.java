package com.localdeals.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;

/**
 * Elasticsearch Java client with the validated search timeouts from {@link TrafficControlProperties};
 * the parent class exposes the {@code ElasticsearchOperations} bean.
 */
@Configuration
public class ElasticsearchConfig extends ElasticsearchConfiguration {

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String esUri;

    private final TrafficControlProperties trafficControlProperties;

    public ElasticsearchConfig(TrafficControlProperties trafficControlProperties) {
        this.trafficControlProperties = trafficControlProperties;
    }

    @Override
    public ClientConfiguration clientConfiguration() {
        return ClientConfiguration.builder()
                .connectedTo(esUri.replace("http://", "").replace("https://", ""))
                .withConnectTimeout(trafficControlProperties.getSearch().getConnectTimeout())
                .withSocketTimeout(trafficControlProperties.getSearch().getSocketTimeout())
                .build();
    }
}
