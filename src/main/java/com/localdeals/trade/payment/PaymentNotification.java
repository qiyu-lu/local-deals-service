package com.localdeals.trade.payment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Channel -> us: the money for {@code payNo} arrived. Signed over every other field. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentNotification {
    private String payNo;
    private String channelTxnNo;
    private Long amount;
    private String sign;

    public Map<String, String> signedFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("payNo", payNo);
        fields.put("channelTxnNo", channelTxnNo);
        fields.put("amount", amount == null ? null : amount.toString());
        return fields;
    }
}
