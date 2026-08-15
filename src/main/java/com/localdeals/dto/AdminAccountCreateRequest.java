package com.localdeals.dto;

import lombok.Data;

@Data
public class AdminAccountCreateRequest {
    private Long merchantId;
    private String username;
    private String password;
    private String displayName;
}
