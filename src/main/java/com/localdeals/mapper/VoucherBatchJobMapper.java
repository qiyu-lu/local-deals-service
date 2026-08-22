package com.localdeals.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.entity.VoucherBatchJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface VoucherBatchJobMapper extends BaseMapper<VoucherBatchJob> {
    String JOB_WITH_COUNTS =
            "SELECT j.*, " +
                    "COALESCE((SELECT COUNT(*) FROM tb_voucher_batch_item i WHERE i.job_id=j.id AND i.status='GRANTED'),0) AS granted_item_count, " +
                    "COALESCE((SELECT COUNT(*) FROM tb_voucher_batch_item i WHERE i.job_id=j.id AND i.status='IDEMPOTENT'),0) AS idempotent_item_count, " +
                    "COALESCE((SELECT COUNT(*) FROM tb_voucher_batch_item i WHERE i.job_id=j.id AND i.status='SKIPPED'),0) AS skipped_item_count, " +
                    "COALESCE((SELECT COUNT(*) FROM tb_voucher_batch_item i WHERE i.job_id=j.id AND i.status='FAILED'),0) AS failed_item_count, " +
                    "COALESCE((SELECT COUNT(*) FROM tb_voucher_batch_item i WHERE i.job_id=j.id AND i.status='PENDING'),0) AS pending_item_count " +
                    "FROM tb_voucher_batch_job j ";

    @Select("SELECT * FROM tb_voucher_batch_job WHERE merchant_id=#{merchantId} " +
            "AND request_id=#{requestId} LIMIT 1")
    VoucherBatchJob selectByMerchantAndRequest(@Param("merchantId") Long merchantId,
            @Param("requestId") String requestId);

    @Select("SELECT * FROM tb_voucher_batch_job WHERE merchant_id=#{merchantId} " +
            "AND request_id=#{requestId} LIMIT 1 FOR UPDATE")
    VoucherBatchJob selectByMerchantAndRequestForUpdate(@Param("merchantId") Long merchantId,
            @Param("requestId") String requestId);

    @Select(JOB_WITH_COUNTS + "WHERE j.id=#{jobId} AND j.merchant_id=#{merchantId} LIMIT 1")
    VoucherBatchJob selectScoped(@Param("jobId") Long jobId, @Param("merchantId") Long merchantId);

    @Select(JOB_WITH_COUNTS + "WHERE j.id=#{jobId} AND j.merchant_id=#{merchantId} LIMIT 1 FOR UPDATE")
    VoucherBatchJob selectScopedWithCountsForUpdate(@Param("jobId") Long jobId,
            @Param("merchantId") Long merchantId);

    @Select(JOB_WITH_COUNTS + "WHERE j.merchant_id=#{merchantId} ORDER BY j.id DESC " +
            "LIMIT #{limit} OFFSET #{offset}")
    List<VoucherBatchJob> selectPageScoped(@Param("merchantId") Long merchantId,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM tb_voucher_batch_job WHERE merchant_id=#{merchantId}")
    long countScoped(@Param("merchantId") Long merchantId);

    @Select("SELECT * FROM tb_voucher_batch_job WHERE status='SNAPSHOTTING' " +
            "ORDER BY id LIMIT 1 FOR UPDATE")
    VoucherBatchJob selectNextSnapshotForUpdate();

    @Select("SELECT * FROM tb_voucher_batch_job WHERE status IN ('READY','RUNNING') " +
            "ORDER BY id LIMIT 1 FOR UPDATE")
    VoucherBatchJob selectNextRunnableForUpdate();

    @Select("SELECT * FROM tb_voucher_batch_job WHERE id=#{jobId} AND merchant_id=#{merchantId} FOR UPDATE")
    VoucherBatchJob selectScopedForUpdate(@Param("jobId") Long jobId,
            @Param("merchantId") Long merchantId);

    @Insert("INSERT INTO tb_voucher_batch_job " +
            "(merchant_id,campaign_id,operator_id,request_id,captured_rule_version,target_tag_id,status,target_count) " +
            "VALUES(#{merchantId},#{campaignId},#{operatorId},#{requestId},#{capturedRuleVersion},#{targetTagId},'SNAPSHOTTING',0) " +
            "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertIdempotent(VoucherBatchJob job);

    @Update("UPDATE tb_voucher_batch_job SET target_count=#{targetCount},status='READY'," +
            "update_time=CURRENT_TIMESTAMP WHERE id=#{jobId} AND status='SNAPSHOTTING'")
    int markSnapshotReady(@Param("jobId") Long jobId, @Param("targetCount") int targetCount);

    @Update("UPDATE tb_voucher_batch_job SET status='RUNNING'," +
            "start_time=COALESCE(start_time,CURRENT_TIMESTAMP),update_time=CURRENT_TIMESTAMP " +
            "WHERE id=#{jobId} AND status='READY'")
    int markRunning(@Param("jobId") Long jobId);

    @Update("UPDATE tb_voucher_batch_job SET status='COMPLETED',finish_time=CURRENT_TIMESTAMP," +
            "update_time=CURRENT_TIMESTAMP WHERE id=#{jobId} AND status='RUNNING'")
    int markCompleted(@Param("jobId") Long jobId);

    @Update("UPDATE tb_voucher_batch_job SET status='PARTIAL_FAILED',finish_time=CURRENT_TIMESTAMP," +
            "update_time=CURRENT_TIMESTAMP WHERE id=#{jobId} AND status='RUNNING'")
    int markPartialFailed(@Param("jobId") Long jobId);

    @Update("UPDATE tb_voucher_batch_job SET status='PAUSED',update_time=CURRENT_TIMESTAMP " +
            "WHERE id=#{jobId} AND merchant_id=#{merchantId} AND status IN ('READY','RUNNING')")
    int pauseScoped(@Param("jobId") Long jobId, @Param("merchantId") Long merchantId);

    @Update("UPDATE tb_voucher_batch_job SET status='READY',finish_time=NULL,update_time=CURRENT_TIMESTAMP " +
            "WHERE id=#{jobId} AND merchant_id=#{merchantId} AND status='PAUSED'")
    int resumeScoped(@Param("jobId") Long jobId, @Param("merchantId") Long merchantId);

    @Update("UPDATE tb_voucher_batch_job SET status='READY',finish_time=NULL,update_time=CURRENT_TIMESTAMP " +
            "WHERE id=#{jobId} AND merchant_id=#{merchantId} AND status='PARTIAL_FAILED'")
    int reopenAfterRetry(@Param("jobId") Long jobId, @Param("merchantId") Long merchantId);
}
