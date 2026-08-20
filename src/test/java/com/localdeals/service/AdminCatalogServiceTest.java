package com.localdeals.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.AdminShopCreateRequest;
import com.localdeals.dto.AdminShopUpdateRequest;
import com.localdeals.dto.AdminVoucherCreateRequest;
import com.localdeals.entity.Merchant;
import com.localdeals.entity.Shop;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.MerchantMapper;
import com.localdeals.mapper.ShopMapper;
import com.localdeals.mapper.VoucherMapper;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.utils.AdminPrincipalHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCatalogServiceTest {
    private ShopMapper shopMapper;
    private MerchantMapper merchantMapper;
    private VoucherMapper voucherMapper;
    private IVoucherService voucherService;
    private StringRedisTemplate redisTemplate;
    private GeoOperations<String, String> geoOperations;
    private SimpleMeterRegistry meterRegistry;
    private AdminCatalogService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        shopMapper = mock(ShopMapper.class);
        merchantMapper = mock(MerchantMapper.class);
        voucherMapper = mock(VoucherMapper.class);
        voucherService = mock(IVoucherService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        geoOperations = mock(GeoOperations.class);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        meterRegistry = new SimpleMeterRegistry();
        service = new AdminCatalogService(
                shopMapper,
                merchantMapper,
                voucherMapper,
                voucherService,
                redisTemplate,
                new LocalDealsMetrics(meterRegistry));
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

    @Test
    void createEvictsPossibleHistoricalNegativeCacheOnlyAfterCommit() {
        loginPlatform();
        Merchant merchant = activeMerchant(21L, "MERCHANT_A");
        when(merchantMapper.selectById(21L)).thenReturn(merchant);
        doAnswer(invocation -> {
            ((Shop) invocation.getArgument(0)).setId(101L);
            return 1;
        }).when(shopMapper).insert(any(Shop.class));
        TransactionSynchronizationManager.initSynchronization();

        service.createShop(validCreateRequest(21L));

        verify(redisTemplate, never()).delete("cache:shop:101");
        triggerAfterCommit();
        verify(redisTemplate).delete("cache:shop:101");
        assertEvictionMetric("success", 1D);
    }

    @Test
    void updateEvictsExactDetailKeyOnlyAfterCommit() {
        loginMerchant(21L);
        Shop existing = shop(7L, 21L, 1L);
        when(shopMapper.selectByIdAndMerchantForUpdate(7L, 21L)).thenReturn(existing);
        when(shopMapper.update(any(Shop.class), any(UpdateWrapper.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        AdminShopUpdateRequest request = new AdminShopUpdateRequest();
        request.setName("Updated");

        service.updateShop(7L, request);

        verify(redisTemplate, never()).delete("cache:shop:7");
        triggerAfterCommit();
        verify(redisTemplate).delete("cache:shop:7");
    }

    @Test
    void assignEvictsExactDetailKeyOnlyAfterCommit() {
        loginPlatform();
        Shop existing = shop(8L, 1L, 2L);
        when(shopMapper.selectByIdForUpdate(8L)).thenReturn(existing);
        when(merchantMapper.selectById(1L)).thenReturn(activeMerchant(1L, "LEGACY_UNASSIGNED"));
        when(merchantMapper.selectById(22L)).thenReturn(activeMerchant(22L, "MERCHANT_B"));
        when(shopMapper.update(any(Shop.class), any(UpdateWrapper.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.assignShop(8L, 22L);

        verify(redisTemplate, never()).delete("cache:shop:8");
        triggerAfterCommit();
        verify(redisTemplate).delete("cache:shop:8");
    }

    @Test
    void rollbackDoesNotEvictOrChangeGeo() {
        loginMerchant(21L);
        Shop existing = shop(9L, 21L, 1L);
        when(shopMapper.selectByIdAndMerchantForUpdate(9L, 21L)).thenReturn(existing);
        when(shopMapper.update(any(Shop.class), any(UpdateWrapper.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        AdminShopUpdateRequest request = new AdminShopUpdateRequest();
        request.setTypeId(2L);
        request.setX(120D);
        request.setY(30D);

        service.updateShop(9L, request);
        triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(redisTemplate, never()).delete(any(String.class));
        verify(geoOperations, never()).remove(any(String.class), any());
        verify(geoOperations, never()).add(any(String.class), any(Point.class), anyString());
        assertEvictionMetric("success", 0D);
    }

    @Test
    void evictionFailureDoesNotBlockIndependentGeoRemoveAndAdd() {
        loginMerchant(21L);
        Shop existing = shop(10L, 21L, 1L);
        when(shopMapper.selectByIdAndMerchantForUpdate(10L, 21L)).thenReturn(existing);
        when(shopMapper.update(any(Shop.class), any(UpdateWrapper.class))).thenReturn(1);
        doThrow(new IllegalStateException("delete failed"))
                .when(redisTemplate).delete("cache:shop:10");
        TransactionSynchronizationManager.initSynchronization();
        AdminShopUpdateRequest request = new AdminShopUpdateRequest();
        request.setTypeId(2L);
        request.setX(120D);
        request.setY(30D);

        Shop committed = service.updateShop(10L, request);
        triggerAfterCommit();

        assertThat(committed.getTypeId()).isEqualTo(2L);
        verify(geoOperations).remove("shop:geo:1", "10");
        verify(geoOperations).add("shop:geo:2", new Point(120D, 30D), "10");
        assertEvictionMetric("failure", 1D);
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

    private AdminShopCreateRequest validCreateRequest(Long merchantId) {
        AdminShopCreateRequest request = new AdminShopCreateRequest();
        request.setMerchantId(merchantId);
        request.setName("New Shop");
        request.setTypeId(1L);
        request.setImages("shop.jpg");
        request.setAddress("Test Street");
        request.setX(120D);
        request.setY(30D);
        return request;
    }

    private static Merchant activeMerchant(Long id, String code) {
        Merchant merchant = new Merchant();
        merchant.setId(id);
        merchant.setCode(code);
        merchant.setStatus(1);
        return merchant;
    }

    private static Shop shop(Long id, Long merchantId, Long typeId) {
        return new Shop().setId(id).setMerchantId(merchantId).setTypeId(typeId)
                .setX(119D).setY(29D);
    }

    private void triggerAfterCommit() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    private void triggerAfterCompletion(int status) {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
        TransactionSynchronizationManager.clearSynchronization();
    }

    private void assertEvictionMetric(String result, double expected) {
        assertThat(meterRegistry.get("local_deals.cache.maintenance")
                .tags("resource", "shop_detail", "operation", "evict", "result", result)
                .counter().count()).isEqualTo(expected);
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
