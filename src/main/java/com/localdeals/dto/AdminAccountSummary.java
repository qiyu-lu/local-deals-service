package com.localdeals.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminAccountSummary {
    private Long id;
    private Long merchantId;
    private String username;
    private String displayName;
    private String scopeType;
    private Integer status;
    private List<String> roles;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
}
