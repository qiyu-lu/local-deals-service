package com.localdeals.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.AdminShopUpdateRequest;
import com.localdeals.dto.AdminVoucherCreateRequest;
import com.localdeals.entity.Shop;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.MerchantMapper;
import com.localdeals.mapper.ShopMapper;
import com.localdeals.mapper.VoucherMapper;
import com.localdeals.utils.AdminPrincipalHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCatalogServiceTest {
    private ShopMapper shopMapper;
    private MerchantMapper merchantMapper;
    private VoucherMapper voucherMapper;
    private IVoucherService voucherService;
    private AdminCatalogService service;

    @BeforeEach
    void setUp() {
        shopMapper = mock(ShopMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        voucherMapper = mock(VoucherMapper.class);
        voucherService = mock(IVoucherService.class);
        service = new AdminCatalogService(
                shopMapper,
                merchantMapper,
                voucherMapper,
                voucherService,
                mock(StringRedisTemplate.class));
    }

    @AfterEach
    void clearThreadState() {
        AdminPrincipalHolder.remove();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void merchantShopListAlwaysIncludesMerchantScopePredicate() {
        loginMerchant(21L);
        when(shopMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(new Page<Shop>(1, 20).setRecords(Collections.emptyList()));
        ArgumentCaptor<Wrapper> wrapper = ArgumentCaptor.forClass(Wrapper.class);

        service.listShops(1, 20, null);

        verify(shopMapper).selectPage(any(Page.class), wrapper.capture());
        assertThat(wrapper.getValue().getCustomSqlSegment()).contains("merchant_id");
    }

    @Test
    void crossMerchantVoucherCreationStopsBeforeAnyVoucherWrite() {
        loginMerchant(21L);
        when(shopMapper.selectByIdAndMerchantForUpdate(99L, 21L)).thenReturn(null);

        assertThatThrownBy(() -> service.createVoucher(
                99L, validVoucherRequest(), true))
                .isInstanceOf(ApiStatusException.class)
                .hasMessage("资源不存在");

        verify(voucherService, never()).addSeckillVoucher(any());
        verify(voucherService, never()).save(any());
    }

    @Test
    void merchantVoucherReadCarriesScopeIntoTheVoucherSql() {
        loginMerchant(21L);
        Shop shop = new Shop();
        shop.setId(7L);
        shop.setMerchantId(21L);
        when(shopMapper.selectOne(any())).thenReturn(shop);
        when(voucherMapper.queryVoucherOfShopForAdmin(7L, 21L))
                .thenReturn(Collections.emptyList());

        service.listVouchers(7L);

        verify(voucherMapper).queryVoucherOfShopForAdmin(7L, 21L);
        verify(voucherMapper, never()).queryVoucherOfShopForAdmin(7L, null);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void merchantUpdateUsesIdAndMerchantInTheSameSqlStatement() {
        loginMerchant(21L);
        Shop existing = new Shop();
        existing.setId(7L);
        existing.setMerchantId(21L);
        existing.setTypeId(1L);
        when(shopMapper.selectByIdAndMerchantForUpdate(7L, 21L)).thenReturn(existing);
        when(shopMapper.update(any(Shop.class), any(UpdateWrapper.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        AdminShopUpdateRequest request = new AdminShopUpdateRequest();
        request.setName("Scoped Shop");
        ArgumentCaptor<Wrapper> wrapper = ArgumentCaptor.forClass(Wrapper.class);

        service.updateShop(7L, request);

        verify(shopMapper).update(any(Shop.class), wrapper.capture());
        assertThat(wrapper.getValue().getCustomSqlSegment())
                .contains("id")
                .contains("merchant_id");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void platformListDoesNotAccidentallyInheritAMerchantPredicate() {
        loginPlatform();
        when(shopMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenReturn(new Page<Shop>(1, 20).setRecords(Collections.emptyList()));
        ArgumentCaptor<Wrapper> wrapper = ArgumentCaptor.forClass(Wrapper.class);

        service.listShops(1, 20, null);

        verify(shopMapper).selectPage(any(Page.class), wrapper.capture());
        assertThat(wrapper.getValue().getCustomSqlSegment()).doesNotContain("merchant_id");
    }

    @Test
    void alreadyAssignedShopCannotBeMovedToAnotherMerchant() {
        loginPlatform();
        Shop existing = new Shop();
        existing.setId(7L);
        existing.setMerchantId(21L);
        when(shopMapper.selectByIdForUpdate(7L)).thenReturn(existing);
        com.localdeals.entity.Merchant assigned = new com.localdeals.entity.Merchant();
        assigned.setId(21L);
        assigned.setCode("MERCHANT_A");
        assigned.setStatus(1);
        when(merchantMapper.selectById(21L)).thenReturn(assigned);

        assertThatThrownBy(() -> service.assignShop(7L, 22L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("仅历史待分配商铺可以认领，已归属商铺禁止换绑");

        verify(shopMapper, never()).update(any(), any(UpdateWrapper.class));
    }

    private AdminVoucherCreateRequest validVoucherRequest() {
        AdminVoucherCreateRequest request = new AdminVoucherCreateRequest();
        request.setTitle("Limited voucher");
        request.setPayValue(8000L);
        request.setActualValue(10000L);
        request.setStock(10);
        request.setBeginTime(java.time.LocalDateTime.now().plusMinutes(1));
        request.setEndTime(java.time.LocalDateTime.now().plusHours(1));
        return request;
    }

    private void loginMerchant(Long merchantId) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(8L);
        principal.setMerchantId(merchantId);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        AdminPrincipalHolder.save(principal);
    }

    private void loginPlatform() {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(1L);
        principal.setScopeType(AdminPrincipal.SCOPE_PLATFORM);
        AdminPrincipalHolder.save(principal);
    }
}
