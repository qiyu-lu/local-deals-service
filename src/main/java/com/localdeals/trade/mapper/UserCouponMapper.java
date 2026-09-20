package com.localdeals.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.trade.entity.UserCoupon;
import com.localdeals.trade.mapper.VoucherMapper.VoucherSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * Validity starts now and lasts {@code tb_voucher.valid_days}, both on the database clock.
     * The voucher is read by the caller rather than joined here: user_coupon is sharded and
     * tb_voucher is not, so one statement cannot span both.
     */
    @Insert("INSERT INTO user_coupon (coupon_no, user_id, voucher_id, merchant_id, shop_id, source, source_ref, " +
            "status, valid_from, valid_to, verify_code) VALUES (#{couponNo}, #{userId}, #{voucher.voucherId}, " +
            "#{merchantId}, #{voucher.shopId}, #{source}, #{sourceRef}, 'AVAILABLE', NOW(3), " +
            "NOW(3) + INTERVAL #{voucher.validDays} DAY, #{verifyCode})")
    int insertFromVoucher(@Param("couponNo") String couponNo, @Param("userId") long userId,
                          @Param("voucher") VoucherSnapshot voucher,
                          @Param("merchantId") long merchantId,
                          @Param("source") String source, @Param("sourceRef") String sourceRef,
                          @Param("verifyCode") String verifyCode);

    @Select("SELECT * FROM user_coupon WHERE coupon_no = #{couponNo}")
    UserCoupon selectByCouponNo(@Param("couponNo") String couponNo);

    /**
     * Locking read: sees the latest committed status, not the transaction's snapshot.
     * The user id is in the predicate so the lock lands on one shard — without it the statement
     * would take a row lock in all eight tables.
     */
    @Select("SELECT * FROM user_coupon WHERE id = #{id} AND user_id = #{userId} FOR UPDATE")
    UserCoupon selectForUpdate(@Param("id") long id, @Param("userId") long userId);

    /** Reaches every shard: a verify code is random and carries no route. Rare and small. */
    @Select("SELECT * FROM user_coupon WHERE verify_code = #{verifyCode}")
    UserCoupon selectByVerifyCode(@Param("verifyCode") String verifyCode);

    /** BaseMapper's selectById would broadcast; the user id routes this one. */
    @Select("SELECT * FROM user_coupon WHERE id = #{id} AND user_id = #{userId}")
    UserCoupon selectOwned(@Param("id") long id, @Param("userId") long userId);

    /** The only way to consume a coupon: still AVAILABLE and inside its validity window. */
    @Update("UPDATE user_coupon SET status = 'USED', used_at = NOW(3), verified_by = #{adminId} " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 'AVAILABLE' " +
            "AND valid_from <= NOW(3) AND valid_to > NOW(3)")
    int markUsed(@Param("id") long id, @Param("userId") long userId, @Param("adminId") long adminId);

    @Update("UPDATE user_coupon SET status = #{to} WHERE coupon_no = #{couponNo} AND status = #{from}")
    int changeStatus(@Param("couponNo") String couponNo, @Param("from") String from, @Param("to") String to);

    /**
     * The lapsed coupons a sweep should take next. The bound cannot be in the UPDATE any more:
     * ShardingSphere refuses {@code UPDATE ... LIMIT} that routes to more than one node, because
     * the limit would apply per node and mean something else. Reading first also gives each
     * update its shard key.
     */
    @Select("SELECT id, user_id AS userId FROM user_coupon WHERE status = 'AVAILABLE' " +
            "AND valid_to <= NOW(3) ORDER BY valid_to LIMIT #{limit}")
    List<CouponRef> selectDue(@Param("limit") int limit);

    /** Expires one coupon; the status is the CAS, so a repeated sweep changes nothing. */
    @Update("UPDATE user_coupon SET status = 'EXPIRED' WHERE id = #{id} AND user_id = #{userId} " +
            "AND status = 'AVAILABLE' AND valid_to <= NOW(3)")
    int expire(@Param("id") long id, @Param("userId") long userId);

    /** A coupon named the way every statement on it has to name one: with its shard key. */
    record CouponRef(long id, long userId) {
    }

    @Select("SELECT * FROM user_coupon WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
    List<UserCoupon> selectByUser(@Param("userId") long userId, @Param("limit") int limit);
}
