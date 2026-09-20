package com.localdeals.trade.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage {
    private Long voucherId;
    private Long userId;
    private Long orderId;
    /**
     * The request that won this order, so the instance that persists it logs under the same id
     * as the instance that admitted it. Null for a message the reconciler rebuilt from Redis,
     * and for anything written before M8.
     */
    private String traceId;

    public SeckillOrderMessage(Long voucherId, Long userId, Long orderId) {
        this(voucherId, userId, orderId, null);
    }
}
