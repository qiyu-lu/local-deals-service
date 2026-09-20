package com.localdeals.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.trade.entity.Voucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);

    List<Voucher> queryVoucherOfShopForAdmin(@Param("shopId") Long shopId,
            @Param("merchantId") Long merchantId);

    /**
     * What an order or a coupon has to copy off the voucher at creation time.
     *
     * <p>Both used to be written by {@code INSERT ... SELECT ... JOIN tb_shop}, which a sharded
     * table cannot be the target of: the rows of one statement belong to different databases,
     * and the vouchers they read live in only one. The join happens here instead, one read per
     * batch, and the insert becomes plain VALUES.</p>
     */
    @Select("SELECT v.id AS voucherId, v.shop_id AS shopId, s.merchant_id AS merchantId, " +
            "v.pay_value AS payValue, v.valid_days AS validDays " +
            "FROM tb_voucher v JOIN tb_shop s ON s.id = v.shop_id WHERE v.id = #{voucherId}")
    VoucherSnapshot selectSnapshot(@Param("voucherId") long voucherId);

    /** The voucher fields an order and a coupon snapshot; null when the voucher or its shop is gone. */
    record VoucherSnapshot(long voucherId, long shopId, long merchantId, long payValue, int validDays) {
    }
}
