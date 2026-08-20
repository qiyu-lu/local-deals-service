package com.localdeals.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityPropertiesTest {

    @Test
    void defaultsAreDisabledAndBounded() {
        ObservabilityProperties properties = new ObservabilityProperties();

        assertThatCode(properties::validate).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(properties.isSamplingEnabled()).isFalse();
        org.assertj.core.api.Assertions.assertThat(properties.getSamplingInterval())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsAnIntervalWhichWouldContinuouslyScanThePendingIndex() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setSamplingInterval(Duration.ofSeconds(14));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 15s");
    }
}
