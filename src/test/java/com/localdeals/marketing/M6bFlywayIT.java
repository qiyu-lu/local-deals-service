package com.localdeals.marketing;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "M6B_ISOLATED", matches = "true")
class M6bFlywayIT {
    private static final Pattern RUN_ID = Pattern.compile("m6b_[a-z0-9][a-z0-9_-]{2,40}");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");
    private static final String SENTINEL_TABLE = "m6b_run_sentinel";
    private static final String TARGET_MARKER_TABLE = "m6b_target_marker";

    @Test
    void freshV1ToV10AndUpgradeV9ToV10KeepLegacyGrantAndM6bKeys() throws Exception {
        String baseUrl = required("M6B_MYSQL_BASE_URL");
        String username = required("M6B_MYSQL_USERNAME");
        String password = required("M6B_MYSQL_PASSWORD");
        String runId = requiredRunId();
        String expectedPort = requiredPort();
        assertBaseUrlTargetsLoopback(baseUrl, expectedPort);
        String sentinelSchema = runId + "_sentinel";
        String fresh = targetSchema(runId, "fresh");
        String upgrade = targetSchema(runId, "upgrade");

        recreateSchema(baseUrl, username, password, runId, sentinelSchema, fresh);
        recreateSchema(baseUrl, username, password, runId, sentinelSchema, upgrade);

        Flyway.configure().dataSource(schemaUrl(baseUrl, fresh), username, password).load().migrate();
        Flyway.configure().dataSource(schemaUrl(baseUrl, upgrade), username, password)
                .target(MigrationVersion.fromVersion("9")).load().migrate();
        seedV9Grant(schemaUrl(baseUrl, upgrade), username, password);
        Flyway.configure().dataSource(schemaUrl(baseUrl, upgrade), username, password).load().migrate();

        assertSchema(schemaUrl(baseUrl, fresh), username, password, false);
        assertSchema(schemaUrl(baseUrl, upgrade), username, password, true);
        cleanupUpgradeFixture(schemaUrl(baseUrl, upgrade), username, password);
        assertThat(count(schemaUrl(baseUrl, upgrade), username, password,
                "SELECT COUNT(*) FROM tb_voucher_grant")).isZero();
        writeTargetMarker(baseUrl, username, password, fresh, runId);
        writeTargetMarker(baseUrl, username, password, upgrade, runId);
    }

    private void seedV9Grant(String url, String username, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO tb_admin_account " +
                    "(merchant_id,username,password_hash,display_name,scope_type,status) VALUES " +
                    "(1,'m6b.flyway.seed','$2a$10$012345678901234567890u012345678901234567890123456789012'," +
                    "'M6B Flyway Seed','MERCHANT',1)");
            statement.executeUpdate("INSERT INTO tb_voucher_campaign " +
                    "(merchant_id,voucher_id,name,grant_mode,eligibility_type,required_tag_id," +
                    "begin_time,end_time,quota_total,granted_count,status,rule_version,created_by) " +
                    "VALUES (1,1,'M6B_FLYWAY_SEED','BOTH','ALL',NULL," +
                    "CURRENT_TIMESTAMP - INTERVAL 1 MINUTE,CURRENT_TIMESTAMP + INTERVAL 1 DAY," +
                    "10,0,'ACTIVE',1,(SELECT id FROM tb_admin_account WHERE username='m6b.flyway.seed'))");
            statement.executeUpdate("INSERT INTO tb_voucher_grant " +
                    "(campaign_id,merchant_id,voucher_id,user_id,source,rule_version,operator_id) " +
                    "VALUES ((SELECT id FROM tb_voucher_campaign WHERE name='M6B_FLYWAY_SEED')," +
                    "1,1,1,'USER_CLAIM',1,NULL)");
        }
    }

    private void cleanupUpgradeFixture(String url, String username, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE g FROM tb_voucher_grant g JOIN tb_voucher_campaign c " +
                    "ON c.id=g.campaign_id WHERE c.name='M6B_FLYWAY_SEED'");
            statement.executeUpdate("DELETE FROM tb_voucher_campaign WHERE name='M6B_FLYWAY_SEED'");
            statement.executeUpdate("DELETE FROM tb_admin_account WHERE username='m6b.flyway.seed'");
        }
    }

    private void recreateSchema(String baseUrl, String username, String password, String runId,
            String sentinelSchema, String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
             Statement statement = connection.createStatement()) {
            assertSentinel(connection, sentinelSchema, runId);
            if (schemaExists(connection, schema)) {
                assertTargetMarker(connection, schema, runId);
                statement.execute("DROP DATABASE `" + schema + "`");
            }
            statement.execute("CREATE DATABASE `" + schema +
                    "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private void assertSchema(String url, String username, String password,
            boolean expectLegacyGrant) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")).isEqualTo(10);
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() " +
                            "AND table_name IN ('tb_sign','tb_marketing_tag','tb_marketing_tag_member'," +
                            "'tb_voucher_campaign','tb_voucher_grant')")).isEqualTo(5);
            assertThat(singleInt(statement,
                    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() " +
                            "AND table_name='tb_sign' AND index_name='uk_sign_user_date'")).isEqualTo(1);
            assertThat(singleInt(statement,
                    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() " +
                            "AND table_name='tb_voucher_grant' " +
                            "AND index_name='uk_voucher_grant_campaign_user_key'")).isEqualTo(1);
            assertThat(singleString(statement,
                    "SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() " +
                            "AND table_name='tb_voucher_campaign' AND column_name='grant_mode'"))
                    .contains("TASK");
            assertThat(singleString(statement,
                    "SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() " +
                            "AND table_name='tb_voucher_grant' AND column_name='source'"))
                    .contains("TASK_REWARD");
            assertThat(singleString(statement,
                    "SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() " +
                            "AND table_name='tb_voucher_grant' AND column_name='idempotency_key'"))
                    .isEqualTo("NO");
            int legacy = singleInt(statement,
                    "SELECT COUNT(*) FROM tb_voucher_grant WHERE idempotency_key='ONCE'");
            assertThat(legacy).isEqualTo(expectLegacyGrant ? 1 : 0);
        }
    }

    private void writeTargetMarker(String baseUrl, String username, String password,
            String schema, String runId) throws Exception {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE `" + schema + "`.`" + TARGET_MARKER_TABLE + "` (" +
                    "run_id varchar(64) NOT NULL PRIMARY KEY, purpose varchar(64) NOT NULL) ENGINE=InnoDB");
            try (PreparedStatement marker = connection.prepareStatement(
                    "INSERT INTO `" + schema + "`.`" + TARGET_MARKER_TABLE + "` " +
                            "(run_id,purpose) VALUES (?,?)")) {
                marker.setString(1, runId);
                marker.setString(2, "m6b-flyway-target");
                marker.executeUpdate();
            }
        }
    }

    private void assertSentinel(Connection connection, String schema, String runId) throws Exception {
        if (!schemaExists(connection, schema)) {
            throw new IllegalStateException("M6B MySQL sentinel schema is missing: " + schema);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT run_id FROM `" + schema + "`.`" + SENTINEL_TABLE + "` " +
                        "WHERE run_id=? AND purpose='m6b-isolated-mysql'")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !runId.equals(result.getString(1)) || result.next()) {
                    throw new IllegalStateException("M6B MySQL sentinel mismatch for " + runId);
                }
            }
        }
    }

    private void assertTargetMarker(Connection connection, String schema, String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT run_id FROM `" + schema + "`.`" + TARGET_MARKER_TABLE + "` " +
                        "WHERE run_id=? AND purpose='m6b-flyway-target'")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !runId.equals(result.getString(1)) || result.next()) {
                    throw new IllegalStateException("M6B target schema marker mismatch: " + schema);
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Refusing destructive operation on unverified schema: " + schema,
                    failure);
        }
    }

    private boolean schemaExists(Connection connection, String schema) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private int singleInt(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private String singleString(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private int count(String url, String username, String password, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            return singleInt(statement, sql);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(name + " is required");
        return value.trim();
    }

    private static String requiredRunId() {
        String value = required("M6B_RUN_ID");
        if (!RUN_ID.matcher(value).matches()) throw new IllegalStateException("invalid M6B_RUN_ID: " + value);
        return value;
    }

    private static String requiredPort() {
        String value = required("M6B_MYSQL_PORT");
        if (!PORT.matcher(value).matches() || Integer.parseInt(value) < 1024 ||
                Integer.parseInt(value) > 65534) throw new IllegalStateException("invalid M6B_MYSQL_PORT");
        return value;
    }

    private static String targetSchema(String runId, String suffix) {
        String schema = runId + "_" + suffix;
        if (!schema.matches(Pattern.quote(runId) + "_(fresh|upgrade)")) {
            throw new IllegalStateException("unsafe M6B schema: " + schema);
        }
        return schema;
    }

    private static void assertBaseUrlTargetsLoopback(String baseUrl, String port) {
        String prefix = beforeQuery(baseUrl);
        if (!prefix.equals("jdbc:mysql://127.0.0.1:" + port + "/")) {
            throw new IllegalStateException("M6B base URL must target verified loopback port " + port);
        }
    }

    private static String beforeQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static String schemaUrl(String baseUrl, String schema) {
        String prefix = beforeQuery(baseUrl);
        if (!prefix.endsWith("/")) throw new IllegalArgumentException("M6B base URL must end with /");
        int query = baseUrl.indexOf('?');
        return prefix + schema + (query < 0 ? "" : baseUrl.substring(query));
    }
}
