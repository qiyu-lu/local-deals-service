package com.localdeals.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("payment_record")
public class PaymentRecord {
    public static final String WAITING = "WAITING";
    public static final String SUCCESS = "SUCCESS";
    public static final String ABNORMAL = "ABNORMAL";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String payNo;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderNo;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    private Long amount;
    private String channel;
    private String status;
    private String channelTxnNo;
    private Long paidAmount;
    private LocalDateTime paidAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
