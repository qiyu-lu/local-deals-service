package com.localdeals.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("refund_record")
public class RefundRecord {
    public static final String TYPE_USER = "USER";
    public static final String TYPE_AUTO = "AUTO";
    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String refundNo;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderNo;
    private String payNo;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    private Long amount;
    private String type;
    private String reason;
    private String status;
    private String channelRefundNo;
    private Integer requestAttempts;
    private LocalDateTime finishedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
