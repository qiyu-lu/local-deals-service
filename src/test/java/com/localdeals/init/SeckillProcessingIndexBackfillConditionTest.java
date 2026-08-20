package com.localdeals.init;

import com.localdeals.config.SeckillProperties;
import com.localdeals.service.SeckillOrderStateService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SeckillProcessingIndexBackfillConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SeckillProcessingIndexBackfillRunner.class);

    @Test
    void runnerIsAbsentWhenBackfillFlagIsMissing() {
        contextRunner.run(context -> assertThat(
                context.getBeansOfType(SeckillProcessingIndexBackfillRunner.class)).isEmpty());
    }

    @Test
    void runnerIsCreatedOnlyWhenBackfillIsExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "local-deals.seckill.reconciliation.backfill-on-startup=true")
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withBean(SeckillOrderStateService.class, () -> mock(SeckillOrderStateService.class))
                .withBean(SeckillProperties.class, SeckillProperties::new)
                .run(context -> assertThat(context)
                        .hasSingleBean(SeckillProcessingIndexBackfillRunner.class));
    }
}
