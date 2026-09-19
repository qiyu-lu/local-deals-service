package com.localdeals.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/** A coupon a user holds: bought in a seckill or granted by a marketing campaign. */
@Data
@TableName("user_coupon")
public class UserCoupon {
    @TableId(value = "id", type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String couponNo;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long voucherId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long shopId;
    private CouponSource source;
    private String sourceRef;
    private CouponStatus status;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private String verifyCode;
    private LocalDateTime usedAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long verifiedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
