package com.localdeals.service;

import com.localdeals.config.BlogHotRankProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BlogHotRankRefreshSchedulerTest {

    @Test
    void classIsFailClosedUnlessRefreshIsExplicitlyEnabled() {
        ConditionalOnProperty condition =
                BlogHotRankRefreshScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("local-deals.blog-hot-rank");
        assertThat(condition.name()).containsExactly("refresh-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void disabledRuntimeGateDoesNotInvokeTheBuilder() {
        BlogHotRankService service = mock(BlogHotRankService.class);
        BlogHotRankProperties properties = new BlogHotRankProperties();
        BlogHotRankRefreshScheduler scheduler =
                new BlogHotRankRefreshScheduler(service, properties);

        scheduler.rebuildIfEnabled();

        verify(service, never()).rebuild();
    }

    @Test
    void enabledRuntimeGateInvokesOneRebuild() {
        BlogHotRankService service = mock(BlogHotRankService.class);
        BlogHotRankProperties properties = new BlogHotRankProperties();
        properties.setRefreshEnabled(true);
        BlogHotRankRefreshScheduler scheduler =
                new BlogHotRankRefreshScheduler(service, properties);

        scheduler.rebuildIfEnabled();

        verify(service).rebuild();
    }

    @Test
    void enabledSpringContextParsesBoundDurationSpelOnJavaEight() {
        new ApplicationContextRunner()
                .withPropertyValues("local-deals.blog-hot-rank.refresh-enabled=true")
                .withUserConfiguration(EnabledSchedulerContext.class)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).hasSingleBean(BlogHotRankRefreshScheduler.class);
                    verify(context.getBean(BlogHotRankService.class), never()).rebuild();
                });
    }

    @Configuration
    @EnableScheduling
    @Import(BlogHotRankRefreshScheduler.class)
    static class EnabledSchedulerContext {

        @Bean
        BlogHotRankProperties blogHotRankProperties() {
            BlogHotRankProperties properties = new BlogHotRankProperties();
            properties.setRefreshEnabled(true);
            properties.setInitialDelay(Duration.ofHours(1));
            properties.setFixedDelay(Duration.ofHours(1));
            properties.setMaxStale(Duration.ofHours(2));
            return properties;
        }

        @Bean
        BlogHotRankService hotRankService() {
            return mock(BlogHotRankService.class);
        }
    }
}
