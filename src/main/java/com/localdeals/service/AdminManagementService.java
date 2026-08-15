package com.localdeals.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.localdeals.auth.AdminRoleCodes;
import com.localdeals.dto.AdminAccountCreateRequest;
import com.localdeals.dto.AdminAccountSummary;
import com.localdeals.dto.AdminMerchantCreateRequest;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.entity.AdminAccount;
import com.localdeals.entity.AdminRole;
import com.localdeals.entity.Merchant;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.AdminAccountMapper;
import com.localdeals.mapper.AdminRoleMapper;
import com.localdeals.mapper.MerchantMapper;
import com.localdeals.utils.AdminPrincipalHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AdminManagementService {
    private static final Pattern MERCHANT_CODE_PATTERN = Pattern.compile("[A-Z0-9_-]{3,64}");

    private final MerchantMapper merchantMapper;
    private final AdminAccountMapper accountMapper;
    private final AdminRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminManagementService(MerchantMapper merchantMapper,
            AdminAccountMapper accountMapper,
            AdminRoleMapper roleMapper,
            PasswordEncoder passwordEncoder) {
        this.merchantMapper = merchantMapper;
        this.accountMapper = accountMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Merchant> listMerchants() {
        requirePlatform();
        return merchantMapper.selectList(new QueryWrapper<Merchant>().orderByAsc("id"));
    }

    @Transactional
    public Map<String, Long> createMerchant(AdminMerchantCreateRequest request) {
        requirePlatform();
        if (request == null || !StringUtils.hasText(request.getCode()) ||
                !StringUtils.hasText(request.getName())) {
            throw new IllegalArgumentException("商户编码和名称不能为空");
        }
        String code = request.getCode().trim().toUpperCase(Locale.ROOT);
        if (!MERCHANT_CODE_PATTERN.matcher(code).matches() || "LEGACY_UNASSIGNED".equals(code)) {
            throw new IllegalArgumentException("商户编码仅支持 3-64 位大写字母、数字、下划线和短横线");
        }
        String username = requireUsername(request.getOwnerUsername());
        validatePassword(request.getOwnerPassword());
        String displayName = requireDisplayName(request.getOwnerDisplayName());

        try {
            Merchant merchant = new Merchant();
            merchant.setCode(code);
            merchant.setName(request.getName().trim());
            merchant.setStatus(1);
            if (merchantMapper.insert(merchant) != 1) {
                throw new IllegalStateException("商户创建失败");
            }
            AdminAccount owner = createAccountEntity(
                    merchant.getId(), username, request.getOwnerPassword(), displayName);
            assignRole(owner.getId(), AdminRoleCodes.MERCHANT_OWNER);

            Map<String, Long> result = new LinkedHashMap<>();
            result.put("merchantId", merchant.getId());
            result.put("ownerAccountId", owner.getId());
            return result;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("商户编码或后台用户名已存在");
        }
    }

    public List<AdminAccountSummary> listAccounts(Long requestedMerchantId) {
        AdminPrincipal principal = currentPrincipal();
        QueryWrapper<AdminAccount> query = new QueryWrapper<>();
        if (principal.isPlatform()) {
            if (requestedMerchantId != null) {
                query.eq("merchant_id", requestedMerchantId);
            }
        } else {
            query.eq("merchant_id", principal.getMerchantId());
        }
        query.orderByAsc("id");
        return accountMapper.selectList(query).stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Transactional
    public AdminAccountSummary createStaff(AdminAccountCreateRequest request) {
        AdminPrincipal principal = currentPrincipal();
        if (request == null) {
            throw new IllegalArgumentException("账号信息不能为空");
        }
        Long merchantId = principal.isPlatform() ? request.getMerchantId() : principal.getMerchantId();
        requireActiveMerchant(merchantId);
        String username = requireUsername(request.getUsername());
        validatePassword(request.getPassword());
        String displayName = requireDisplayName(request.getDisplayName());
        try {
            AdminAccount account = createAccountEntity(
                    merchantId, username, request.getPassword(), displayName);
            assignRole(account.getId(), AdminRoleCodes.MERCHANT_STAFF);
            return toSummary(account);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("后台用户名已存在");
        }
    }

    @Transactional
    public void changeAccountStatus(Long accountId, Integer status) {
        AdminPrincipal principal = currentPrincipal();
        if (status == null || (status != 1 && status != 2)) {
            throw new IllegalArgumentException("账号状态仅支持启用或停用");
        }
        if (accountId == null || accountId.equals(principal.getAccountId())) {
            throw new IllegalArgumentException("不能修改当前登录账号的状态");
        }
        QueryWrapper<AdminAccount> scope = new QueryWrapper<AdminAccount>().eq("id", accountId);
        if (!principal.isPlatform()) {
            scope.eq("merchant_id", principal.getMerchantId());
        }
        AdminAccount target = accountMapper.selectOne(scope.last("LIMIT 1"));
        if (target == null) {
            throw new ApiStatusException(HttpStatus.NOT_FOUND, "资源不存在");
        }
        List<String> roles = accountMapper.selectRoleCodes(accountId);
        if (roles.contains(AdminRoleCodes.PLATFORM_ADMIN) || roles.contains(AdminRoleCodes.MERCHANT_OWNER)) {
            throw new IllegalArgumentException("内置主账号不能通过员工接口停用");
        }
        Long merchantScope = principal.isPlatform() ? null : principal.getMerchantId();
        int updated = accountMapper.updateStatusAndBumpVersion(
                accountId,
                status,
                target.getAuthVersion() == null ? 0 : target.getAuthVersion(),
                merchantScope);
        if (updated != 1) {
            throw new ApiStatusException(HttpStatus.CONFLICT, "账号状态已变化，请刷新后重试");
        }
    }

    private AdminAccount createAccountEntity(Long merchantId, String username,
            String rawPassword, String displayName) {
        AdminAccount account = new AdminAccount();
        account.setMerchantId(merchantId);
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setDisplayName(displayName);
        account.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        account.setStatus(1);
        if (accountMapper.insert(account) != 1) {
            throw new IllegalStateException("后台账号创建失败");
        }
        return account;
    }

    private void assignRole(Long accountId, String roleCode) {
        AdminRole role = roleMapper.selectOne(new QueryWrapper<AdminRole>()
                .eq("code", roleCode).eq("status", 1).last("LIMIT 1"));
        if (role == null || accountMapper.insertAccountRole(accountId, role.getId()) != 1) {
            throw new IllegalStateException("后台角色初始化失败");
        }
    }

    private AdminAccountSummary toSummary(AdminAccount account) {
        AdminAccountSummary summary = new AdminAccountSummary();
        summary.setId(account.getId());
        summary.setMerchantId(account.getMerchantId());
        summary.setUsername(account.getUsername());
        summary.setDisplayName(account.getDisplayName());
        summary.setScopeType(account.getScopeType());
        summary.setStatus(account.getStatus());
        summary.setRoles(new ArrayList<>(accountMapper.selectRoleCodes(account.getId())));
        summary.setLastLoginTime(account.getLastLoginTime());
        summary.setCreateTime(account.getCreateTime());
        return summary;
    }

    private void requireActiveMerchant(Long merchantId) {
        Merchant merchant = merchantId == null ? null : merchantMapper.selectById(merchantId);
        if (merchant == null || !Integer.valueOf(1).equals(merchant.getStatus())) {
            throw new IllegalArgumentException("请选择有效的商户主体");
        }
    }

    private String requireUsername(String rawUsername) {
        String username = AdminAuthService.normalizeUsername(rawUsername);
        if (username == null) {
            throw new IllegalArgumentException("用户名仅支持 4-64 位小写字母、数字、点、下划线和短横线");
        }
        return username;
    }

    private void validatePassword(String password) {
        AdminAuthService.validateNewPassword(password);
    }

    private String requireDisplayName(String value) {
        if (!StringUtils.hasText(value) || value.trim().length() > 64) {
            throw new IllegalArgumentException("显示名称不能为空且不能超过 64 个字符");
        }
        return value.trim();
    }

    private void requirePlatform() {
        if (!currentPrincipal().isPlatform()) {
            throw new ApiStatusException(HttpStatus.FORBIDDEN, "仅平台管理员可执行此操作");
        }
    }

    private AdminPrincipal currentPrincipal() {
        AdminPrincipal principal = AdminPrincipalHolder.get();
        if (principal == null) {
            throw new ApiStatusException(HttpStatus.UNAUTHORIZED, "后台登录已失效");
        }
        return principal;
    }
}
