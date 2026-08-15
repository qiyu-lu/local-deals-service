package com.localdeals.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdminWebSocketTicketResponse {
    private String ticket;
    private long expiresIn;
}
