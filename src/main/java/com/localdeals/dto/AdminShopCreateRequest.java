package com.localdeals.dto;

import lombok.Data;

@Data
public class AdminShopCreateRequest {
    private Long merchantId;
    private String name;
    private Long typeId;
    private String images;
    private String area;
    private String address;
    private Double x;
    private Double y;
    private Long avgPrice;
    private String openHours;
}
