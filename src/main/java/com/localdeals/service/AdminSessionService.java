package com.localdeals.service;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.auth.AdminRoleCodes;
import com.localdeals.config.AdminProperties;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.AdminWebSocketTicketResponse;
import com.localdeals.entity.AdminAccount;
import com.localdeals.entity.Merchant;
import com.localdeals.mapper.AdminAccountMapper;
import com.localdeals.mapper.MerchantMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.localdeals.utils.RedisConstants.ADMIN_LOGIN_TOKEN_KEY;
import static com.localdeals.utils.RedisConstants.ADMIN_WEBSOCKET_TICKET_KEY;

@Service
public class AdminSessionService {
    private static final long WEBSOCKET_TICKET_TTL_SECONDS = 30L;
    private static final Pattern OPAQUE_TOKEN_PATTERN = Pattern.compile("[0-9a-f]{32}");
    private static final DefaultRedisScript<String> CONSUME_TICKET_SCRIPT;

    static {
        CONSUME_TICKET_SCRIPT = new DefaultRedisScript<>();
        CONSUME_TICKET_SCRIPT.setLocation(new ClassPathResource("lua/consume_admin_ws_ticket.lua"));
        CONSUME_TICKET_SCRIPT.setResultType(String.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final AdminAccountMapper adminAccountMapper;
    private final MerchantMapper merchantMapper;
    private final AdminProperties adminProperties;

    public AdminSessionService(StringRedisTemplate stringRedisTemplate,
            AdminAccountMapper adminAccountMapper,
            MerchantMapper merchantMapper,
            AdminProperties adminProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.adminAccountMapper = adminAccountMapper;
        this.merchantMapper = merchantMapper;
        this.adminProperties = adminProperties;
    }

    public AdminPrincipal resolve(String token, boolean refreshTtl) {
        if (StrUtil.isBlank(token) || !OPAQUE_TOKEN_PATTERN.matcher(token).matches()) {
            return null;
        }
        String tokenKey = ADMIN_LOGIN_TOKEN_KEY + token;
        String accountIdValue = stringRedisTemplate.opsForValue().get(tokenKey);
        if (StrUtil.isBlank(accountIdValue)) {
            return null;
        }
        final Long accountId;
        final Integer issuedAuthVersion;
        try {
            String[] sessionParts = accountIdValue.split(":", -1);
            if (sessionParts.length != 2) {
                throw new NumberFormatException("invalid admin session payload");
            }
            accountId = Long.valueOf(sessionParts[0]);
            issuedAuthVersion = Integer.valueOf(sessionParts[1]);
        } catch (NumberFormatException e) {
            stringRedisTemplate.delete(tokenKey);
            return null;
        }

        AdminPrincipal principal = resolveAccount(accountId);
        if (principal == null || !issuedAuthVersion.equals(principal.getAuthVersion())) {
            stringRedisTemplate.delete(tokenKey);
            return null;
        }
        if (refreshTtl) {
            stringRedisTemplate.expire(tokenKey, sessionTtl());
        }
        return principal;
    }

    public AdminPrincipal resolveAccount(Long accountId) {
        AdminAccount account = accountId == null ? null : adminAccountMapper.selectById(accountId);
        if (account == null || !Integer.valueOf(1).equals(account.getStatus())) {
            return null;
        }
        boolean platform = AdminPrincipal.SCOPE_PLATFORM.equals(account.getScopeType());
        if (platform) {
            if (account.getMerchantId() != null) {
                return null;
            }
        } else if (AdminPrincipal.SCOPE_MERCHANT.equals(account.getScopeType())) {
            if (account.getMerchantId() == null) {
                return null;
            }
            Merchant merchant = merchantMapper.selectById(account.getMerchantId());
            if (merchant == null || !Integer.valueOf(1).equals(merchant.getStatus())) {
                return null;
            }
        } else {
            return null;
        }

        List<String> roles = adminAccountMapper.selectRoleCodes(account.getId());
        if (platform) {
            if (!roles.contains(AdminRoleCodes.PLATFORM_ADMIN)) {
                return null;
            }
        } else if (roles.contains(AdminRoleCodes.PLATFORM_ADMIN) ||
                (!roles.contains(AdminRoleCodes.MERCHANT_OWNER) &&
                        !roles.contains(AdminRoleCodes.MERCHANT_STAFF))) {
            return null;
        }

        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(account.getId());
        principal.setMerchantId(account.getMerchantId());
        principal.setUsername(account.getUsername());
        principal.setDisplayName(account.getDisplayName());
        principal.setScopeType(account.getScopeType());
        principal.setAuthVersion(account.getAuthVersion() == null ? 0 : account.getAuthVersion());
        principal.setPermissions(new LinkedHashSet<>(
                adminAccountMapper.selectPermissionCodes(account.getId())));
        return principal;
    }

    public void storeSession(String token, Long accountId, Integer authVersion) {
        stringRedisTemplate.opsForValue().set(
                ADMIN_LOGIN_TOKEN_KEY + token,
                accountId + ":" + (authVersion == null ? 0 : authVersion),
                sessionTtl()
        );
    }

    public void revoke(String token) {
        if (StrUtil.isNotBlank(token)) {
            stringRedisTemplate.delete(ADMIN_LOGIN_TOKEN_KEY + token);
        }
    }

    public AdminWebSocketTicketResponse issueWebSocketTicket(String token) {
        AdminPrincipal principal = resolve(token, true);
        if (principal == null || !principal.hasPermission(AdminPermissionCodes.ORDER_REALTIME)) {
            return null;
        }
        String ticket = UUID.randomUUID().toString(true);
        stringRedisTemplate.opsForValue().set(
                ADMIN_WEBSOCKET_TICKET_KEY + ticket,
                token,
                WEBSOCKET_TICKET_TTL_SECONDS,
                TimeUnit.SECONDS
        );
        return new AdminWebSocketTicketResponse(ticket, WEBSOCKET_TICKET_TTL_SECONDS);
    }

    public String consumeWebSocketTicket(String ticket) {
        if (StrUtil.isBlank(ticket) || !OPAQUE_TOKEN_PATTERN.matcher(ticket).matches()) {
            return null;
        }
        return stringRedisTemplate.execute(
                CONSUME_TICKET_SCRIPT,
                java.util.Collections.singletonList(ADMIN_WEBSOCKET_TICKET_KEY + ticket)
        );
    }

    public String extractBearerToken(String authorization) {
        if (StrUtil.isBlank(authorization)) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = value.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    public long sessionTtlSeconds() {
        return sessionTtl().getSeconds();
    }

    private Duration sessionTtl() {
        Duration ttl = adminProperties.getSessionTtl();
        return ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(30) : ttl;
    }
}
