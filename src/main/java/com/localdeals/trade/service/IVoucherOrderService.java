package com.localdeals.trade.service;

import com.localdeals.platform.dto.Result;
import com.localdeals.trade.dto.SeckillOrderPersistenceResult;
import com.localdeals.trade.entity.VoucherOrder;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId, String clientIp);

    Result querySeckillOrderStatus(Long orderId);

    SeckillOrderPersistenceResult classifyPersistence(Long orderId, Long userId, Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * Persists an admitted seckill reservation as a PENDING_PAY {@code trade_order} and takes
     * one unit of DB stock in the same transaction. Replaying the same message is a no-op.
     */
    void createPendingOrder(com.localdeals.trade.mq.SeckillOrderMessage message);
}
