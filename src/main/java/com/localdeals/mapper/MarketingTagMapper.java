package com.localdeals.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.localdeals.entity.MarketingTag;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface MarketingTagMapper extends BaseMapper<MarketingTag> {
    @Select("SELECT * FROM tb_marketing_tag WHERE merchant_id = #{merchantId} ORDER BY id DESC")
    List<MarketingTag> selectAllScoped(@Param("merchantId") Long merchantId);

    @Select("SELECT * FROM tb_marketing_tag WHERE id = #{tagId} " +
            "AND merchant_id = #{merchantId} LIMIT 1")
    MarketingTag selectScoped(@Param("tagId") Long tagId, @Param("merchantId") Long merchantId);

    @Select("SELECT * FROM tb_marketing_tag WHERE id = #{tagId} " +
            "AND merchant_id = #{merchantId} FOR UPDATE")
    MarketingTag selectScopedForUpdate(@Param("tagId") Long tagId,
            @Param("merchantId") Long merchantId);
}
