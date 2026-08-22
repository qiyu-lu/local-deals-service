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
@TableName("tb_voucher_campaign")
public class VoucherCampaign {
    @TableId(value = "id", type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long voucherId;
    private String name;
    private String grantMode;
    private String eligibilityType;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long requiredTagId;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private Integer quotaTotal;
    private Integer grantedCount;
    private String status;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ruleVersion;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private Integer voucherType;
    @TableField(exist = false)
    private Integer voucherStatus;
    @TableField(exist = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long voucherMerchantId;
    @TableField(exist = false)
    private String voucherTitle;
    @TableField(exist = false)
    private Boolean alreadyGranted;
    @TableField(exist = false)
    private Boolean tagEligible;
    @TableField(exist = false)
    private String claimState;
    @TableField(exist = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long expectedRuleVersion;
    @TableField(exist = false)
    private String expectedStatus;
}
