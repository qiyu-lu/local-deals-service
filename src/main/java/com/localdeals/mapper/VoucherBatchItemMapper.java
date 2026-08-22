package com.localdeals.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.entity.VoucherBatchItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface VoucherBatchItemMapper extends BaseMapper<VoucherBatchItem> {
    @Insert("INSERT INTO tb_voucher_batch_item (job_id,user_id,status,attempts) " +
            "SELECT j.id,m.user_id,'PENDING',0 " +
            "FROM tb_voucher_batch_job j " +
            "JOIN tb_marketing_tag t ON t.id=j.target_tag_id AND t.merchant_id=j.merchant_id " +
            "JOIN tb_marketing_tag_member m ON m.tag_id=t.id AND m.merchant_id=t.merchant_id " +
            "WHERE j.id=#{jobId} AND j.status='SNAPSHOTTING' " +
            "AND j.merchant_id=#{merchantId} AND t.id=#{tagId} AND t.status='ACTIVE' " +
            "AND m.status='ACTIVE' AND (m.expire_time IS NULL OR m.expire_time > CURRENT_TIMESTAMP)")
    int snapshotActiveMembers(@Param("jobId") Long jobId, @Param("merchantId") Long merchantId,
            @Param("tagId") Long tagId);

    @Select("SELECT * FROM tb_voucher_batch_item WHERE job_id=#{jobId} AND status='PENDING' " +
            "ORDER BY id LIMIT #{limit} FOR UPDATE")
    List<VoucherBatchItem> selectPendingForUpdate(@Param("jobId") Long jobId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM tb_voucher_batch_item WHERE job_id=#{jobId}")
    int countByJob(@Param("jobId") Long jobId);

    @Select("SELECT COUNT(*) FROM tb_voucher_batch_item WHERE job_id=#{jobId} AND status=#{status}")
    long countByJobAndStatus(@Param("jobId") Long jobId, @Param("status") String status);

    @Select("SELECT i.* FROM tb_voucher_batch_item i JOIN tb_voucher_batch_job j ON j.id=i.job_id " +
            "WHERE i.job_id=#{jobId} AND j.merchant_id=#{merchantId} " +
            "ORDER BY i.id LIMIT #{limit} OFFSET #{offset}")
    List<VoucherBatchItem> selectPageScoped(@Param("jobId") Long jobId,
            @Param("merchantId") Long merchantId, @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("SELECT i.* FROM tb_voucher_batch_item i JOIN tb_voucher_batch_job j ON j.id=i.job_id " +
            "WHERE i.job_id=#{jobId} AND j.merchant_id=#{merchantId} AND i.status=#{status} " +
            "ORDER BY i.id LIMIT #{limit} OFFSET #{offset}")
    List<VoucherBatchItem> selectPageScopedByStatus(@Param("jobId") Long jobId,
            @Param("merchantId") Long merchantId, @Param("status") String status,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM tb_voucher_batch_item i JOIN tb_voucher_batch_job j ON j.id=i.job_id " +
            "WHERE i.job_id=#{jobId} AND j.merchant_id=#{merchantId}")
    long countScoped(@Param("jobId") Long jobId, @Param("merchantId") Long merchantId);

    @Select("SELECT COUNT(*) FROM tb_voucher_batch_item i JOIN tb_voucher_batch_job j ON j.id=i.job_id " +
            "WHERE i.job_id=#{jobId} AND j.merchant_id=#{merchantId} AND i.status=#{status}")
    long countScopedByStatus(@Param("jobId") Long jobId, @Param("merchantId") Long merchantId,
            @Param("status") String status);

    @Update("UPDATE tb_voucher_batch_item SET status=#{status},grant_id=#{grantId}," +
            "attempts=attempts+1,last_error_code=#{errorCode},last_error_message=#{errorMessage}," +
            "update_time=CURRENT_TIMESTAMP WHERE id=#{itemId} AND status='PENDING'")
    int markOutcome(@Param("itemId") Long itemId, @Param("status") String status,
            @Param("grantId") Long grantId, @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);

    @Update("UPDATE tb_voucher_batch_item SET status='PENDING',last_error_code=NULL," +
            "last_error_message=NULL,update_time=CURRENT_TIMESTAMP WHERE job_id=#{jobId} " +
            "AND status='FAILED'")
    int resetFailed(@Param("jobId") Long jobId);
}
