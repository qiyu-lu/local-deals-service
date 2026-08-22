package com.localdeals.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.entity.MarketingTagMember;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface MarketingTagMemberMapper extends BaseMapper<MarketingTagMember> {
    @Select("SELECT COUNT(*) FROM tb_user u WHERE u.id = #{userId} AND (" +
            "EXISTS (SELECT 1 FROM tb_voucher_order o " +
            "JOIN tb_voucher v ON v.id = o.voucher_id " +
            "JOIN tb_shop s ON s.id = v.shop_id " +
            "WHERE o.user_id = u.id AND s.merchant_id = #{merchantId}) OR " +
            "EXISTS (SELECT 1 FROM tb_voucher_grant g " +
            "WHERE g.user_id = u.id AND g.merchant_id = #{merchantId}))")
    int countBusinessRelationship(@Param("merchantId") Long merchantId,
            @Param("userId") Long userId);

    @Select("SELECT m.* FROM tb_marketing_tag_member m " +
            "JOIN tb_marketing_tag t ON t.id = m.tag_id AND t.merchant_id = m.merchant_id " +
            "WHERE m.merchant_id = #{merchantId} AND m.tag_id = #{tagId} " +
            "AND m.user_id = #{userId} AND m.status = 'ACTIVE' AND t.status = 'ACTIVE' " +
            "AND (m.expire_time IS NULL OR m.expire_time > CURRENT_TIMESTAMP) FOR UPDATE")
    MarketingTagMember selectEligibleForUpdate(@Param("merchantId") Long merchantId,
            @Param("tagId") Long tagId, @Param("userId") Long userId);

    @Select("SELECT * FROM tb_marketing_tag_member WHERE merchant_id=#{merchantId} " +
            "AND tag_id=#{tagId} AND user_id=#{userId} FOR UPDATE")
    MarketingTagMember selectScopedForUpdate(@Param("merchantId") Long merchantId,
            @Param("tagId") Long tagId, @Param("userId") Long userId);

    @Select("SELECT * FROM tb_marketing_tag_member WHERE merchant_id = #{merchantId} " +
            "AND tag_id = #{tagId} ORDER BY id DESC")
    List<MarketingTagMember> selectAllScoped(@Param("merchantId") Long merchantId,
            @Param("tagId") Long tagId);

    @Insert("INSERT INTO tb_marketing_tag_member " +
            "(merchant_id,tag_id,user_id,status,expire_time,assigned_by,assigned_time) " +
            "VALUES(#{merchantId},#{tagId},#{userId},'ACTIVE',#{expireTime},#{assignedBy},CURRENT_TIMESTAMP) " +
            "ON DUPLICATE KEY UPDATE status='ACTIVE',expire_time=VALUES(expire_time)," +
            "assigned_by=VALUES(assigned_by),assigned_time=CURRENT_TIMESTAMP")
    int upsertActive(@Param("merchantId") Long merchantId, @Param("tagId") Long tagId,
            @Param("userId") Long userId, @Param("expireTime") LocalDateTime expireTime,
            @Param("assignedBy") Long assignedBy);

    @Update("UPDATE tb_marketing_tag_member SET status='REMOVED' " +
            "WHERE merchant_id=#{merchantId} AND tag_id=#{tagId} AND user_id=#{userId} " +
            "AND status='ACTIVE'")
    int removeScoped(@Param("merchantId") Long merchantId, @Param("tagId") Long tagId,
            @Param("userId") Long userId);
}
