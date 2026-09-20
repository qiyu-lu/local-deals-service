package com.localdeals.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trace id is the only thing tying one buyer's request to the instance that admitted it, the
 * consumer that persisted it and the row it became. It has to exist on every request, survive
 * the edge that forwards it, and never let the caller decide what a log line says.
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void aRequestWithoutATraceHeaderGetsOne() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(new MockHttpServletRequest("POST", "/voucher-order/seckill/7"), response, chain);

        assertThat(chain.traces).hasSize(1);
        assertThat(chain.traces.get(0)).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(TraceContext.HEADER)).isEqualTo(chain.traces.get(0));
    }

    @Test
    void theEdgesTraceIdIsKept() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher-order/seckill/7");
        request.addHeader(TraceContext.HEADER, "1f0c9a3b4d5e6f708192a3b4c5d6e7f8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.traces).containsExactly("1f0c9a3b4d5e6f708192a3b4c5d6e7f8");
        assertThat(response.getHeader(TraceContext.HEADER)).isEqualTo("1f0c9a3b4d5e6f708192a3b4c5d6e7f8");
    }

    /**
     * The header reaches every log line of the request, so a caller that may write into it may
     * forge log lines. Anything but the accepted alphabet is replaced, not sanitised in place.
     */
    @Test
    void aForgedTraceHeaderIsReplacedInsteadOfLogged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher-order/seckill/7");
        request.addHeader(TraceContext.HEADER, "abc\n2026-09-21 ERROR [] forged line");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.traces.get(0)).matches("[0-9a-f]{32}");
    }

    @Test
    void anOverlongTraceHeaderIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher-order/seckill/7");
        request.addHeader(TraceContext.HEADER, "a".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.traces.get(0)).matches("[0-9a-f]{32}");
    }

    /** A pooled request thread must not start the next request holding the previous trace. */
    @Test
    void theTraceIsRemovedFromTheThreadWhenTheRequestEnds() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/shop/1"),
                new MockHttpServletResponse(), new RecordingChain());

        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    @Test
    void theTraceIsRemovedEvenWhenTheRequestFails() {
        FilterChain failing = (request, response) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/shop/1"),
                new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    /** Two requests never share a trace, so one buyer's line cannot be read as another's. */
    @Test
    void eachRequestGetsItsOwnTrace() throws Exception {
        RecordingChain chain = new RecordingChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/shop/1"), new MockHttpServletResponse(), chain);
        filter.doFilter(new MockHttpServletRequest("GET", "/shop/1"), new MockHttpServletResponse(), chain);

        assertThat(chain.traces).doesNotHaveDuplicates();
    }

    private static final class RecordingChain implements FilterChain {
        private final List<String> traces = new ArrayList<>();

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                throws IOException, ServletException {
            traces.add(MDC.get(TraceContext.MDC_KEY));
            assertThat(TraceContext.current()).isEqualTo(MDC.get(TraceContext.MDC_KEY));
            // The response header has to be set before anything downstream can commit it.
            assertThat(((HttpServletResponse) response).getHeader(TraceContext.HEADER)).isNotNull();
            assertThat(((HttpServletRequest) request).getRequestURI()).isNotNull();
        }
    }
}
