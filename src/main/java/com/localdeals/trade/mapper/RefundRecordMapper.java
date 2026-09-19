package com.localdeals.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.trade.entity.RefundRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface RefundRecordMapper extends BaseMapper<RefundRecord> {

    @Select("SELECT * FROM refund_record WHERE refund_no = #{refundNo} FOR UPDATE")
    RefundRecord selectForUpdate(@Param("refundNo") String refundNo);

    @Select("SELECT * FROM refund_record WHERE refund_no = #{refundNo}")
    RefundRecord selectByRefundNo(@Param("refundNo") String refundNo);

    @Update("UPDATE refund_record SET status = 'SUCCESS', channel_refund_no = #{channelRefundNo}, " +
            "finished_at = NOW(3) WHERE refund_no = #{refundNo} AND status = 'PENDING'")
    int markSucceeded(@Param("refundNo") String refundNo, @Param("channelRefundNo") String channelRefundNo);

    @Update("UPDATE refund_record SET request_attempts = request_attempts + 1 WHERE refund_no = #{refundNo}")
    int countRequest(@Param("refundNo") String refundNo);

    /** Refunds the channel has not confirmed for a while: ask again (the channel is idempotent per refund_no). */
    @Select("SELECT * FROM refund_record WHERE status = 'PENDING' " +
            "AND update_time <= NOW(3) - INTERVAL #{olderThanSeconds} SECOND ORDER BY update_time LIMIT #{limit}")
    List<RefundRecord> selectStalePending(@Param("olderThanSeconds") long olderThanSeconds,
                                          @Param("limit") int limit);
}
