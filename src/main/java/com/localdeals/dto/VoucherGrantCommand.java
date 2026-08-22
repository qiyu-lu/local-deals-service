package com.localdeals.dto;

import lombok.Data;

@Data
public class VoucherGrantCommand {
    public static final String USER_CLAIM = "USER_CLAIM";
    public static final String ADMIN_GRANT = "ADMIN_GRANT";

    private Long campaignId;
    private Long merchantId;
    private Long userId;
    private Long expectedRuleVersion;
    private Long operatorId;
    private String source;
}
