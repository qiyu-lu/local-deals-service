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

@EnabledIfEnvironmentVariable(named = "M6C_ISOLATED", matches = "true")
class M6cFlywayIT {
    private static final Pattern RUN_ID = Pattern.compile("m6c_[a-z0-9][a-z0-9_-]{2,40}");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");
    private static final String SENTINEL_TABLE = "m6c_run_sentinel";
    private static final String TARGET_MARKER_TABLE = "m6c_target_marker";

    @Test
    void freshV1ToV11AndUpgradeV10ToV11KeepLegacyGrantsWithoutBackfillNotifications() throws Exception {
        String baseUrl = required("M6C_MYSQL_BASE_URL");
        String username = required("M6C_MYSQL_USERNAME");
        String password = required("M6C_MYSQL_PASSWORD");
        String runId = requiredRunId();
        String port = requiredPort();
        assertBaseUrlTargetsLoopback(baseUrl, port);

        String sentinelSchema = runId + "_sentinel";
        String fresh = targetSchema(runId, "fresh");
        String upgrade = targetSchema(runId, "upgrade");
        recreateSchema(baseUrl, username, password, runId, sentinelSchema, fresh);
        recreateSchema(baseUrl, username, password, runId, sentinelSchema, upgrade);

        Flyway.configure().dataSource(schemaUrl(baseUrl, fresh), username, password)
                .target(MigrationVersion.fromVersion("11")).load().migrate();
        Flyway.configure().dataSource(schemaUrl(baseUrl, upgrade), username, password)
                .target(MigrationVersion.fromVersion("10")).load().migrate();
        seedV10Grant(schemaUrl(baseUrl, upgrade), username, password);
        Flyway.configure().dataSource(schemaUrl(baseUrl, upgrade), username, password)
                .target(MigrationVersion.fromVersion("11")).load().migrate();

        assertSchema(schemaUrl(baseUrl, fresh), username, password, 0);
        assertSchema(schemaUrl(baseUrl, upgrade), username, password, 1);
        cleanupUpgradeFixture(schemaUrl(baseUrl, upgrade), username, password);
        writeTargetMarker(baseUrl, username, password, fresh, runId);
        writeTargetMarker(baseUrl, username, password, upgrade, runId);
    }

    @Test
    void upgradeV8ToV11InstallsAllLaterMigrationsWithoutHistoricalNotifications() throws Exception {
        String baseUrl = required("M6C_MYSQL_BASE_URL");
        String username = required("M6C_MYSQL_USERNAME");
        String password = required("M6C_MYSQL_PASSWORD");
        String runId = requiredRunId();
        String port = requiredPort();
        assertBaseUrlTargetsLoopback(baseUrl, port);

        String sentinelSchema = runId + "_sentinel";
        String upgrade = targetSchema(runId, "upgrade_v8");
        recreateSchema(baseUrl, username, password, runId, sentinelSchema, upgrade);

        Flyway.configure().dataSource(schemaUrl(baseUrl, upgrade), username, password)
                .target(MigrationVersion.fromVersion("8")).load().migrate();
        Flyway.configure().dataSource(schemaUrl(baseUrl, upgrade), username, password)
                .target(MigrationVersion.fromVersion("11")).load().migrate();

        assertSchema(schemaUrl(baseUrl, upgrade), username, password, 0);
        writeTargetMarker(baseUrl, username, password, upgrade, runId);
    }

    private void seedV10Grant(String url, String username, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO tb_admin_account " +
                    "(merchant_id,username,password_hash,display_name,scope_type,status) VALUES " +
                    "(1,'m6c.flyway.seed','$2a$10$012345678901234567890u012345678901234567890123456789012'," +
                    "'M6C Flyway Seed','MERCHANT',1)");
            statement.executeUpdate("INSERT INTO tb_voucher_campaign " +
                    "(merchant_id,voucher_id,name,grant_mode,eligibility_type,required_tag_id," +
                    "begin_time,end_time,quota_total,granted_count,status,rule_version,created_by) " +
                    "VALUES (1,1,'M6C_FLYWAY_SEED','BOTH','ALL',NULL," +
                    "CURRENT_TIMESTAMP - INTERVAL 1 MINUTE,CURRENT_TIMESTAMP + INTERVAL 1 DAY," +
                    "10,1,'ACTIVE',1,(SELECT id FROM tb_admin_account WHERE username='m6c.flyway.seed'))");
            statement.executeUpdate("INSERT INTO tb_voucher_grant " +
                    "(campaign_id,merchant_id,voucher_id,user_id,source,idempotency_key,rule_version,operator_id) " +
                    "VALUES ((SELECT id FROM tb_voucher_campaign WHERE name='M6C_FLYWAY_SEED')," +
                    "1,1,1,'USER_CLAIM','ONCE',1,NULL)");
        }
    }

    private void assertSchema(String url, String username, String password, int expectedLegacyGrants)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")).isEqualTo(11);
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() " +
                            "AND table_name IN ('tb_voucher_batch_job','tb_voucher_batch_item'," +
                            "'tb_voucher_grant_notification_outbox')")).isEqualTo(3);
            assertThat(singleString(statement,
                    "SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() " +
                            "AND table_name='tb_voucher_grant' AND column_name='source'")).contains("BATCH_GRANT");
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() " +
                            "AND table_name='tb_voucher_batch_item' " +
                            "AND index_name='uk_voucher_batch_item_job_user'")).isEqualTo(2);
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() " +
                            "AND table_name='tb_voucher_grant_notification_outbox' " +
                            "AND index_name='uk_voucher_grant_notification_grant'")).isEqualTo(1);
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM tb_voucher_grant_notification_outbox")).isZero();
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM tb_voucher_grant WHERE idempotency_key='ONCE'")).isEqualTo(expectedLegacyGrants);
            assertThat(singleString(statement,
                    "SELECT delete_rule FROM information_schema.referential_constraints " +
                            "WHERE constraint_schema=DATABASE() AND constraint_name=" +
                            "'fk_voucher_grant_notification_grant'")).isEqualTo("CASCADE");
        }
    }

    private void cleanupUpgradeFixture(String url, String username, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE g FROM tb_voucher_grant g JOIN tb_voucher_campaign c " +
                    "ON c.id=g.campaign_id WHERE c.name='M6C_FLYWAY_SEED'");
            statement.executeUpdate("DELETE FROM tb_voucher_campaign WHERE name='M6C_FLYWAY_SEED'");
            statement.executeUpdate("DELETE FROM tb_admin_account WHERE username='m6c.flyway.seed'");
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

    private void writeTargetMarker(String baseUrl, String username, String password,
            String schema, String runId) throws Exception {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE `" + schema + "`.`" + TARGET_MARKER_TABLE +
                    "` (run_id varchar(64) NOT NULL PRIMARY KEY, purpose varchar(64) NOT NULL) ENGINE=InnoDB");
            try (PreparedStatement marker = connection.prepareStatement(
                    "INSERT INTO `" + schema + "`.`" + TARGET_MARKER_TABLE +
                            "` (run_id,purpose) VALUES (?,?)")) {
                marker.setString(1, runId);
                marker.setString(2, "m6c-flyway-target");
                marker.executeUpdate();
            }
        }
    }

    private void assertSentinel(Connection connection, String schema, String runId) throws Exception {
        if (!schemaExists(connection, schema)) throw new IllegalStateException("M6C MySQL sentinel is missing");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT run_id FROM `" + schema + "`.`" + SENTINEL_TABLE +
                        "` WHERE run_id=? AND purpose='m6c-isolated-mysql'")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !runId.equals(result.getString(1)) || result.next()) {
                    throw new IllegalStateException("M6C MySQL sentinel mismatch");
                }
            }
        }
    }

    private void assertTargetMarker(Connection connection, String schema, String runId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT run_id FROM `" + schema + "`.`" + TARGET_MARKER_TABLE +
                        "` WHERE run_id=? AND purpose='m6c-flyway-target'")) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !runId.equals(result.getString(1)) || result.next()) {
                    throw new IllegalStateException("M6C target marker mismatch");
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Refusing destructive operation on unverified M6C schema: " + schema,
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

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(name + " is required");
        return value.trim();
    }

    private static String requiredRunId() {
        String value = required("M6C_RUN_ID");
        if (!RUN_ID.matcher(value).matches()) throw new IllegalStateException("invalid M6C_RUN_ID: " + value);
        return value;
    }

    private static String requiredPort() {
        String value = required("M6C_MYSQL_PORT");
        if (!PORT.matcher(value).matches() || Integer.parseInt(value) < 1024 ||
                Integer.parseInt(value) > 65534 || Integer.parseInt(value) == 3306) {
            throw new IllegalStateException("invalid M6C_MYSQL_PORT");
        }
        return value;
    }

    private static String targetSchema(String runId, String suffix) {
        String schema = runId + "_" + suffix;
        if (!schema.matches(Pattern.quote(runId) + "_(fresh|upgrade|upgrade_v8)")) {
            throw new IllegalStateException("unsafe M6C schema: " + schema);
        }
        return schema;
    }

    private static void assertBaseUrlTargetsLoopback(String url, String port) {
        String prefix = beforeQuery(url);
        if (!prefix.equals("jdbc:mysql://127.0.0.1:" + port + "/")) {
            throw new IllegalStateException("M6C MySQL base URL must target 127.0.0.1:" + port + " and no database");
        }
    }

    private static String beforeQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static String schemaUrl(String baseUrl, String schema) {
        String prefix = beforeQuery(baseUrl);
        if (!prefix.endsWith("/")) throw new IllegalArgumentException("M6C base URL must end with /");
        int query = baseUrl.indexOf('?');
        return prefix + schema + (query < 0 ? "" : baseUrl.substring(query));
    }
}
