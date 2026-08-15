package com.localdeals.dto;

import lombok.Data;

@Data
public class AdminPasswordChangeRequest {
    private String currentPassword;
    private String newPassword;
}
