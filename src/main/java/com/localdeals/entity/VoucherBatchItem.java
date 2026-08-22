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
@TableName("tb_voucher_batch_item")
public class VoucherBatchItem {
    @TableId(value = "id", type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long jobId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    private String status;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long grantId;
    private Integer attempts;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableField(exist = false)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;
}
