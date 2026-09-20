package com.localdeals.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.trade.entity.TradeOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    /**
     * Creates a PENDING_PAY order from a snapshot the caller already read off the voucher.
     * The deadline is still computed by the database clock, which every later comparison (close
     * CAS, fallback scan) also uses. A duplicate raises DuplicateKeyException for the caller to
     * classify, exactly as it did when this was an INSERT ... SELECT.
     */
    @Insert("INSERT INTO trade_order (order_no, user_id, voucher_id, shop_id, merchant_id, amount, " +
            "status, expire_at) VALUES (#{orderNo}, #{userId}, #{voucher.voucherId}, #{voucher.shopId}, " +
            "#{voucher.merchantId}, #{voucher.payValue}, 'PENDING_PAY', " +
            "DATE_ADD(NOW(3), INTERVAL #{payTimeoutSeconds} SECOND))")
    int insertPending(@Param("orderNo") long orderNo, @Param("userId") long userId,
                      @Param("voucher") VoucherMapper.VoucherSnapshot voucher,
                      @Param("payTimeoutSeconds") long payTimeoutSeconds);

    /**
     * The batch form: one statement for the whole slice of a consumer batch that belongs to one
     * voucher. ShardingSphere splits the rows across the tables their user ids belong to and
     * sums the affected rows. {@code IGNORE} makes a redelivered message a no-op instead of
     * failing the batch; the caller compares the count with the slice size and falls back to the
     * single-message path when they differ.
     */
    @Insert("<script>INSERT IGNORE INTO trade_order (order_no, user_id, voucher_id, shop_id, " +
            "merchant_id, amount, status, expire_at) VALUES " +
            "<foreach collection=\"rows\" item=\"row\" separator=\",\">" +
            "(#{row.orderNo}, #{row.userId}, #{voucher.voucherId}, #{voucher.shopId}, " +
            "#{voucher.merchantId}, #{voucher.payValue}, 'PENDING_PAY', " +
            "DATE_ADD(NOW(3), INTERVAL #{payTimeoutSeconds} SECOND))" +
            "</foreach></script>")
    int insertPendingBatch(@Param("rows") List<SeckillOrderRow> rows,
                           @Param("voucher") VoucherMapper.VoucherSnapshot voucher,
                           @Param("payTimeoutSeconds") long payTimeoutSeconds);

    /** One order of a batch insert. user_id is what routes the row to its shard. */
    record SeckillOrderRow(long orderNo, long userId) {
    }

    /**
     * The single status-changing statement. Only {@code OrderStateMachine} may call it.
     * Zero affected rows means the order is not in {@code from} (or not expired yet).
     */
    @Update("<script>UPDATE trade_order SET status = #{to}, version = version + 1" +
            "<if test=\"to == 'PAID'\">, paid_at = NOW(3)</if>" +
            "<if test=\"to == 'CLOSED'\">, closed_at = NOW(3)</if>" +
            "<if test=\"to == 'USED'\">, used_at = NOW(3)</if>" +
            "<if test=\"to == 'REFUNDED'\">, refunded_at = NOW(3)</if>" +
            "<if test=\"releasesInventory\">, release_pending = 1</if>" +
            " WHERE order_no = #{orderNo} AND status = #{from}" +
            "<if test=\"requiresExpiry\"> AND expire_at &lt;= NOW(3)</if>" +
            "</script>")
    int transition(@Param("orderNo") long orderNo, @Param("from") String from, @Param("to") String to,
                   @Param("requiresExpiry") boolean requiresExpiry,
                   @Param("releasesInventory") boolean releasesInventory);

    @Select("SELECT * FROM trade_order WHERE order_no = #{orderNo} FOR UPDATE")
    TradeOrder selectForUpdate(@Param("orderNo") long orderNo);

    @Select("SELECT * FROM trade_order WHERE user_id = #{userId} AND voucher_id = #{voucherId} " +
            "AND active_flag = 1")
    TradeOrder selectActive(@Param("userId") long userId, @Param("voucherId") long voucherId);

    /** Deadline checked on the database clock, the same one the close uses. */
    @Select("SELECT COUNT(*) FROM trade_order WHERE order_no = #{orderNo} AND status = 'PENDING_PAY' " +
            "AND expire_at > NOW(3)")
    int countPayable(@Param("orderNo") long orderNo);

    @Select("SELECT * FROM trade_order WHERE user_id = #{userId} ORDER BY create_time DESC LIMIT #{limit}")
    List<TradeOrder> selectByUser(@Param("userId") long userId, @Param("limit") int limit);

    @Update("UPDATE trade_order SET release_pending = NULL WHERE order_no = #{orderNo} AND release_pending = 1")
    int clearReleasePending(@Param("orderNo") long orderNo);

    /** Unpaid orders past their deadline by more than {@code graceSeconds}: their timer message was lost. */
    @Select("SELECT order_no FROM trade_order WHERE status = 'PENDING_PAY' " +
            "AND expire_at <= NOW(3) - INTERVAL #{graceSeconds} SECOND ORDER BY expire_at LIMIT #{limit}")
    List<Long> selectOverduePending(@Param("graceSeconds") long graceSeconds, @Param("limit") int limit);

    @Select("SELECT * FROM trade_order WHERE release_pending = 1 " +
            "AND update_time <= NOW(3) - INTERVAL #{olderThanSeconds} SECOND LIMIT #{limit}")
    List<TradeOrder> selectReleasePending(@Param("olderThanSeconds") long olderThanSeconds,
                                          @Param("limit") int limit);
}
