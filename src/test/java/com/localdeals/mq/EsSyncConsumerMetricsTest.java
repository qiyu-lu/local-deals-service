package com.localdeals.mq;

import com.localdeals.observability.LocalDealsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class EsSyncConsumerMetricsTest {

    private ElasticsearchRestTemplate esTemplate;
    private SimpleMeterRegistry registry;
    private EsSyncConsumer consumer;

    @BeforeEach
    void setUp() {
        esTemplate = mock(ElasticsearchRestTemplate.class);
        registry = new SimpleMeterRegistry();
        consumer = new EsSyncConsumer(esTemplate, new LocalDealsMetrics(registry));
    }

    @Test
    void oneGoodAndOneBadRowIsAPartialFailure() {
        consumer.onMessage("{\"table\":\"tb_shop\",\"type\":\"UPDATE\",\"isDdl\":false," +
                "\"data\":[{\"id\":\"1\",\"name\":\"ok\"},{\"name\":\"missing-id\"}]}");

        assertThat(counter("local_deals.es.sync.messages", "table", "shop",
                "operation", "update", "result", "partial_failure")).isEqualTo(1D);
        assertThat(counter("local_deals.es.sync.rows", "table", "shop",
                "operation", "update", "result", "success")).isEqualTo(1D);
        assertThat(counter("local_deals.es.sync.rows", "table", "shop",
                "operation", "update", "result", "failure")).isEqualTo(1D);
    }

    @Test
    void allEsWritesFailWithoutThrowingAndMessageIsFailure() {
        doThrow(new IllegalStateException("es unavailable")).when(esTemplate).index(any(), any());

        consumer.onMessage("{\"table\":\"tb_blog\",\"type\":\"INSERT\",\"isDdl\":false," +
                "\"data\":[{\"id\":\"2\",\"title\":\"x\"}]}");

        assertThat(counter("local_deals.es.sync.messages", "table", "blog",
                "operation", "insert", "result", "failure")).isEqualTo(1D);
    }

    @Test
    void malformedAndDdlMessagesHaveFiniteIgnoredLabels() {
        consumer.onMessage("not-json");
        consumer.onMessage("{\"table\":\"tb_shop\",\"type\":\"DDL\",\"isDdl\":true," +
                "\"data\":[{}]}");

        assertThat(counter("local_deals.es.sync.messages", "table", "ignored",
                "operation", "other", "result", "failure")).isEqualTo(1D);
        assertThat(counter("local_deals.es.sync.messages", "table", "shop",
                "operation", "other", "result", "ignored")).isEqualTo(1D);
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }
}
