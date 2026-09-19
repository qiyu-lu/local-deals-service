package com.localdeals.merchant.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdminLoginResponse {
    private String token;
    private long expiresIn;
    private AdminPrincipal principal;
}
