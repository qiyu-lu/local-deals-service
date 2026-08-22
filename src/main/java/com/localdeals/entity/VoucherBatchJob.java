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
@TableName("tb_voucher_batch_job")
public class VoucherBatchJob {
    @TableId(value = "id", type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long campaignId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;
    private String requestId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long capturedRuleVersion;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long targetTagId;
    private String status;
    private Integer targetCount;
    private LocalDateTime createTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private Integer grantedItemCount;
    @TableField(exist = false)
    private Integer idempotentItemCount;
    @TableField(exist = false)
    private Integer skippedItemCount;
    @TableField(exist = false)
    private Integer failedItemCount;
    @TableField(exist = false)
    private Integer pendingItemCount;
}
