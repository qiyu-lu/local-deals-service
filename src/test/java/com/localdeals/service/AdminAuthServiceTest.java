package com.localdeals.service;

import com.localdeals.config.AdminProperties;
import com.localdeals.dto.AdminLoginRequest;
import com.localdeals.dto.AdminLoginResponse;
import com.localdeals.dto.AdminPasswordChangeRequest;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.entity.AdminAccount;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.AdminAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuthServiceTest {
    private AdminAccountMapper accountMapper;
    private AdminSessionService sessionService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private PasswordEncoder passwordEncoder;
    private AdminAuthService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        accountMapper = mock(AdminAccountMapper.class);
        sessionService = mock(AdminSessionService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        AdminProperties properties = new AdminProperties();
        properties.setMaxLoginFailures(5);
        properties.setMaxIpLoginAttempts(30);
        properties.setLoginLockDuration(Duration.ofMinutes(15));
        service = new AdminAuthService(
                accountMapper, sessionService, redisTemplate, passwordEncoder, properties);
    }

    @Test
    void validCredentialsCreateAnIndependentAdminSession() {
        AdminAccount account = new AdminAccount();
        account.setId(7L);
        account.setUsername("merchant.owner");
        account.setPasswordHash("bcrypt-hash");
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(7L);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        principal.setMerchantId(21L);
        principal.setAuthVersion(3);
        when(values.get(anyString())).thenReturn(null);
        when(accountMapper.selectOne(any())).thenReturn(account);
        when(passwordEncoder.matches("correct-password", "bcrypt-hash")).thenReturn(true);
        when(sessionService.resolveAccount(7L)).thenReturn(principal);
        when(sessionService.sessionTtlSeconds()).thenReturn(1800L);

        AdminLoginResponse response = service.login(
                request("merchant.owner", "correct-password"), "127.0.0.1");

        assertThat(response.getToken()).matches("[0-9a-f]{32}");
        assertThat(response.getPrincipal()).isSameAs(principal);
        verify(sessionService).storeSession(response.getToken(), 7L, 3);
        verify(accountMapper).touchLastLogin(7L);
        org.mockito.InOrder authenticationOrder = inOrder(redisTemplate, accountMapper, passwordEncoder);
        authenticationOrder.verify(redisTemplate)
                .execute(any(RedisScript.class), anyList(), anyString());
        authenticationOrder.verify(accountMapper).selectOne(any());
        authenticationOrder.verify(passwordEncoder).matches("correct-password", "bcrypt-hash");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownAccountUsesDummyHashAndReturnsTheSameGenericError() {
        when(values.get(anyString())).thenReturn(null);
        when(accountMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.matches("wrong-password", "dummy-hash")).thenReturn(false);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);

        assertThatThrownBy(() -> service.login(
                request("unknown.user", "wrong-password"), "127.0.0.1"))
                .isInstanceOfSatisfying(ApiStatusException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(401);
                    assertThat(error.getMessage()).isEqualTo("账号或密码错误");
                });

        verify(passwordEncoder).matches("wrong-password", "dummy-hash");
        verify(sessionService, never()).storeSession(anyString(), any(), any());
    }

    @Test
    void lockedIdentityIsRejectedBeforeDatabaseLookup() {
        when(values.get(anyString())).thenReturn("5");

        assertThatThrownBy(() -> service.login(
                request("merchant.owner", "any-password"), "127.0.0.1"))
                .isInstanceOfSatisfying(ApiStatusException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(429));

        verify(accountMapper, never()).selectOne(any());
    }

    @Test
    void independentIpLimitStopsCredentialSprayingBeforeDatabaseLookup() {
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(31L);

        assertThatThrownBy(() -> service.login(
                request("different.user", "any-password"), "203.0.113.9"))
                .isInstanceOfSatisfying(ApiStatusException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(429));

        verify(accountMapper, never()).selectOne(any());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void configuredIpAttemptBoundaryStillReachesCredentialVerification() {
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(30L, 1L);
        when(accountMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.matches("any-password", "dummy-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(
                request("different.user", "any-password"), "203.0.113.9"))
                .isInstanceOfSatisfying(ApiStatusException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(401));

        verify(accountMapper).selectOne(any());
        verify(passwordEncoder).matches("any-password", "dummy-hash");
    }

    @Test
    void nullIpAttemptResultFailsClosedBeforeDatabaseLookup() {
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.login(
                request("different.user", "any-password"), "203.0.113.9"))
                .isInstanceOfSatisfying(ApiStatusException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(429));

        verify(accountMapper, never()).selectOne(any());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void redisFailureReturnsServiceUnavailableBeforeDatabaseLookup() {
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis unavailable"));

        assertThatThrownBy(() -> service.login(
                request("different.user", "any-password"), "203.0.113.9"))
                .isInstanceOfSatisfying(ApiStatusException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(503));

        verify(accountMapper, never()).selectOne(any());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void passwordChangeUsesOptimisticCredentialVersion() {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(7L);
        principal.setAuthVersion(3);
        AdminAccount account = new AdminAccount();
        account.setId(7L);
        account.setStatus(1);
        account.setPasswordHash("old-hash");
        account.setAuthVersion(3);
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(passwordEncoder.matches("current-password", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("new-password-123", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("new-password-123")).thenReturn("new-hash");
        when(accountMapper.updatePasswordAndBumpVersion(7L, 3, "new-hash")).thenReturn(1);
        AdminPasswordChangeRequest request = new AdminPasswordChangeRequest();
        request.setCurrentPassword("current-password");
        request.setNewPassword("new-password-123");

        service.changePassword(principal, request);

        verify(accountMapper).updatePasswordAndBumpVersion(7L, 3, "new-hash");
    }

    private AdminLoginRequest request(String username, String password) {
        AdminLoginRequest request = new AdminLoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
