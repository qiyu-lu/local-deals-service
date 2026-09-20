package com.localdeals.trade.sharding;

import org.springframework.core.env.Environment;

/**
 * The worker id ShardingSphere's SNOWFLAKE generator stamps into the attached tables' primary
 * keys, which must differ between instances or two of them mint the same id in the same
 * millisecond.
 *
 * <p>Not the Redis lease that {@code WorkerIdLease} holds for the order number's Snowflake,
 * even though one identifier for both would be tidier: the sharded data source runs the Flyway
 * migrations, and taking the id from Redis would mean no database can be migrated while Redis
 * is down. The port is enough for what this project deploys — several instances on one host,
 * and a host cannot give two of them the same port. Across hosts the ports repeat, so a
 * deployment there sets {@code local-deals.sharding.worker-id} per instance.</p>
 */
public final class ShardingWorkerId {

    /** Replaced in sharding.yaml before ShardingSphere parses it. */
    public static final String PLACEHOLDER = "@workerId@";

    public static final String PROPERTY = "local-deals.sharding.worker-id";
    /** ShardingSphere's own ceiling: the id occupies ten bits. */
    public static final int MAX = 1023;

    private ShardingWorkerId() {
    }

    public static int resolve(Environment environment) {
        Integer explicit = environment.getProperty(PROPERTY, Integer.class);
        if (explicit == null) {
            return environment.getProperty("server.port", Integer.class, 8083) % (MAX + 1);
        }
        if (explicit < 0 || explicit > MAX) {
            throw new IllegalStateException(PROPERTY + " must be between 0 and " + MAX + ", was " + explicit);
        }
        return explicit;
    }

    public static String applyTo(String rules, int workerId) {
        return rules.replace(PLACEHOLDER, Integer.toString(workerId));
    }
}
