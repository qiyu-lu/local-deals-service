package com.localdeals.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeckillPropertiesTest {

    @Test
    void startupRejectsBackfillAndLiveReconciliationInTheSameProcess() {
        SeckillProperties properties = new SeckillProperties();
        properties.getReconciliation().setBackfillOnStartup(true);
        properties.getReconciliation().setEnabled(true);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backfill-on-startup and enabled");
    }

    @Test
    void disabledWorkerRemainsASafeKillSwitchEvenIfCompensationFlagIsStillSet() {
        SeckillProperties properties = new SeckillProperties();
        properties.getReconciliation().setEnabled(false);
        properties.getReconciliation().setCompensationEnabled(true);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void topicAndConsumerGroupAreConfigurableButNeverBlank() {
        SeckillProperties properties = new SeckillProperties();
        properties.setTopic("m5c-topic-run-1");
        properties.setConsumerGroup("m5c-consumer-run-1");
        assertThatCode(properties::validate).doesNotThrowAnyException();

        properties.setTopic(" ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topic and consumer-group");
    }
}
