package com.localdeals.merchant.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdminWebSocketTicketResponse {
    private String ticket;
    private long expiresIn;
}
