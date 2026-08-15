package com.localdeals.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_admin_role")
public class AdminRole {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String scopeType;
    private Boolean builtIn;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
