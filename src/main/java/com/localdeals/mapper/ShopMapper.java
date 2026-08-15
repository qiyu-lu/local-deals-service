package com.localdeals.mapper;

import com.localdeals.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ShopMapper extends BaseMapper<Shop> {

    @Select("SELECT * FROM tb_shop WHERE id = #{shopId} FOR UPDATE")
    Shop selectByIdForUpdate(@Param("shopId") Long shopId);

    @Select("SELECT * FROM tb_shop WHERE id = #{shopId} AND merchant_id = #{merchantId} FOR UPDATE")
    Shop selectByIdAndMerchantForUpdate(@Param("shopId") Long shopId,
            @Param("merchantId") Long merchantId);
}
