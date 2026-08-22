package com.localdeals.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.entity.VoucherCampaign;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface VoucherCampaignMapper extends BaseMapper<VoucherCampaign> {
    String CAMPAIGN_VIEW = "SELECT c.*,v.type AS voucher_type,v.status AS voucher_status," +
            "s.merchant_id AS voucher_merchant_id,v.title AS voucher_title " +
            "FROM tb_voucher_campaign c JOIN tb_voucher v ON v.id=c.voucher_id " +
            "JOIN tb_shop s ON s.id=v.shop_id AND s.merchant_id=c.merchant_id ";

    @Select(CAMPAIGN_VIEW + "WHERE c.merchant_id=#{merchantId} ORDER BY c.id DESC")
    List<VoucherCampaign> selectAllScoped(@Param("merchantId") Long merchantId);

    @Select(CAMPAIGN_VIEW + "WHERE c.id=#{campaignId} AND c.merchant_id=#{merchantId} LIMIT 1")
    VoucherCampaign selectScoped(@Param("campaignId") Long campaignId,
            @Param("merchantId") Long merchantId);

    @Select(CAMPAIGN_VIEW + "WHERE c.id=#{campaignId} AND c.merchant_id=#{merchantId} FOR UPDATE")
    VoucherCampaign selectScopedForUpdate(@Param("campaignId") Long campaignId,
            @Param("merchantId") Long merchantId);

    @Select(CAMPAIGN_VIEW + "WHERE c.id=#{campaignId} FOR UPDATE")
    VoucherCampaign selectForUserClaimForUpdate(@Param("campaignId") Long campaignId);

    @Select("SELECT COUNT(*) FROM tb_voucher v JOIN tb_shop s ON s.id=v.shop_id " +
            "WHERE v.id=#{voucherId} AND v.type=0 AND v.status=1 AND s.merchant_id=#{merchantId}")
    int countActiveRegularVoucherScoped(@Param("voucherId") Long voucherId,
            @Param("merchantId") Long merchantId);

    @Update("UPDATE tb_voucher_campaign SET voucher_id=#{voucherId},name=#{name}," +
            "grant_mode=#{grantMode},eligibility_type=#{eligibilityType}," +
            "required_tag_id=#{requiredTagId},begin_time=#{beginTime},end_time=#{endTime}," +
            "quota_total=#{quotaTotal},rule_version=rule_version+1 " +
            "WHERE id=#{id} AND merchant_id=#{merchantId} " +
            "AND status=#{expectedStatus} AND rule_version=#{expectedRuleVersion} " +
            "AND status IN ('DRAFT','PAUSED') " +
            "AND granted_count <= #{quotaTotal} " +
            "AND (granted_count=0 OR voucher_id=#{voucherId})")
    int updateDefinitionScoped(VoucherCampaign campaign);

    @Update("UPDATE tb_voucher_campaign SET status=#{status},rule_version=rule_version+1 " +
            "WHERE id=#{campaignId} AND merchant_id=#{merchantId} AND status=#{expectedStatus} " +
            "AND rule_version=#{expectedRuleVersion}")
    int updateStatusScoped(@Param("campaignId") Long campaignId,
            @Param("merchantId") Long merchantId, @Param("expectedStatus") String expectedStatus,
            @Param("expectedRuleVersion") Long expectedRuleVersion, @Param("status") String status);

    @Update("UPDATE tb_voucher_campaign SET granted_count=granted_count+1 " +
            "WHERE id=#{campaignId} AND merchant_id=#{merchantId} AND status='ACTIVE' " +
            "AND voucher_id=#{voucherId} AND rule_version=#{ruleVersion} AND begin_time <= CURRENT_TIMESTAMP " +
            "AND end_time > CURRENT_TIMESTAMP AND granted_count < quota_total")
    int incrementGrantCount(@Param("campaignId") Long campaignId,
            @Param("merchantId") Long merchantId, @Param("voucherId") Long voucherId,
            @Param("ruleVersion") Long ruleVersion);

    @Select("SELECT CURRENT_TIMESTAMP")
    LocalDateTime currentDatabaseTime();

    @Select("SELECT c.*,v.title AS voucher_title," +
            "CASE WHEN g.id IS NULL THEN 0 ELSE 1 END AS already_granted," +
            "CASE WHEN c.eligibility_type='ALL' THEN 1 " +
            "WHEN EXISTS (SELECT 1 FROM tb_marketing_tag_member tm " +
            "JOIN tb_marketing_tag t ON t.id=tm.tag_id AND t.merchant_id=tm.merchant_id " +
            "WHERE tm.merchant_id=c.merchant_id AND tm.tag_id=c.required_tag_id " +
            "AND tm.user_id=#{userId} AND tm.status='ACTIVE' AND t.status='ACTIVE' " +
            "AND (tm.expire_time IS NULL OR tm.expire_time > CURRENT_TIMESTAMP)) THEN 1 ELSE 0 END " +
            "AS tag_eligible FROM tb_voucher_campaign c " +
            "JOIN tb_voucher v ON v.id=c.voucher_id AND v.type=0 AND v.status=1 " +
            "JOIN tb_shop s ON s.id=v.shop_id AND s.id=#{shopId} AND s.merchant_id=c.merchant_id " +
            "LEFT JOIN tb_voucher_grant g ON g.campaign_id=c.id AND g.user_id=#{userId} " +
            "WHERE c.status <> 'CLOSED' ORDER BY c.id DESC")
    List<VoucherCampaign> selectForUserShop(@Param("shopId") Long shopId,
            @Param("userId") Long userId);
}
