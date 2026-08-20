package com.localdeals.init;

import com.localdeals.config.BlogLikeProperties;
import com.localdeals.service.BlogLikeCutoverService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlogLikeCutoverGuardTest {

    @Test
    void closedGatesDoNotRequireMarker() {
        BlogLikeProperties properties = new BlogLikeProperties();
        BlogLikeCutoverService cutover = mock(BlogLikeCutoverService.class);

        assertThatCode(() -> new BlogLikeCutoverGuard(properties, cutover)
                .run(new DefaultApplicationArguments(new String[0])))
                .doesNotThrowAnyException();
        verify(cutover, never()).isCompleted();
    }

    @Test
    void openingEitherGateRequiresDurableCompletionMarker() {
        BlogLikeProperties properties = new BlogLikeProperties();
        properties.setWriteEnabled(true);
        BlogLikeCutoverService cutover = mock(BlogLikeCutoverService.class);
        when(cutover.isCompleted()).thenReturn(false);

        assertThatThrownBy(() -> new BlogLikeCutoverGuard(properties, cutover)
                .run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cutover marker");
    }

    @Test
    void completedCutoverAllowsWorkerAndWrites() {
        BlogLikeProperties properties = new BlogLikeProperties();
        properties.setWriteEnabled(true);
        properties.setWorkerEnabled(true);
        BlogLikeCutoverService cutover = mock(BlogLikeCutoverService.class);
        when(cutover.isCompleted()).thenReturn(true);

        assertThatCode(() -> new BlogLikeCutoverGuard(properties, cutover)
                .run(new DefaultApplicationArguments(new String[0])))
                .doesNotThrowAnyException();
    }
}
