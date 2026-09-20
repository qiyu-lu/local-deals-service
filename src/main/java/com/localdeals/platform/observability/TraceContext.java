package com.localdeals.platform.observability;

import org.slf4j.MDC;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The one identifier that follows a buyer's request across instances: into the log lines of the
 * instance that admitted it, into the message that carries it, into the instance that persists
 * it, and into the order row itself.
 *
 * <p>It lives in the SLF4J {@link MDC}, so every log line picks it up through the
 * {@code %X{traceId}} in the logging pattern instead of being passed one argument at a time. MDC
 * is thread-bound: a thread that takes work from somewhere else — a consume thread draining a
 * batch — has to put the trace on itself with {@link #runWith}, and has to give the thread back
 * the way it found it.</p>
 */
public final class TraceContext {

    /** The key the logging pattern reads. */
    public static final String MDC_KEY = "traceId";

    /** Request header, also written back on the response. nginx sets it from {@code $request_id}. */
    public static final String HEADER = "X-Trace-Id";

    /** Long enough for 128 random bits in hex, short enough to keep a log line readable. */
    static final int MAX_LENGTH = 64;

    private TraceContext() {
    }

    /** 32 hex characters, the shape nginx's {@code $request_id} already has. */
    public static String newTraceId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return hex(random.nextLong()) + hex(random.nextLong());
    }

    /**
     * The trace to use for an identifier that arrived from outside — a header, a message.
     *
     * @return the identifier when it is one this application is willing to log, else null; the
     * caller then mints a fresh one. Unacceptable input is never repaired, because a repaired
     * identifier still came from the caller and no longer matches what the sender recorded.
     */
    public static String accept(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return null;
            }
        }
        return candidate;
    }

    /** The trace of the calling thread, or null. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** Runs {@code body} under {@code traceId}, then restores whatever the thread had before. */
    public static void runWith(String traceId, Runnable body) {
        String previous = MDC.get(MDC_KEY);
        apply(traceId);
        try {
            body.run();
        } finally {
            apply(previous);
        }
    }

    static void apply(String traceId) {
        if (traceId == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, traceId);
        }
    }

    private static String hex(long value) {
        return String.format("%016x", value);
    }
}
