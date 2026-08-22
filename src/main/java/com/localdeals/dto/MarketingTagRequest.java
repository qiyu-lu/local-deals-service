package com.localdeals.dto;

import lombok.Data;

@Data
public class MarketingTagRequest {
    private Long merchantId;
    private String code;
    private String name;
}
