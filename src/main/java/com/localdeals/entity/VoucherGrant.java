package com.localdeals.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_voucher_grant")
public class VoucherGrant {
    @TableId(value = "id", type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long campaignId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long voucherId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    private String source;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ruleVersion;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;
    private LocalDateTime grantedAt;

    @TableField(exist = false)
    private String campaignName;
    @TableField(exist = false)
    private String voucherTitle;
    @TableField(exist = false)
    private Long payValue;
    @TableField(exist = false)
    private Long actualValue;
    @TableField(exist = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long shopId;
    @TableField(exist = false)
    private String shopName;
}
