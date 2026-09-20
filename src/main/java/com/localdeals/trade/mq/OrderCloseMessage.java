package com.localdeals.trade.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCloseMessage {
    private Long orderNo;
    /** The request that bought the order, carried so the close an hour later joins it. */
    private String traceId;

    public OrderCloseMessage(Long orderNo) {
        this(orderNo, null);
    }
}
