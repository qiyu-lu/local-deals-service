package com.localdeals.merchant.dto;

import lombok.Data;

@Data
public class AdminMerchantCreateRequest {
    private String code;
    private String name;
    private String ownerUsername;
    private String ownerPassword;
    private String ownerDisplayName;
}
