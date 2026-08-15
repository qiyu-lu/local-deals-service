package com.localdeals.service;

import com.localdeals.dto.LoginFormDTO;
import com.localdeals.dto.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.localdeals.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.localdeals.utils.RedisConstants.LOGIN_CODE_FAILURE_KEY;
import static com.localdeals.utils.RedisConstants.LOGIN_USER_KEY;
import static com.localdeals.utils.RedisConstants.LOGIN_CODE_RATE_LIMIT_KEY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for IUserService.
 * Connects to real MySQL and Redis (localhost, Docker port-mapped).
 */
@SpringBootTest
@ActiveProfiles("test")
class UserServiceIT {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private static final String TEST_PHONE = "13899991001";
    private static final String TEST_CODE  = "654321";
    private String issuedToken;

    @BeforeEach
    void seedVerificationCode() {
        jdbcTemplate.update("DELETE FROM tb_user WHERE phone = ?", TEST_PHONE);
        stringRedisTemplate.delete(LOGIN_CODE_FAILURE_KEY + TEST_PHONE);
        stringRedisTemplate.opsForValue().set(
                LOGIN_CODE_KEY + TEST_PHONE, TEST_CODE, 5, TimeUnit.MINUTES);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tb_user WHERE phone = ?", TEST_PHONE);
        stringRedisTemplate.delete(LOGIN_CODE_KEY + TEST_PHONE);
        stringRedisTemplate.delete(LOGIN_CODE_FAILURE_KEY + TEST_PHONE);
        stringRedisTemplate.delete(LOGIN_CODE_RATE_LIMIT_KEY + TEST_PHONE);
        if (issuedToken != null) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + issuedToken);
            issuedToken = null;
        }
    }

    /**
     * Bug B1: UserServiceImpl.login() calls fieldValue.toString() without a null check.
     * New users have icon = null, causing NPE when building the Redis Hash.
     * After fix: null is converted to "" and login succeeds.
     */
    @Test
    void login_newUserWithNullIcon_doesNotThrowNPE() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(TEST_PHONE);
        form.setCode(TEST_CODE);

        Result result = userService.login(form, null);

        assertTrue(result.getSuccess(), "Login must succeed for a new user: " + result.getErrorMsg());
        assertNotNull(result.getData(), "Login must return a token");
        issuedToken = (String) result.getData();
        assertNull(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + TEST_PHONE));
        assertNull(stringRedisTemplate.opsForValue().get(LOGIN_CODE_FAILURE_KEY + TEST_PHONE));
    }

    @Test
    void fifthWrongCodeLocksOriginalCodeAndNewIssueResetsAttemptBudget() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(TEST_PHONE);
        form.setCode("000000");

        for (int attempt = 0; attempt < 5; attempt++) {
            Result result = userService.login(form, null);
            assertFalse(result.getSuccess());
            assertEquals("验证码不正确", result.getErrorMsg());
        }

        assertNull(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + TEST_PHONE));
        assertEquals("5", stringRedisTemplate.opsForValue().get(LOGIN_CODE_FAILURE_KEY + TEST_PHONE));

        Result issueResult = userService.sendCode(TEST_PHONE, null);
        assertTrue(issueResult.getSuccess());
        assertNull(stringRedisTemplate.opsForValue().get(LOGIN_CODE_FAILURE_KEY + TEST_PHONE));
        assertNotNull(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + TEST_PHONE));
    }

    @Test
    void malformedCodeDoesNotConsumeFailureBudget() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(TEST_PHONE);
        form.setCode("ABC123");

        Result result = userService.login(form, null);

        assertFalse(result.getSuccess());
        assertNull(stringRedisTemplate.opsForValue().get(LOGIN_CODE_FAILURE_KEY + TEST_PHONE));
    }
}
