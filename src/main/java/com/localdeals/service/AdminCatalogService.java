package com.localdeals.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.AdminShopCreateRequest;
import com.localdeals.dto.AdminShopUpdateRequest;
import com.localdeals.dto.AdminVoucherCreateRequest;
import com.localdeals.entity.Merchant;
import com.localdeals.entity.Shop;
import com.localdeals.entity.Voucher;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.MerchantMapper;
import com.localdeals.mapper.ShopMapper;
import com.localdeals.mapper.VoucherMapper;
import com.localdeals.utils.AdminPrincipalHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.List;

import static com.localdeals.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.localdeals.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Service
public class AdminCatalogService {
    private static final String LEGACY_UNASSIGNED_MERCHANT_CODE = "LEGACY_UNASSIGNED";

    private final ShopMapper shopMapper;
    private final MerchantMapper merchantMapper;
    private final VoucherMapper voucherMapper;
    private final IVoucherService voucherService;
    private final StringRedisTemplate stringRedisTemplate;

    public AdminCatalogService(ShopMapper shopMapper,
            MerchantMapper merchantMapper,
            VoucherMapper voucherMapper,
            IVoucherService voucherService,
            StringRedisTemplate stringRedisTemplate) {
        this.shopMapper = shopMapper;
        this.merchantMapper = merchantMapper;
        this.voucherMapper = voucherMapper;
        this.voucherService = voucherService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Page<Shop> listShops(int current, int size, String keyword) {
        AdminPrincipal principal = currentPrincipal();
        QueryWrapper<Shop> query = new QueryWrapper<>();
        if (!principal.isPlatform()) {
            query.eq("merchant_id", principal.getMerchantId());
        }
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            query.and(group -> group.like("name", value).or().like("address", value));
        }
        query.orderByDesc("id");
        return shopMapper.selectPage(
                new Page<>(Math.max(1, current), Math.min(100, Math.max(1, size))), query);
    }

    public Shop getShop(Long shopId) {
        return requireScopedShop(shopId, false);
    }

    @Transactional
    public Shop createShop(AdminShopCreateRequest request) {
        AdminPrincipal principal = currentPrincipal();
        validateCreateShop(request);
        Long merchantId = principal.isPlatform() ? request.getMerchantId() : principal.getMerchantId();
        requireActiveMerchant(merchantId);

        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setName(request.getName().trim());
        shop.setTypeId(request.getTypeId());
        shop.setImages(request.getImages().trim());
        shop.setArea(trimToNull(request.getArea()));
        shop.setAddress(request.getAddress().trim());
        shop.setX(request.getX());
        shop.setY(request.getY());
        shop.setAvgPrice(request.getAvgPrice());
        shop.setOpenHours(trimToNull(request.getOpenHours()));
        shop.setSold(0);
        shop.setComments(0);
        shop.setScore(0);
        if (shopMapper.insert(shop) != 1) {
            throw new IllegalStateException("商铺创建失败");
        }
        registerCacheSyncAfterCommit(null, shop);
        return shop;
    }

    @Transactional
    public Shop updateShop(Long shopId, AdminShopUpdateRequest request) {
        AdminPrincipal principal = currentPrincipal();
        Shop existing = requireScopedShop(shopId, true);
        Long previousTypeId = existing.getTypeId();
        Shop patch = controlledShopPatch(request);
        patch.setId(shopId);

        UpdateWrapper<Shop> scope = new UpdateWrapper<Shop>().eq("id", shopId);
        if (!principal.isPlatform()) {
            scope.eq("merchant_id", principal.getMerchantId());
        }
        if (shopMapper.update(patch, scope) != 1) {
            throw notFound();
        }
        Shop updated = copyWithPatch(existing, patch);
        registerCacheSyncAfterCommit(previousTypeId, updated);
        return updated;
    }

    @Transactional
    public void assignShop(Long shopId, Long merchantId) {
        AdminPrincipal principal = currentPrincipal();
        if (!principal.isPlatform()) {
            throw new ApiStatusException(HttpStatus.FORBIDDEN, "仅平台管理员可分配商铺归属");
        }
        Shop existing = requireScopedShop(shopId, true);
        Merchant currentMerchant = merchantMapper.selectById(existing.getMerchantId());
        if (currentMerchant == null ||
                !LEGACY_UNASSIGNED_MERCHANT_CODE.equals(currentMerchant.getCode())) {
            throw new IllegalArgumentException("仅历史待分配商铺可以认领，已归属商铺禁止换绑");
        }
        requireActiveMerchant(merchantId);
        Shop patch = new Shop();
        patch.setId(shopId);
        patch.setMerchantId(merchantId);
        UpdateWrapper<Shop> ownership = new UpdateWrapper<Shop>()
                .eq("id", shopId)
                .eq("merchant_id", existing.getMerchantId());
        if (shopMapper.update(patch, ownership) != 1) {
            throw notFound();
        }
        existing.setMerchantId(merchantId);
        registerCacheEvictionAfterCommit(shopId);
    }

    public List<Voucher> listVouchers(Long shopId) {
        AdminPrincipal principal = currentPrincipal();
        requireScopedShop(shopId, false);
        Long merchantScope = principal.isPlatform() ? null : principal.getMerchantId();
        return voucherMapper.queryVoucherOfShopForAdmin(shopId, merchantScope);
    }

    @Transactional
    public Voucher createVoucher(Long shopId, AdminVoucherCreateRequest request, boolean seckill) {
        requireScopedShop(shopId, true);
        validateVoucher(request, seckill);
        Voucher voucher = new Voucher();
        voucher.setShopId(shopId);
        voucher.setTitle(request.getTitle().trim());
        voucher.setSubTitle(trimToNull(request.getSubTitle()));
        voucher.setRules(trimToNull(request.getRules()));
        voucher.setPayValue(request.getPayValue());
        voucher.setActualValue(request.getActualValue());
        voucher.setStatus(1);
        if (seckill) {
            voucher.setStock(request.getStock());
            voucher.setBeginTime(request.getBeginTime());
            voucher.setEndTime(request.getEndTime());
            voucherService.addSeckillVoucher(voucher);
        } else {
            voucher.setType(0);
            if (!voucherService.save(voucher)) {
                throw new IllegalStateException("优惠券创建失败");
            }
        }
        return voucher;
    }

    private Shop requireScopedShop(Long shopId, boolean forUpdate) {
        if (shopId == null) {
            throw notFound();
        }
        AdminPrincipal principal = currentPrincipal();
        Shop shop;
        if (forUpdate) {
            shop = principal.isPlatform()
                    ? shopMapper.selectByIdForUpdate(shopId)
                    : shopMapper.selectByIdAndMerchantForUpdate(shopId, principal.getMerchantId());
        } else {
            QueryWrapper<Shop> query = new QueryWrapper<Shop>().eq("id", shopId);
            if (!principal.isPlatform()) {
                query.eq("merchant_id", principal.getMerchantId());
            }
            shop = shopMapper.selectOne(query.last("LIMIT 1"));
        }
        if (shop == null) {
            throw notFound();
        }
        return shop;
    }

    private AdminPrincipal currentPrincipal() {
        AdminPrincipal principal = AdminPrincipalHolder.get();
        if (principal == null) {
            throw new ApiStatusException(HttpStatus.UNAUTHORIZED, "后台登录已失效");
        }
        return principal;
    }

    private void requireActiveMerchant(Long merchantId) {
        Merchant merchant = merchantId == null ? null : merchantMapper.selectById(merchantId);
        if (merchant == null || !Integer.valueOf(1).equals(merchant.getStatus())) {
            throw new IllegalArgumentException("请选择有效的商户主体");
        }
    }

    private void validateCreateShop(AdminShopCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getName()) ||
                request.getTypeId() == null || !StringUtils.hasText(request.getImages()) ||
                !StringUtils.hasText(request.getAddress()) || request.getX() == null ||
                request.getY() == null) {
            throw new IllegalArgumentException("商铺名称、类型、图片、地址和坐标不能为空");
        }
        validateCoordinate(request.getX(), request.getY());
        validateMoney(request.getAvgPrice());
    }

    private Shop controlledShopPatch(AdminShopUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("商铺更新内容不能为空");
        }
        Shop patch = new Shop();
        if (request.getName() != null) {
            requireText(request.getName(), "商铺名称不能为空");
            patch.setName(request.getName().trim());
        }
        patch.setTypeId(request.getTypeId());
        if (request.getImages() != null) {
            requireText(request.getImages(), "商铺图片不能为空");
            patch.setImages(request.getImages().trim());
        }
        patch.setArea(trimToNull(request.getArea()));
        if (request.getAddress() != null) {
            requireText(request.getAddress(), "商铺地址不能为空");
            patch.setAddress(request.getAddress().trim());
        }
        patch.setX(request.getX());
        patch.setY(request.getY());
        patch.setAvgPrice(request.getAvgPrice());
        patch.setOpenHours(trimToNull(request.getOpenHours()));
        if ((request.getX() == null) != (request.getY() == null)) {
            throw new IllegalArgumentException("经纬度必须同时更新");
        }
        if (request.getX() != null) {
            validateCoordinate(request.getX(), request.getY());
        }
        validateMoney(request.getAvgPrice());
        if (patch.getName() == null && patch.getTypeId() == null && patch.getImages() == null &&
                patch.getArea() == null && patch.getAddress() == null && patch.getX() == null &&
                patch.getAvgPrice() == null && patch.getOpenHours() == null) {
            throw new IllegalArgumentException("商铺更新内容不能为空");
        }
        return patch;
    }

    private Shop copyWithPatch(Shop existing, Shop patch) {
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getTypeId() != null) existing.setTypeId(patch.getTypeId());
        if (patch.getImages() != null) existing.setImages(patch.getImages());
        if (patch.getArea() != null) existing.setArea(patch.getArea());
        if (patch.getAddress() != null) existing.setAddress(patch.getAddress());
        if (patch.getX() != null) {
            existing.setX(patch.getX());
            existing.setY(patch.getY());
        }
        if (patch.getAvgPrice() != null) existing.setAvgPrice(patch.getAvgPrice());
        if (patch.getOpenHours() != null) existing.setOpenHours(patch.getOpenHours());
        return existing;
    }

    private void validateVoucher(AdminVoucherCreateRequest request, boolean seckill) {
        if (request == null || !StringUtils.hasText(request.getTitle()) ||
                request.getPayValue() == null || request.getActualValue() == null ||
                request.getPayValue() < 0 || request.getActualValue() <= 0 ||
                request.getPayValue() > request.getActualValue()) {
            throw new IllegalArgumentException("优惠券标题和金额不合法");
        }
        if (seckill && (request.getStock() == null || request.getStock() <= 0 ||
                request.getBeginTime() == null || request.getEndTime() == null ||
                !request.getBeginTime().isBefore(request.getEndTime()))) {
            throw new IllegalArgumentException("秒杀库存和活动时间不合法");
        }
    }

    private void registerCacheSyncAfterCommit(Long previousTypeId, Shop current) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("商铺写入必须在事务中执行");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    stringRedisTemplate.delete(CACHE_SHOP_KEY + current.getId());
                    if (previousTypeId != null) {
                        stringRedisTemplate.opsForGeo().remove(
                                SHOP_GEO_KEY + previousTypeId, current.getId().toString());
                    }
                    if (current.getTypeId() != null && current.getX() != null && current.getY() != null) {
                        stringRedisTemplate.opsForGeo().add(
                                SHOP_GEO_KEY + current.getTypeId(),
                                new Point(current.getX(), current.getY()),
                                current.getId().toString());
                    }
                } catch (RuntimeException e) {
                    log.error("Failed to refresh shop caches after commit. shopId={}", current.getId(), e);
                }
            }
        });
    }

    private void registerCacheEvictionAfterCommit(Long shopId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("商铺归属变更必须在事务中执行");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
                } catch (RuntimeException e) {
                    log.error("Failed to evict shop cache after ownership assignment. shopId={}", shopId, e);
                }
            }
        });
    }

    private void validateCoordinate(Double x, Double y) {
        if (x == null || y == null || x < -180 || x > 180 || y < -90 || y > 90) {
            throw new IllegalArgumentException("商铺经纬度不合法");
        }
    }

    private void validateMoney(Long amount) {
        if (amount != null && amount < 0) {
            throw new IllegalArgumentException("金额不能为负数");
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private ApiStatusException notFound() {
        return new ApiStatusException(HttpStatus.NOT_FOUND, "资源不存在");
    }
}
