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
@TableName("tb_voucher_grant_notification_outbox")
public class VoucherGrantNotificationOutbox {
    @TableId(value = "id", type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long grantId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    private String eventType;
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime publishedAt;

    @TableField(exist = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long campaignId;
    @TableField(exist = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long voucherId;
}
