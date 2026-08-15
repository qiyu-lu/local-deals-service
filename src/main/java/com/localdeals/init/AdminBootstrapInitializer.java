package com.localdeals.init;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.localdeals.auth.AdminRoleCodes;
import com.localdeals.config.AdminProperties;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.entity.AdminAccount;
import com.localdeals.entity.AdminRole;
import com.localdeals.mapper.AdminAccountMapper;
import com.localdeals.mapper.AdminRoleMapper;
import com.localdeals.service.AdminAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@Profile("!test")
public class AdminBootstrapInitializer implements ApplicationRunner {
    private final AdminProperties properties;
    private final AdminAccountMapper accountMapper;
    private final AdminRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapInitializer(AdminProperties properties,
            AdminAccountMapper accountMapper,
            AdminRoleMapper roleMapper,
            PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.accountMapper = accountMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer existingPlatformAccounts = accountMapper.selectCount(
                new QueryWrapper<AdminAccount>().eq("scope_type", AdminPrincipal.SCOPE_PLATFORM));
        if (existingPlatformAccounts != null && existingPlatformAccounts > 0) {
            return;
        }

        String rawUsername = properties.getBootstrap().getUsername();
        String rawPassword = properties.getBootstrap().getPassword();
        if (!StringUtils.hasText(rawUsername) && !StringUtils.hasText(rawPassword)) {
            log.warn("No platform administrator exists and bootstrap credentials are not configured; " +
                    "the admin API remains fail-closed");
            return;
        }
        if (!StringUtils.hasText(rawUsername) || !StringUtils.hasText(rawPassword)) {
            throw new IllegalStateException("Both admin bootstrap username and password must be configured");
        }
        String username = AdminAuthService.normalizeUsername(rawUsername);
        if (username == null) {
            throw new IllegalStateException("Admin bootstrap username must match [a-z0-9._-]{4,64}");
        }
        if (rawPassword.length() < 12 || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException(
                    "Admin bootstrap password must contain at least 12 characters and at most 72 UTF-8 bytes");
        }

        AdminRole platformRole = roleMapper.selectOne(new QueryWrapper<AdminRole>()
                .eq("code", AdminRoleCodes.PLATFORM_ADMIN).eq("status", 1).last("LIMIT 1"));
        if (platformRole == null) {
            throw new IllegalStateException("PLATFORM_ADMIN role is missing; verify Flyway migration V5");
        }
        AdminAccount duplicate = accountMapper.selectOne(
                new QueryWrapper<AdminAccount>().eq("username", username).last("LIMIT 1"));
        if (duplicate != null) {
            throw new IllegalStateException("Bootstrap username is already used by a non-platform account");
        }

        AdminAccount account = new AdminAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setDisplayName(defaultDisplayName(properties.getBootstrap().getDisplayName()));
        account.setScopeType(AdminPrincipal.SCOPE_PLATFORM);
        account.setStatus(1);
        if (accountMapper.insert(account) != 1 ||
                accountMapper.insertAccountRole(account.getId(), platformRole.getId()) != 1) {
            throw new IllegalStateException("Failed to create platform administrator bootstrap account");
        }
        log.info("Created the initial platform administrator account username={}", username);
    }

    private String defaultDisplayName(String displayName) {
        if (!StringUtils.hasText(displayName)) {
            return "平台管理员";
        }
        String value = displayName.trim();
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}
