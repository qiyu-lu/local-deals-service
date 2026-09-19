package com.localdeals.merchant.service;

import com.localdeals.platform.dto.Result;
import com.localdeals.merchant.entity.Shop;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {
    public Result queryShopById(Long id);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y, String sortBy);


    Result searchShops(String keyword, Double x, Double y, Integer radius, Long typeId, Integer current);
}
