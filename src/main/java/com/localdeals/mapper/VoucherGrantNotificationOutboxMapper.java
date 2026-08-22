package com.localdeals.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.entity.VoucherGrantNotificationOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface VoucherGrantNotificationOutboxMapper extends BaseMapper<VoucherGrantNotificationOutbox> {
    @Insert("INSERT INTO tb_voucher_grant_notification_outbox " +
            "(grant_id,merchant_id,user_id,event_type,status,attempts,next_attempt_time) " +
            "VALUES(#{grantId},#{merchantId},#{userId},'VOUCHER_GRANTED','PENDING',0,CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertPending(VoucherGrantNotificationOutbox outbox);

    @Select("SELECT o.*,g.campaign_id,g.voucher_id FROM tb_voucher_grant_notification_outbox o " +
            "JOIN tb_voucher_grant g ON g.id=o.grant_id " +
            "WHERE o.status='PENDING' AND o.next_attempt_time <= CURRENT_TIMESTAMP " +
            "ORDER BY o.next_attempt_time,o.id LIMIT #{limit} FOR UPDATE")
    List<VoucherGrantNotificationOutbox> selectDueForUpdate(@Param("limit") int limit);

    @Update("UPDATE tb_voucher_grant_notification_outbox SET status='PUBLISHED'," +
            "published_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=#{id} AND status='PENDING'")
    int markPublished(@Param("id") Long id);

    @Update("UPDATE tb_voucher_grant_notification_outbox SET attempts=#{attempts}," +
            "next_attempt_time=#{nextAttemptTime},last_error=#{lastError} " +
            "WHERE id=#{id} AND status='PENDING'")
    int markRetry(@Param("id") Long id, @Param("attempts") int attempts,
            @Param("nextAttemptTime") LocalDateTime nextAttemptTime,
            @Param("lastError") String lastError);

    @Select("SELECT COUNT(*) FROM tb_voucher_grant_notification_outbox WHERE status='PENDING'")
    long countPending();

    @Select("SELECT MIN(create_time) FROM tb_voucher_grant_notification_outbox WHERE status='PENDING'")
    LocalDateTime oldestPendingCreateTime();
}
