package com.localdeals.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.localdeals.auth.AdminRoleCodes;
import com.localdeals.dto.AdminAccountCreateRequest;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.entity.AdminAccount;
import com.localdeals.entity.AdminRole;
import com.localdeals.entity.Merchant;
import com.localdeals.mapper.AdminAccountMapper;
import com.localdeals.mapper.AdminRoleMapper;
import com.localdeals.mapper.MerchantMapper;
import com.localdeals.utils.AdminPrincipalHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminManagementServiceTest {
    private MerchantMapper merchantMapper;
    private AdminAccountMapper accountMapper;
    private AdminRoleMapper roleMapper;
    private AdminManagementService service;

    @BeforeEach
    void setUp() {
        merchantMapper = mock(MerchantMapper.class);
        accountMapper = mock(AdminAccountMapper.class);
        roleMapper = mock(AdminRoleMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("bcrypt-hash");
        service = new AdminManagementService(merchantMapper, accountMapper, roleMapper, encoder);
    }

    @AfterEach
    void clearHolder() {
        AdminPrincipalHolder.remove();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void merchantAccountListCannotUseARequestedForeignMerchantId() {
        loginMerchant(21L);
        when(accountMapper.selectList(any())).thenReturn(Collections.emptyList());
        ArgumentCaptor<QueryWrapper> scope = ArgumentCaptor.forClass(QueryWrapper.class);

        service.listAccounts(99L);

        verify(accountMapper).selectList(scope.capture());
        assertThat(scope.getValue().getCustomSqlSegment()).contains("merchant_id");
        assertThat(scope.getValue().getParamNameValuePairs().values()).contains(21L).doesNotContain(99L);
    }

    @Test
    void merchantCreatesOnlyAStaffAccountInsideItsOwnScope() {
        loginMerchant(21L);
        Merchant merchant = new Merchant();
        merchant.setId(21L);
        merchant.setStatus(1);
        when(merchantMapper.selectById(21L)).thenReturn(merchant);
        when(accountMapper.insert(any(AdminAccount.class))).thenAnswer(invocation -> {
            AdminAccount account = invocation.getArgument(0);
            account.setId(8L);
            return 1;
        });
        AdminRole staffRole = new AdminRole();
        staffRole.setId(3L);
        staffRole.setCode(AdminRoleCodes.MERCHANT_STAFF);
        when(roleMapper.selectOne(any())).thenReturn(staffRole);
        when(accountMapper.insertAccountRole(8L, 3L)).thenReturn(1);
        when(accountMapper.selectRoleCodes(8L))
                .thenReturn(Collections.singletonList(AdminRoleCodes.MERCHANT_STAFF));
        AdminAccountCreateRequest request = new AdminAccountCreateRequest();
        request.setMerchantId(99L);
        request.setUsername("readonly.staff");
        request.setPassword("long-enough-password");
        request.setDisplayName("Read only staff");
        ArgumentCaptor<AdminAccount> inserted = ArgumentCaptor.forClass(AdminAccount.class);

        service.createStaff(request);

        verify(accountMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getMerchantId()).isEqualTo(21L);
        assertThat(inserted.getValue().getScopeType()).isEqualTo(AdminPrincipal.SCOPE_MERCHANT);
        verify(accountMapper).insertAccountRole(8L, 3L);
    }

    @Test
    void merchantCannotCreateANewMerchantEvenWhenCallingServiceDirectly() {
        loginMerchant(21L);

        assertThatThrownBy(() -> service.createMerchant(null))
                .hasMessage("仅平台管理员可执行此操作");

        verify(merchantMapper, never()).insert(any());
    }

    @Test
    void statusChangeBumpsCredentialVersionInsideTheMerchantScope() {
        loginMerchant(21L);
        AdminAccount target = new AdminAccount();
        target.setId(8L);
        target.setMerchantId(21L);
        target.setAuthVersion(4);
        when(accountMapper.selectOne(any())).thenReturn(target);
        when(accountMapper.selectRoleCodes(8L))
                .thenReturn(Collections.singletonList(AdminRoleCodes.MERCHANT_STAFF));
        when(accountMapper.updateStatusAndBumpVersion(8L, 2, 4, 21L)).thenReturn(1);

        service.changeAccountStatus(8L, 2);

        verify(accountMapper).updateStatusAndBumpVersion(8L, 2, 4, 21L);
    }

    private void loginMerchant(Long merchantId) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(7L);
        principal.setMerchantId(merchantId);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        AdminPrincipalHolder.save(principal);
    }
}
