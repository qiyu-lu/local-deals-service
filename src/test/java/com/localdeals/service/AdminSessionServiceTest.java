package com.localdeals.service;

import com.localdeals.config.AdminProperties;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.entity.AdminAccount;
import com.localdeals.entity.Merchant;
import com.localdeals.mapper.AdminAccountMapper;
import com.localdeals.mapper.MerchantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import static com.localdeals.auth.AdminRoleCodes.MERCHANT_STAFF;
import static com.localdeals.auth.AdminRoleCodes.PLATFORM_ADMIN;

import static com.localdeals.utils.RedisConstants.ADMIN_LOGIN_TOKEN_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSessionServiceTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private AdminAccountMapper accountMapper;
    private MerchantMapper merchantMapper;
    private AdminSessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        accountMapper = mock(AdminAccountMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(accountMapper.selectRoleCodes(7L)).thenReturn(Collections.singletonList(PLATFORM_ADMIN));
        AdminProperties properties = new AdminProperties();
        properties.setSessionTtl(Duration.ofMinutes(30));
        service = new AdminSessionService(redisTemplate, accountMapper, merchantMapper, properties);
    }

    @Test
    void everyResolutionReloadsAccountAndCurrentPermissionsFromDatabase() {
        when(values.get(ADMIN_LOGIN_TOKEN_KEY + TOKEN)).thenReturn("7:0");
        when(accountMapper.selectById(7L)).thenReturn(platformAccount());
        when(accountMapper.selectPermissionCodes(7L))
                .thenReturn(Arrays.asList("shop:read"))
                .thenReturn(Arrays.asList("shop:read", "shop:write"));

        AdminPrincipal first = service.resolve(TOKEN, false);
        AdminPrincipal second = service.resolve(TOKEN, false);

        assertThat(first.getPermissions()).containsExactly("shop:read");
        assertThat(second.getPermissions()).containsExactly("shop:read", "shop:write");
        verify(accountMapper, times(2)).selectById(7L);
    }

    @Test
    void disabledAccountRevokesExistingRedisTokenImmediately() {
        when(values.get(ADMIN_LOGIN_TOKEN_KEY + TOKEN)).thenReturn("7:0");
        AdminAccount account = platformAccount();
        account.setStatus(2);
        when(accountMapper.selectById(7L)).thenReturn(account);

        assertThat(service.resolve(TOKEN, false)).isNull();

        verify(redisTemplate).delete(ADMIN_LOGIN_TOKEN_KEY + TOKEN);
    }

    @Test
    void disabledMerchantRevokesExistingMerchantSession() {
        when(values.get(ADMIN_LOGIN_TOKEN_KEY + TOKEN)).thenReturn("8:0");
        AdminAccount account = new AdminAccount();
        account.setId(8L);
        account.setMerchantId(21L);
        account.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        account.setStatus(1);
        when(accountMapper.selectById(8L)).thenReturn(account);
        when(accountMapper.selectRoleCodes(8L)).thenReturn(Collections.singletonList(MERCHANT_STAFF));
        Merchant merchant = new Merchant();
        merchant.setId(21L);
        merchant.setStatus(2);
        when(merchantMapper.selectById(21L)).thenReturn(merchant);

        assertThat(service.resolve(TOKEN, false)).isNull();
        verify(redisTemplate).delete(ADMIN_LOGIN_TOKEN_KEY + TOKEN);
    }

    @Test
    void consumerTokenCannotResolveThroughAdminNamespace() {
        when(values.get(ADMIN_LOGIN_TOKEN_KEY + TOKEN)).thenReturn(null);

        assertThat(service.resolve(TOKEN, false)).isNull();

        verify(values).get(ADMIN_LOGIN_TOKEN_KEY + TOKEN);
        verify(accountMapper, never()).selectById(7L);
    }

    @Test
    void merchantAccountCanNeverAcquirePlatformScopeFromACrossScopeRole() {
        when(values.get(ADMIN_LOGIN_TOKEN_KEY + TOKEN)).thenReturn("8:0");
        AdminAccount account = new AdminAccount();
        account.setId(8L);
        account.setMerchantId(21L);
        account.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        account.setStatus(1);
        when(accountMapper.selectById(8L)).thenReturn(account);
        Merchant merchant = new Merchant();
        merchant.setId(21L);
        merchant.setStatus(1);
        when(merchantMapper.selectById(21L)).thenReturn(merchant);
        when(accountMapper.selectRoleCodes(8L)).thenReturn(Collections.singletonList(PLATFORM_ADMIN));

        assertThat(service.resolve(TOKEN, false)).isNull();
        verify(accountMapper, never()).selectPermissionCodes(8L);
    }

    @Test
    void malformedBearerIsRejectedBeforeRedisLookup() {
        assertThat(service.resolve("consumer-token", false)).isNull();
        verify(values, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void credentialVersionChangeRevokesAnOtherwiseValidToken() {
        when(values.get(ADMIN_LOGIN_TOKEN_KEY + TOKEN)).thenReturn("7:2");
        AdminAccount account = platformAccount();
        account.setAuthVersion(3);
        when(accountMapper.selectById(7L)).thenReturn(account);

        assertThat(service.resolve(TOKEN, false)).isNull();

        verify(redisTemplate).delete(ADMIN_LOGIN_TOKEN_KEY + TOKEN);
    }

    private AdminAccount platformAccount() {
        AdminAccount account = new AdminAccount();
        account.setId(7L);
        account.setUsername("platform.admin");
        account.setDisplayName("Platform Admin");
        account.setScopeType(AdminPrincipal.SCOPE_PLATFORM);
        account.setStatus(1);
        return account;
    }
}
