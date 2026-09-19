package com.localdeals.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.trade.entity.UserCoupon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /** Validity starts now and lasts {@code tb_voucher.valid_days}, both on the database clock. */
    @Insert("INSERT INTO user_coupon (coupon_no, user_id, voucher_id, merchant_id, shop_id, source, source_ref, " +
            "status, valid_from, valid_to, verify_code) " +
            "SELECT #{couponNo}, #{userId}, v.id, #{merchantId}, v.shop_id, #{source}, #{sourceRef}, 'AVAILABLE', " +
            "NOW(3), NOW(3) + INTERVAL v.valid_days DAY, #{verifyCode} FROM tb_voucher v WHERE v.id = #{voucherId}")
    int insertFromVoucher(@Param("couponNo") String couponNo, @Param("userId") long userId,
                          @Param("voucherId") long voucherId, @Param("merchantId") long merchantId,
                          @Param("source") String source, @Param("sourceRef") String sourceRef,
                          @Param("verifyCode") String verifyCode);

    @Select("SELECT * FROM user_coupon WHERE coupon_no = #{couponNo}")
    UserCoupon selectByCouponNo(@Param("couponNo") String couponNo);

    /** Locking read: sees the latest committed status, not the transaction's snapshot. */
    @Select("SELECT * FROM user_coupon WHERE id = #{id} FOR UPDATE")
    UserCoupon selectForUpdate(@Param("id") long id);

    @Select("SELECT * FROM user_coupon WHERE verify_code = #{verifyCode}")
    UserCoupon selectByVerifyCode(@Param("verifyCode") String verifyCode);

    /** The only way to consume a coupon: still AVAILABLE and inside its validity window. */
    @Update("UPDATE user_coupon SET status = 'USED', used_at = NOW(3), verified_by = #{adminId} " +
            "WHERE id = #{id} AND status = 'AVAILABLE' AND valid_from <= NOW(3) AND valid_to > NOW(3)")
    int markUsed(@Param("id") long id, @Param("adminId") long adminId);

    @Update("UPDATE user_coupon SET status = #{to} WHERE coupon_no = #{couponNo} AND status = #{from}")
    int transition(@Param("couponNo") String couponNo, @Param("from") String from, @Param("to") String to);

    @Update("UPDATE user_coupon SET status = 'EXPIRED' WHERE status = 'AVAILABLE' AND valid_to <= NOW(3) " +
            "LIMIT #{limit}")
    int expireDue(@Param("limit") int limit);

    @Select("SELECT * FROM user_coupon WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
    List<UserCoupon> selectByUser(@Param("userId") long userId, @Param("limit") int limit);
}
