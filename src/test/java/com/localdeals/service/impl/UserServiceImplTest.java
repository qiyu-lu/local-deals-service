package com.localdeals.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.localdeals.dto.LoginFormDTO;
import com.localdeals.dto.Result;
import com.localdeals.observability.LocalDealsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.localdeals.entity.User;
import com.localdeals.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.localdeals.utils.RedisConstants.LOGIN_CODE_FAILURE_KEY;
import static com.localdeals.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.localdeals.utils.RedisConstants.LOGIN_CODE_MAX_FAILURES;
import static com.localdeals.utils.RedisConstants.LOGIN_CODE_RATE_LIMIT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UserServiceImplTest {
    private static final String PHONE = "13800138000";
    private static final String CODE = "123456";

    private StringRedisTemplate redisTemplate;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        service = new UserServiceImpl(redisTemplate,
                new LocalDealsMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(service, "logVerificationCode", false);
    }

    @Test
    void sendCodeUsesAtomicPerPhoneCooldown() {
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any());

        Result result = service.sendCode(PHONE, null);

        assertTrue(result.getSuccess());
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        LOGIN_CODE_RATE_LIMIT_KEY + PHONE,
                        LOGIN_CODE_KEY + PHONE,
                        LOGIN_CODE_FAILURE_KEY + PHONE)),
                any(), eq("120"), eq("60"));
    }

    @Test
    void sendCodeRejectsRequestInsideCooldown() {
        doReturn(0L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(), any(), any());

        Result result = service.sendCode(PHONE, null);

        assertFalse(result.getSuccess());
    }

    @Test
    void invalidPhoneDoesNotTouchRedis() {
        Result result = service.sendCode("123", null);

        assertFalse(result.getSuccess());
        verify(redisTemplate, never()).execute(
                any(RedisScript.class), anyList(), any(), any(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void successfulLoginUsesSingleUseConsumeScript() {
        UserMapper userMapper = mock(UserMapper.class);
        User user = new User();
        user.setId(9L);
        user.setPhone(PHONE);
        user.setNickName("tester");
        doReturn(user).when(userMapper).selectOne(any(Wrapper.class));
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);

        HashOperations hashOperations = mock(HashOperations.class);
        doReturn(hashOperations).when(redisTemplate).opsForHash();
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(LOGIN_CODE_KEY + PHONE, LOGIN_CODE_FAILURE_KEY + PHONE)),
                eq(CODE), eq(String.valueOf(LOGIN_CODE_MAX_FAILURES)));

        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(PHONE);
        form.setCode(CODE);
        Result result = service.login(form, null);

        assertTrue(result.getSuccess(), String.valueOf(result.getErrorMsg()));
        assertNotNull(result.getData());

        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(
                script.capture(),
                eq(Arrays.asList(LOGIN_CODE_KEY + PHONE, LOGIN_CODE_FAILURE_KEY + PHONE)),
                eq(CODE), eq(String.valueOf(LOGIN_CODE_MAX_FAILURES)));
        assertTrue(script.getValue().getScriptAsString().contains("redis.call('del', codeKey, failureKey)"));
    }

    @Test
    void invalidOrAlreadyConsumedCodeStopsBeforeDatabaseLookup() {
        doReturn(0L).when(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(LOGIN_CODE_KEY + PHONE, LOGIN_CODE_FAILURE_KEY + PHONE)),
                eq(CODE), eq(String.valueOf(LOGIN_CODE_MAX_FAILURES)));

        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(PHONE);
        form.setCode(CODE);

        Result result = service.login(form, null);

        assertFalse(result.getSuccess());
    }

    @Test
    void codeMustBeExactlySixAsciiDigitsAndMalformedInputDoesNotConsumeAttempt() {
        String[] invalidCodes = {null, "", "12345", "1234567", "ABC123", "１２３４５６", " 123456"};

        for (String invalidCode : invalidCodes) {
            LoginFormDTO form = new LoginFormDTO();
            form.setPhone(PHONE);
            form.setCode(invalidCode);

            Result result = service.login(form, null);
            assertFalse(result.getSuccess(), "应拒绝验证码格式: " + invalidCode);
            assertEquals("验证码不正确", result.getErrorMsg());
        }

        verify(redisTemplate, never()).execute(
                any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void fifthFailureAndLockedStateExposeTheSameGenericError() {
        doReturn(0L, 0L, 0L, 0L, 2L, 2L).when(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(LOGIN_CODE_KEY + PHONE, LOGIN_CODE_FAILURE_KEY + PHONE)),
                eq("000000"), eq(String.valueOf(LOGIN_CODE_MAX_FAILURES)));

        for (int attempt = 1; attempt <= 6; attempt++) {
            LoginFormDTO form = new LoginFormDTO();
            form.setPhone(PHONE);
            form.setCode("000000");
            Result result = service.login(form, null);

            assertFalse(result.getSuccess());
            assertEquals("验证码不正确", result.getErrorMsg());
        }

        verify(redisTemplate, times(6)).execute(
                any(RedisScript.class),
                eq(Arrays.asList(LOGIN_CODE_KEY + PHONE, LOGIN_CODE_FAILURE_KEY + PHONE)),
                eq("000000"), eq(String.valueOf(LOGIN_CODE_MAX_FAILURES)));
    }

    @Test
    void luaScriptsKeepLockUntilOriginalTtlResetOnIssueAndCleanUpOnSuccess() throws Exception {
        String consumeScript = StreamUtils.copyToString(
                new ClassPathResource("lua/consume_login_code.lua").getInputStream(),
                StandardCharsets.UTF_8);
        String issueScript = StreamUtils.copyToString(
                new ClassPathResource("lua/issue_login_code.lua").getInputStream(),
                StandardCharsets.UTF_8);

        assertTrue(consumeScript.contains("if failures >= maxFailures then"));
        assertTrue(consumeScript.contains("redis.call('set', failureKey, tostring(maxFailures), 'PX', remainingTtl)"));
        assertTrue(consumeScript.contains("redis.call('del', codeKey)"));
        assertTrue(consumeScript.contains("redis.call('del', codeKey, failureKey)"));
        assertTrue(issueScript.contains("redis.call('del', KEYS[3])"));
    }
}
