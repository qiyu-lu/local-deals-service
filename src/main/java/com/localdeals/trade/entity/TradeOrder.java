package com.localdeals.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One purchase of a voucher. Rows are inserted by the seckill consumer in {@code PENDING_PAY};
 * {@code status} has no setter on purpose: only {@code OrderStateMachine} changes it, with a
 * conditional UPDATE.
 */
@Data
@TableName("trade_order")
public class TradeOrder {
    @TableId(value = "order_no", type = IdType.INPUT)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderNo;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long voucherId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long shopId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;
    /** Amount due in cents, copied from {@code tb_voucher.pay_value} when the order is created. */
    private Long amount;
    @Setter(AccessLevel.NONE)
    private OrderStatus status;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private LocalDateTime closedAt;
    private LocalDateTime usedAt;
    private LocalDateTime refundedAt;
    /** 1 while the Redis reservation of a closed/refunded order still has to be released. */
    private Integer releasePending;
    private Integer version;
    /** Generated column: 1 for orders that block another purchase, NULL for CLOSED/REFUNDED. */
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private Integer activeFlag;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
