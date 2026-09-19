package com.localdeals.trade.payment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Channel -> us: the refund {@code refundNo} was paid back. Signed over every other field. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundNotification {
    private String refundNo;
    private String channelRefundNo;
    private Long amount;
    private String sign;

    public Map<String, String> signedFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("refundNo", refundNo);
        fields.put("channelRefundNo", channelRefundNo);
        fields.put("amount", amount == null ? null : amount.toString());
        return fields;
    }
}
