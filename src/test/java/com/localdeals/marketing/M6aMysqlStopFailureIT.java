package com.localdeals.marketing;

import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.service.VoucherGrantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = M6aPersistenceTestConfiguration.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M6A_ISOLATED", matches = "true")
@EnabledIfEnvironmentVariable(named = "M6A_DOCKER_FAULT", matches = "true")
class M6aMysqlStopFailureIT {
    private static final String MYSQL_CONTAINER = "m6a-m6a_20260822c-mysql";
    private static final long CAMPAIGN_ID = 1L;
    private static final long USER_ID = 9_999_991L;

    @DynamicPropertySource
    static void registerM6aDatasource(DynamicPropertyRegistry registry) {
        M6aDatasourceGuard.register(registry);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private VoucherGrantService grantService;

    @Test
    void stoppingOwnedMysqlMapsTo503AndRecoveryHasNoGrantSideEffect() throws Exception {
        String runId = required("M6A_RUN_ID");
        assertThat(runId).isEqualTo("m6a_20260822c");
        assertThat(runDocker("inspect", "--format",
                "{{index .Config.Labels \"com.localdeals.m6a.run-id\"}}", MYSQL_CONTAINER))
                .isEqualTo(runId);
        assertThat(runDocker("inspect", "--format",
                "{{index .Config.Labels \"com.localdeals.m6a.role\"}}", MYSQL_CONTAINER))
                .isEqualTo("mysql");
        assertThat(jdbcTemplate.queryForObject("SELECT run_id FROM `" + runId + "_sentinel`.m6a_run_sentinel " +
                "WHERE purpose='m6a-isolated-mysql'", String.class)).isEqualTo(runId);
        int before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_grant WHERE campaign_id=? AND user_id=?",
                Integer.class, CAMPAIGN_ID, USER_ID);

        boolean stopped = false;
        try {
            runDocker("stop", MYSQL_CONTAINER);
            stopped = true;
            VoucherGrantCommand command = new VoucherGrantCommand();
            command.setCampaignId(CAMPAIGN_ID);
            command.setUserId(USER_ID);
            command.setExpectedRuleVersion(1L);
            command.setSource(VoucherGrantCommand.USER_CLAIM);
            assertThatThrownBy(() -> grantService.grant(command))
                    .isInstanceOf(ApiStatusException.class)
                    .satisfies(error -> {
                        ApiStatusException apiError = (ApiStatusException) error;
                        assertThat(apiError.getStatus().value()).isEqualTo(503);
                        assertThat(apiError.getCode()).isEqualTo(ApiErrorCodes.DATABASE_UNAVAILABLE);
                    });
        } finally {
            if (stopped) {
                runDocker("start", MYSQL_CONTAINER);
                waitForMysql();
            }
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_grant WHERE campaign_id=? AND user_id=?",
                Integer.class, CAMPAIGN_ID, USER_ID)).isEqualTo(before);
    }

    private void waitForMysql() throws Exception {
        for (int i = 0; i < 40; i++) {
            if (runDockerAllowFailure("exec", "-e", "MYSQL_PWD=" + required("M6A_MYSQL_PASSWORD"),
                    MYSQL_CONTAINER, "mysqladmin", "ping", "-h127.0.0.1", "-uroot")) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("c-run MySQL did not recover");
    }

    private String runDocker(String... args) throws Exception {
        ProcessResult result = runProcess(args);
        if (result.exitCode != 0) {
            throw new IllegalStateException("exact M6A docker command failed: " + result.output);
        }
        return result.output.trim();
    }

    private boolean runDockerAllowFailure(String... args) throws Exception {
        return runProcess(args).exitCode == 0;
    }

    private ProcessResult runProcess(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "docker";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = read(process.getInputStream());
        return new ProcessResult(process.waitFor(), output);
    }

    private String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " must identify the isolated M6A MySQL");
        }
        return value;
    }

    private static final class ProcessResult {
        private final int exitCode;
        private final String output;

        private ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
