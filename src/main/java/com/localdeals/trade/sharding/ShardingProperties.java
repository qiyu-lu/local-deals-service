package com.localdeals.trade.sharding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the second order database lives.
 *
 * <p>It is derived from {@code spring.datasource.url} by default, so a deployment that already
 * points the application at a database gains a schema rather than a second set of connection
 * settings. Point {@link #url} somewhere else to put it on another server — the only thing that
 * then stops working is V16's carry-over of the pre-shard rows, which reads across schemas.</p>
 */
@Data
@ConfigurationProperties(prefix = "local-deals.sharding")
public class ShardingProperties {

    /** Appended to the first database's schema name when {@link #url} is blank. */
    private String secondSchemaSuffix = "_1";

    /** The second database's JDBC URL. Blank derives it from the first one. */
    private String url = "";

    /** The second database's credentials. Blank reuses the first one's. */
    private String username = "";
    private String password = "";

    /** The second database's URL, given the first one's. */
    public String urlFrom(String primaryUrl) {
        if (!url.isBlank()) {
            return url;
        }
        String schema = JdbcUrls.schemaOf(primaryUrl);
        return JdbcUrls.withSchema(primaryUrl, schema + secondSchemaSuffix);
    }
}
