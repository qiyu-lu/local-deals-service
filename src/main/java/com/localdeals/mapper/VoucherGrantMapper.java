package com.localdeals.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.entity.VoucherGrant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VoucherGrantMapper extends BaseMapper<VoucherGrant> {
    @Select("SELECT * FROM tb_voucher_grant WHERE campaign_id=#{campaignId} " +
            "AND user_id=#{userId} AND idempotency_key='ONCE' LIMIT 1")
    VoucherGrant selectByCampaignAndUser(@Param("campaignId") Long campaignId,
            @Param("userId") Long userId);

    @Select("SELECT * FROM tb_voucher_grant WHERE campaign_id=#{campaignId} " +
            "AND merchant_id=#{merchantId} AND user_id=#{userId} " +
            "AND idempotency_key='ONCE' LIMIT 1")
    VoucherGrant selectByCampaignAndMerchantAndUser(@Param("campaignId") Long campaignId,
            @Param("merchantId") Long merchantId, @Param("userId") Long userId);

    @Select("SELECT * FROM tb_voucher_grant WHERE campaign_id=#{campaignId} " +
            "AND user_id=#{userId} AND idempotency_key=#{idempotencyKey} LIMIT 1")
    VoucherGrant selectByCampaignAndUserAndKey(@Param("campaignId") Long campaignId,
            @Param("userId") Long userId, @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM tb_voucher_grant WHERE campaign_id=#{campaignId} " +
            "AND merchant_id=#{merchantId} AND user_id=#{userId} " +
            "AND idempotency_key=#{idempotencyKey} LIMIT 1")
    VoucherGrant selectByCampaignAndMerchantAndUserAndKey(@Param("campaignId") Long campaignId,
            @Param("merchantId") Long merchantId, @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT g.* FROM tb_voucher_grant g WHERE g.campaign_id=#{campaignId} " +
            "AND g.merchant_id=#{merchantId} " +
            "AND EXISTS (SELECT 1 FROM tb_voucher_campaign c WHERE c.id=g.campaign_id " +
            "AND c.merchant_id=g.merchant_id AND c.voucher_id=g.voucher_id) " +
            "ORDER BY g.id DESC")
    List<VoucherGrant> selectCampaignGrantsScoped(@Param("campaignId") Long campaignId,
            @Param("merchantId") Long merchantId);

    @Select("SELECT g.*,c.name AS campaign_name,v.title AS voucher_title,v.pay_value,v.actual_value," +
            "s.id AS shop_id,s.name AS shop_name FROM tb_voucher_grant g " +
            "JOIN tb_voucher_campaign c ON c.id=g.campaign_id AND c.merchant_id=g.merchant_id " +
            "AND c.voucher_id=g.voucher_id JOIN tb_voucher v ON v.id=g.voucher_id " +
            "JOIN tb_shop s ON s.id=v.shop_id AND s.merchant_id=g.merchant_id " +
            "WHERE g.user_id=#{userId} ORDER BY g.granted_at DESC,g.id DESC")
    List<VoucherGrant> selectMine(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM tb_voucher_grant WHERE campaign_id=#{campaignId} " +
            "AND merchant_id=#{merchantId}")
    int countByCampaignScoped(@Param("campaignId") Long campaignId,
            @Param("merchantId") Long merchantId);
}
