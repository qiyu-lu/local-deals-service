package com.localdeals.trade.sharding;

/**
 * Reads and rewrites the schema of a JDBC URL.
 *
 * <p>The order domain runs on two databases. The second one is derived from the first rather
 * than configured separately, so a deployment gains a schema instead of a second set of
 * connection settings — which is what keeps the benchmark scripts and the isolated stack working
 * with the variables they already set.</p>
 */
public final class JdbcUrls {

    private JdbcUrls() {
    }

    /** The schema a URL points at, e.g. {@code local_deals}. */
    public static String schemaOf(String url) {
        int start = schemaStart(url);
        int end = schemaEnd(url, start);
        if (end <= start) {
            throw new IllegalArgumentException("JDBC URL names no schema: " + url);
        }
        return url.substring(start, end);
    }

    /** The same server and parameters, pointed at another schema. */
    public static String withSchema(String url, String schema) {
        int start = schemaStart(url);
        return url.substring(0, start) + schema + url.substring(schemaEnd(url, start));
    }

    private static int schemaStart(String url) {
        int hostStart = url.indexOf("//");
        int slash = hostStart < 0 ? -1 : url.indexOf('/', hostStart + 2);
        if (slash < 0) {
            throw new IllegalArgumentException("JDBC URL names no schema: " + url);
        }
        return slash + 1;
    }

    private static int schemaEnd(String url, int start) {
        int query = url.indexOf('?', start);
        return query < 0 ? url.length() : query;
    }
}
