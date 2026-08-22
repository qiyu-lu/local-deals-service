package com.localdeals.dto;

import lombok.Data;

@Data
public class VoucherBatchJobCreateRequest {
    private Long merchantId;
    private String requestId;
    private Long expectedRuleVersion;
}
