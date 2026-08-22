package com.localdeals.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_marketing_tag_member")
public class MarketingTagMember {
    @TableId(value = "id", type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tagId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    private String status;
    private LocalDateTime expireTime;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long assignedBy;
    private LocalDateTime assignedTime;
    private LocalDateTime updateTime;
}
