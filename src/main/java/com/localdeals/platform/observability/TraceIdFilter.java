package com.localdeals.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gives every request a trace id before anything else runs, and writes it back on the response
 * so the caller — a browser, a load generator, the next service — can quote it when asking what
 * happened to one request.
 *
 * <p>Ordered ahead of the interceptors: a request rejected by L1 of the seckill funnel, or by
 * authentication, is exactly the kind whose log line is worth finding.</p>
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = TraceContext.accept(request.getHeader(TraceContext.HEADER));
        if (traceId == null) {
            traceId = TraceContext.newTraceId();
        }
        TraceContext.apply(traceId);
        // Before the chain: a handler may commit the response, and a header set afterwards
        // would be dropped without a word.
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // The container hands this thread to the next request; it must not inherit a trace.
            TraceContext.apply(null);
        }
    }

    /** Async dispatches keep the trace of the request they belong to, so filter both. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
