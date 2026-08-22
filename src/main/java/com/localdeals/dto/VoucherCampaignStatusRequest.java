package com.localdeals.dto;

import lombok.Data;

@Data
public class VoucherCampaignStatusRequest {
    private Long merchantId;
    private String expectedStatus;
    private Long expectedRuleVersion;
    private String status;
}
