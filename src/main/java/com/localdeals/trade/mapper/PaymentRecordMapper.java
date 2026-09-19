package com.localdeals.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.trade.entity.PaymentRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PaymentRecordMapper extends BaseMapper<PaymentRecord> {

    /** Serialises every callback for one payment: duplicates wait here and then see SUCCESS. */
    @Select("SELECT * FROM payment_record WHERE pay_no = #{payNo} FOR UPDATE")
    PaymentRecord selectForUpdate(@Param("payNo") String payNo);

    @Select("SELECT * FROM payment_record WHERE order_no = #{orderNo} AND status = 'WAITING' " +
            "ORDER BY id DESC LIMIT 1")
    PaymentRecord selectWaiting(@Param("orderNo") long orderNo);

    @Select("SELECT COUNT(*) FROM payment_record WHERE order_no = #{orderNo}")
    int countByOrder(@Param("orderNo") long orderNo);

    /** The payment that actually paid the order (the first successful one). */
    @Select("SELECT * FROM payment_record WHERE order_no = #{orderNo} AND status = 'SUCCESS' " +
            "ORDER BY paid_at, id LIMIT 1")
    PaymentRecord selectFirstSuccess(@Param("orderNo") long orderNo);

    @Update("UPDATE payment_record SET status = #{status}, channel_txn_no = #{channelTxnNo}, " +
            "paid_amount = #{paidAmount}, paid_at = NOW(3) WHERE pay_no = #{payNo} AND status = 'WAITING'")
    int settle(@Param("payNo") String payNo, @Param("status") String status,
               @Param("channelTxnNo") String channelTxnNo, @Param("paidAmount") long paidAmount);
}
