package com.localdeals.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VoucherCampaignRequest {
    private Long merchantId;
    private Long voucherId;
    private String expectedStatus;
    private Long expectedRuleVersion;
    private String name;
    private String grantMode;
    private String eligibilityType;
    private Long requiredTagId;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private Integer quotaTotal;
}
