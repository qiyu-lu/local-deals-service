package com.localdeals.service.impl;

import com.localdeals.entity.ShopType;
import com.localdeals.mapper.ShopTypeMapper;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.utils.CacheClient;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.localdeals.utils.RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    private final CacheClient cacheClient;

    public ShopTypeServiceImpl(CacheClient cacheClient) {
        this.cacheClient = cacheClient;
    }

    @Override
    public List<ShopType> queryTypeList(){
        return cacheClient.queryListWithPassThrough(
                LocalDealsMetrics.CacheResource.SHOP_TYPE,
                CACHE_SHOP_TYPE_LIST_KEY,
                ShopType.class,
                this::loadShopTypes,
                ShopType::getId);
    }

    protected List<ShopType> loadShopTypes() {
        return query().orderByAsc("sort").list();
    }
}
