package com.localdeals.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_admin_account")
public class AdminAccount {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long merchantId;
    private String username;
    private String passwordHash;
    private String displayName;
    private String scopeType;
    private Integer status;
    private Integer authVersion;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
