package com.localdeals.marketing.dto;

import lombok.Data;

@Data
public class AdminVoucherGrantRequest {
    private Long merchantId;
    private Long userId;
    private Long expectedRuleVersion;
}
