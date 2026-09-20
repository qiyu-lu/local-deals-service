package com.localdeals.platform.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The trace itself: what counts as one, and how a worker thread borrows one. */
class TraceContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void aNewTraceLooksLikeNginxRequestId() {
        assertThat(TraceContext.newTraceId()).matches("[0-9a-f]{32}");
    }

    @Test
    void acceptsWhatAnEdgeOrAnotherServiceWouldSend() {
        assertThat(TraceContext.accept("1f0c9a3b4d5e6f708192a3b4c5d6e7f8"))
                .isEqualTo("1f0c9a3b4d5e6f708192a3b4c5d6e7f8");
        assertThat(TraceContext.accept("trace-id_42")).isEqualTo("trace-id_42");
    }

    @Test
    void refusesAnythingThatCouldForgeALogLine() {
        assertThat(TraceContext.accept(null)).isNull();
        assertThat(TraceContext.accept("")).isNull();
        assertThat(TraceContext.accept("  ")).isNull();
        assertThat(TraceContext.accept("has space")).isNull();
        assertThat(TraceContext.accept("two\nlines")).isNull();
        assertThat(TraceContext.accept("a".repeat(65))).isNull();
    }

    /**
     * A consume thread is pooled and handles one message after another, so the trace has to be
     * put back exactly as it was found.
     */
    @Test
    void runningWithATraceRestoresWhateverTheThreadHadBefore() {
        MDC.put(TraceContext.MDC_KEY, "outer");

        TraceContext.runWith("inner", () -> assertThat(TraceContext.current()).isEqualTo("inner"));

        assertThat(TraceContext.current()).isEqualTo("outer");
    }

    @Test
    void runningWithoutATraceLeavesTheThreadWithoutOne() {
        TraceContext.runWith(null, () -> assertThat(TraceContext.current()).isNull());

        assertThat(TraceContext.current()).isNull();
    }

    @Test
    void theTraceIsRestoredWhenTheBodyThrows() {
        MDC.put(TraceContext.MDC_KEY, "outer");

        assertThatThrownBy(() -> TraceContext.runWith("inner", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TraceContext.current()).isEqualTo("outer");
    }

    @Test
    void aTraceDoesNotLeakIntoAnotherThread() throws Exception {
        MDC.put(TraceContext.MDC_KEY, "request-thread");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> other = pool.submit(TraceContext::current);
            assertThat(other.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }
}
