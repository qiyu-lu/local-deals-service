package com.localdeals.service;

import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.localdeals.config.AdminProperties;
import com.localdeals.dto.AdminLoginRequest;
import com.localdeals.dto.AdminLoginResponse;
import com.localdeals.dto.AdminPasswordChangeRequest;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.entity.AdminAccount;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.mapper.AdminAccountMapper;
import com.localdeals.observability.LocalDealsMetrics;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.localdeals.utils.RedisConstants.ADMIN_LOGIN_FAILURE_KEY;
import static com.localdeals.utils.RedisConstants.ADMIN_LOGIN_IP_ATTEMPT_KEY;

@Service
public class AdminAuthService {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9._-]{4,64}");
    private static final String LOGIN_ERROR = "账号或密码错误";
    private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT;

    static {
        RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>();
        RECORD_FAILURE_SCRIPT.setLocation(new ClassPathResource("lua/record_admin_login_failure.lua"));
        RECORD_FAILURE_SCRIPT.setResultType(Long.class);
    }

    private final AdminAccountMapper adminAccountMapper;
    private final AdminSessionService adminSessionService;
    private final StringRedisTemplate stringRedisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties adminProperties;
    private final LocalDealsMetrics metrics;
    private final String dummyPasswordHash;

    public AdminAuthService(AdminAccountMapper adminAccountMapper,
            AdminSessionService adminSessionService,
            StringRedisTemplate stringRedisTemplate,
            PasswordEncoder passwordEncoder,
            AdminProperties adminProperties,
            LocalDealsMetrics metrics) {
        this.adminAccountMapper = adminAccountMapper;
        this.adminSessionService = adminSessionService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.passwordEncoder = passwordEncoder;
        this.adminProperties = adminProperties;
        this.metrics = metrics;
        this.dummyPasswordHash = passwordEncoder.encode("local-deals-dummy-admin-password");
    }

    public AdminLoginResponse login(AdminLoginRequest request, String remoteAddress) {
        try {
            AdminLoginResponse response = doLogin(request, remoteAddress);
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.ADMIN_LOGIN,
                    LocalDealsMetrics.AuthResult.SUCCESS);
            return response;
        } catch (ApiStatusException failure) {
            LocalDealsMetrics.AuthResult result;
            if (failure.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                result = LocalDealsMetrics.AuthResult.LOCKED;
            } else if (failure.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                result = LocalDealsMetrics.AuthResult.UNAVAILABLE;
            } else {
                result = LocalDealsMetrics.AuthResult.REJECTED;
            }
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.ADMIN_LOGIN, result);
            throw failure;
        } catch (IllegalArgumentException invalidInput) {
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.ADMIN_LOGIN,
                    LocalDealsMetrics.AuthResult.INVALID_INPUT);
            throw invalidInput;
        } catch (RuntimeException failure) {
            metrics.recordAuth(LocalDealsMetrics.AuthFlow.ADMIN_LOGIN,
                    LocalDealsMetrics.AuthResult.FAILURE);
            throw failure;
        }
    }

    private AdminLoginResponse doLogin(AdminLoginRequest request, String remoteAddress) {
        String username = normalizeUsername(request == null ? null : request.getUsername());
        String password = request == null ? null : request.getPassword();
        String failureKey = failureKey(username, remoteAddress);
        String ipAttemptKey = ipAttemptKey(remoteAddress);
        if (isLocked(failureKey, maxFailures())) {
            throw new ApiStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    ApiErrorCodes.ADMIN_LOGIN_RATE_LIMITED, "登录失败次数过多，请稍后再试");
        }
        long ipAttempts = incrementCounter(ipAttemptKey, (long) maxIpAttempts() + 1L);
        if (ipAttempts > maxIpAttempts()) {
            throw new ApiStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    ApiErrorCodes.ADMIN_LOGIN_RATE_LIMITED, "登录请求过于频繁，请稍后再试");
        }

        AdminAccount account = username == null ? null : adminAccountMapper.selectOne(
                new QueryWrapper<AdminAccount>().eq("username", username).last("LIMIT 1"));
        boolean passwordMatches = false;
        if (password != null && password.length() <= 256) {
            try {
                String candidateHash = account == null ? dummyPasswordHash : account.getPasswordHash();
                passwordMatches = passwordEncoder.matches(password, candidateHash) && account != null;
            } catch (RuntimeException ignored) {
                passwordMatches = false;
            }
        }
        AdminPrincipal principal = passwordMatches
                ? adminSessionService.resolveAccount(account.getId())
                : null;
        if (principal == null) {
            long failures = incrementCounter(failureKey, maxFailures());
            if (failures >= maxFailures()) {
                throw new ApiStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        ApiErrorCodes.ADMIN_LOGIN_RATE_LIMITED, "登录失败次数过多，请稍后再试");
            }
            throw new ApiStatusException(HttpStatus.UNAUTHORIZED, LOGIN_ERROR);
        }

        stringRedisTemplate.delete(failureKey);
        String token = UUID.randomUUID().toString(true);
        adminSessionService.storeSession(
                token, principal.getAccountId(), principal.getAuthVersion());
        adminAccountMapper.touchLastLogin(principal.getAccountId());
        return new AdminLoginResponse(token, adminSessionService.sessionTtlSeconds(), principal);
    }

    public void logout(String token) {
        adminSessionService.revoke(token);
    }

    @Transactional
    public void changePassword(AdminPrincipal principal, AdminPasswordChangeRequest request) {
        if (principal == null || principal.getAccountId() == null) {
            throw new ApiStatusException(HttpStatus.UNAUTHORIZED, "后台登录已失效");
        }
        String currentPassword = request == null ? null : request.getCurrentPassword();
        String newPassword = request == null ? null : request.getNewPassword();
        validateNewPassword(newPassword);
        AdminAccount account = adminAccountMapper.selectById(principal.getAccountId());
        boolean currentMatches = false;
        if (account != null && Integer.valueOf(1).equals(account.getStatus()) &&
                currentPassword != null && currentPassword.length() <= 256) {
            try {
                currentMatches = passwordEncoder.matches(currentPassword, account.getPasswordHash());
            } catch (RuntimeException ignored) {
                currentMatches = false;
            }
        }
        if (!currentMatches) {
            throw new IllegalArgumentException("原密码不正确");
        }
        if (passwordEncoder.matches(newPassword, account.getPasswordHash())) {
            throw new IllegalArgumentException("新密码不能与原密码相同");
        }
        int changed = adminAccountMapper.updatePasswordAndBumpVersion(
                account.getId(),
                account.getAuthVersion() == null ? 0 : account.getAuthVersion(),
                passwordEncoder.encode(newPassword));
        if (changed != 1) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "账号凭据已变化，请重新登录后再试");
        }
    }

    public static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return USERNAME_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    static void validateNewPassword(String password) {
        int encodedLength = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (password == null || password.length() < 12 || encodedLength > 72) {
            throw new IllegalArgumentException("密码至少 12 位且 UTF-8 编码不能超过 72 字节");
        }
    }

    private boolean isLocked(String key, int maximumFailures) {
        final String value;
        try {
            value = stringRedisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            throw loginThrottleUnavailable();
        }
        if (value == null) {
            return false;
        }
        try {
            return Long.parseLong(value) >= maximumFailures;
        } catch (NumberFormatException e) {
            throw loginThrottleUnavailable();
        }
    }

    private long incrementCounter(String key, long nullFallback) {
        final Long count;
        try {
            count = stringRedisTemplate.execute(
                    RECORD_FAILURE_SCRIPT,
                    Collections.singletonList(key),
                    Long.toString(lockDurationSeconds())
            );
        } catch (RuntimeException e) {
            throw loginThrottleUnavailable();
        }
        return count == null ? nullFallback : count;
    }

    private ApiStatusException loginThrottleUnavailable() {
        return new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.ADMIN_LOGIN_UNAVAILABLE, "后台登录暂不可用");
    }

    private int maxFailures() {
        return Math.max(1, adminProperties.getMaxLoginFailures());
    }

    private int maxIpAttempts() {
        return Math.max(maxFailures(), adminProperties.getMaxIpLoginAttempts());
    }

    private long lockDurationSeconds() {
        if (adminProperties.getLoginLockDuration() == null ||
                adminProperties.getLoginLockDuration().isNegative() ||
                adminProperties.getLoginLockDuration().isZero()) {
            return TimeUnit.MINUTES.toSeconds(15);
        }
        return adminProperties.getLoginLockDuration().getSeconds();
    }

    private String failureKey(String username, String remoteAddress) {
        String identity = (username == null ? "invalid" : username) + "|" +
                (remoteAddress == null ? "unknown" : remoteAddress);
        return hashedKey(ADMIN_LOGIN_FAILURE_KEY, identity);
    }

    private String ipAttemptKey(String remoteAddress) {
        return hashedKey(ADMIN_LOGIN_IP_ATTEMPT_KEY,
                remoteAddress == null ? "unknown" : remoteAddress);
    }

    private String hashedKey(String prefix, String value) {
        return prefix + DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8));
    }
}
