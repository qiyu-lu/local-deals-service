package com.localdeals.marketing.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MarketingTagMemberRequest {
    private Long merchantId;
    private LocalDateTime expireTime;
}
