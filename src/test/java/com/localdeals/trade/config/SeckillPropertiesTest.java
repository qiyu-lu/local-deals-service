package com.localdeals.trade.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeckillPropertiesTest {

    @Test
    void reconciliationAndCompensationRunByDefault() {
        SeckillProperties properties = new SeckillProperties();

        org.assertj.core.api.Assertions.assertThat(properties.getReconciliation().isEnabled()).isTrue();
        org.assertj.core.api.Assertions.assertThat(properties.getReconciliation().isCompensationEnabled()).isTrue();
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

    /**
     * The M6 sweep (benchmark/v2/m6/consume-sweep.md, run 20260920-215408-m6-consume at f35f34d)
     * measured 256:4:50ms:256 at 2001.5 orders/s with drain_s_after_load = 0.1 s and a mean batch
     * of 12.68, against 944.6 / 432.9 orders/s and a mean batch of 2.16 for the old defaults in
     * two rounds of the same jar. It is the only combination in that summary.csv where the
     * consumer kept up with the arrivals for a whole round, so it is the combination we ship —
     * whole, because no other combination of these four was measured.
     */
    @Test
    void consumeDefaultsAreTheCombinationTheM6SweepMeasured() {
        SeckillProperties.Consume consume = new SeckillProperties().getConsume();

        assertThat(consume.getBatchSize()).isEqualTo(256);
        assertThat(consume.getThreadCount()).isEqualTo(4);
        assertThat(consume.getPullInterval()).isEqualTo(Duration.ofMillis(50));
        assertThat(consume.getPullBatchSize()).isEqualTo(256);
    }

    /** A default that only lives in the class is not the default the application starts with. */
    @Test
    void applicationYamlShipsTheSameConsumeDefaults() throws IOException {
        SeckillProperties.Consume consume = bindConsumeFromApplicationYaml();

        assertThat(consume.getBatchSize()).isEqualTo(256);
        assertThat(consume.getThreadCount()).isEqualTo(4);
        assertThat(consume.getPullInterval()).isEqualTo(Duration.ofMillis(50));
        assertThat(consume.getPullBatchSize()).isEqualTo(256);
    }

    /** Binds application.yaml the way Boot does, with no environment variable set. */
    private static SeckillProperties.Consume bindConsumeFromApplicationYaml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("env", new HashMap<>()));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment)
                .bind("local-deals.seckill.consume", SeckillProperties.Consume.class).get();
    }
}
