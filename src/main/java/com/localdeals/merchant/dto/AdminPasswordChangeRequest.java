package com.localdeals.merchant.dto;

import lombok.Data;

@Data
public class AdminPasswordChangeRequest {
    private String currentPassword;
    private String newPassword;
}
