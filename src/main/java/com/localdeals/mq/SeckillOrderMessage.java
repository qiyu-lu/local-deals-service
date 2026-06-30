package com.localdeals.mq;

import com.localdeals.entity.VoucherOrder;
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

    public VoucherOrder toVoucherOrder() {
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setVoucherId(voucherId);
        order.setUserId(userId);
        return order;
    }
}
