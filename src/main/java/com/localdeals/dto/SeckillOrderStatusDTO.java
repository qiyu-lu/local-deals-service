package com.localdeals.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** User-scoped view of an asynchronous seckill order. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderStatusDTO {
    /** Kept as a decimal string because Redis-generated ids exceed JavaScript's safe integer range. */
    private String orderId;
    private Long voucherId;
    private String status;
    private String reason;
}
