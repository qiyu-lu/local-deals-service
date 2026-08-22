package com.localdeals.mq;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EsSyncListenerConfigurationTest {

    @Test
    void topicAndGroupAreConfigurableWithCompatibleDefaults() {
        RocketMQMessageListener listener =
                EsSyncConsumer.class.getAnnotation(RocketMQMessageListener.class);

        assertThat(listener.topic())
                .isEqualTo("${local-deals.es-sync.topic:mysql-sync-topic}");
        assertThat(listener.consumerGroup())
                .isEqualTo("${local-deals.es-sync.consumer-group:es-sync-consumer-group}");
    }
}
