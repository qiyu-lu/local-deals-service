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

@EnabledIfEnvironmentVariable(named = "M6A_ISOLATED", matches = "true")
class M6aFlywayIT {
    private static final Pattern RUN_ID = Pattern.compile("m6a_[a-z0-9][a-z0-9_-]{2,40}");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");
    private static final String SENTINEL_TABLE = "m6a_run_sentinel";
    private static final String TARGET_MARKER_TABLE = "m6a_target_marker";

    @Test
    void freshV1ToV9AndUpgradeV8ToV9PreserveExpectedSchemaAndRoles() throws Exception {
        String baseUrl = required("M6A_MYSQL_BASE_URL");
        String username = required("M6A_MYSQL_USERNAME");
        String password = required("M6A_MYSQL_PASSWORD");
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
                .target(MigrationVersion.fromVersion("8")).load().migrate();
        Flyway.configure().dataSource(schemaUrl(baseUrl, upgrade), username, password).load().migrate();

        assertSchema(schemaUrl(baseUrl, fresh), username, password);
        assertSchema(schemaUrl(baseUrl, upgrade), username, password);
        writeTargetMarker(baseUrl, username, password, fresh, runId);
        writeTargetMarker(baseUrl, username, password, upgrade, runId);
    }

    private void recreateSchema(String baseUrl, String username, String password, String runId,
            String sentinelSchema, String schema)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
             Statement statement = connection.createStatement()) {
            assertMysqlSentinel(connection, sentinelSchema, runId);
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
            statement.execute("CREATE TABLE `" + schema + "`.`" + TARGET_MARKER_TABLE + "` (" +
                    "run_id varchar(64) NOT NULL PRIMARY KEY, " +
                    "purpose varchar(64) NOT NULL) ENGINE=InnoDB");
            try (PreparedStatement marker = connection.prepareStatement(
                    "INSERT INTO `" + schema + "`.`" + TARGET_MARKER_TABLE + "` " +
                            "(run_id,purpose) VALUES (?,?)")) {
                marker.setString(1, runId);
                marker.setString(2, "m6a-flyway-target");
                marker.executeUpdate();
            }
        }
    }

    private void assertMysqlSentinel(Connection connection, String sentinelSchema, String runId)
            throws Exception {
        if (!schemaExists(connection, sentinelSchema)) {
            throw new IllegalStateException("M6A MySQL sentinel schema is missing: " + sentinelSchema);
        }
        String sql = "SELECT run_id FROM `" + sentinelSchema + "`.`" + SENTINEL_TABLE + "` " +
                "WHERE run_id=? AND purpose='m6a-isolated-mysql'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !runId.equals(result.getString(1)) || result.next()) {
                    throw new IllegalStateException("M6A MySQL sentinel mismatch for " + runId);
                }
            }
        }
    }

    private void assertTargetMarker(Connection connection, String schema, String runId)
            throws Exception {
        String sql = "SELECT run_id FROM `" + schema + "`.`" + TARGET_MARKER_TABLE + "` " +
                "WHERE run_id=? AND purpose='m6a-flyway-target'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !runId.equals(result.getString(1)) || result.next()) {
                    throw new IllegalStateException("M6A target schema marker mismatch: " + schema);
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

    private void assertSchema(String url, String username, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")).isEqualTo(9);
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() " +
                            "AND table_name IN ('tb_marketing_tag','tb_marketing_tag_member'," +
                            "'tb_voucher_campaign','tb_voucher_grant')")).isEqualTo(4);
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM tb_admin_role_permission rp " +
                            "JOIN tb_admin_role r ON r.id=rp.role_id " +
                            "WHERE (r.code IN ('PLATFORM_ADMIN','MERCHANT_OWNER') " +
                            "AND rp.permission_code IN ('marketing:read','marketing:write')) " +
                            "OR (r.code='MERCHANT_STAFF' AND rp.permission_code='marketing:read')"))
                    .isEqualTo(5);
            assertThat(singleInt(statement,
                    "SELECT COUNT(*) FROM tb_admin_role_permission rp " +
                            "JOIN tb_admin_role r ON r.id=rp.role_id " +
                            "WHERE r.code='MERCHANT_STAFF' AND rp.permission_code='marketing:write'"))
                    .isZero();
        }
    }

    private int singleInt(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " must identify the isolated M6A MySQL");
        }
        return value;
    }

    private static String requiredRunId() {
        String runId = required("M6A_RUN_ID").trim();
        if (!RUN_ID.matcher(runId).matches()) {
            throw new IllegalStateException("M6A_RUN_ID must match m6a_[a-z0-9][a-z0-9_-]{2,40}");
        }
        return runId;
    }

    private static String requiredPort() {
        String port = required("M6A_MYSQL_PORT").trim();
        if (!PORT.matcher(port).matches() || Integer.parseInt(port) < 1024 || Integer.parseInt(port) > 65534) {
            throw new IllegalStateException("M6A_MYSQL_PORT must be an unprivileged TCP port");
        }
        return port;
    }

    private static String targetSchema(String runId, String suffix) {
        String schema = runId + "_" + suffix;
        if (!schema.matches(Pattern.quote(runId) + "_(fresh|upgrade)")) {
            throw new IllegalStateException("Unsafe M6A target schema: " + schema);
        }
        return schema;
    }

    private static void assertBaseUrlTargetsLoopback(String baseUrl, String expectedPort) {
        String prefix = beforeQuery(baseUrl);
        String expected = "jdbc:mysql://127.0.0.1:" + expectedPort + "/";
        if (!prefix.equals(expected)) {
            throw new IllegalStateException("M6A_MYSQL_BASE_URL must be the verified loopback base URL " +
                    expected + " without a database name");
        }
    }

    private static String beforeQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static String schemaUrl(String baseUrl, String schema) {
        String prefix = beforeQuery(baseUrl);
        if (!prefix.endsWith("/")) {
            throw new IllegalArgumentException("M6A_MYSQL_BASE_URL must end with / before query parameters");
        }
        int query = baseUrl.indexOf('?');
        return prefix + schema + (query < 0 ? "" : baseUrl.substring(query));
    }
}
