package com.localdeals.trade.service;

import com.localdeals.platform.dto.Result;
import com.localdeals.trade.dto.SeckillOrderPersistenceResult;
import com.localdeals.trade.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

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
}
