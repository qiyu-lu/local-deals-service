package com.localdeals.merchant.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/** Who did what to which object, when, from where, and whether it worked. */
@Data
@TableName("admin_audit_log")
public class AdminAuditLog {
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";

    @TableId(value = "id", type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long accountId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;
    private String username;
    private String action;
    private String targetType;
    private String targetId;
    private String result;
    private String errorCode;
    private String clientIp;
    private LocalDateTime createTime;
}
