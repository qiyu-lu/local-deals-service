package com.localdeals.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.dto.Result;
import com.localdeals.entity.VoucherOrder;
import com.localdeals.mapper.VoucherOrderMapper;
import com.localdeals.mq.SeckillOrderProducer;
import com.localdeals.service.ISeckillVoucherService;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.utils.RedisIdWorker;
import com.localdeals.utils.UserHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * Voucher seckill service.
 *
 * <p>The HTTP thread performs Redis Lua admission control synchronously via a RocketMQ
 * transaction message ({@link SeckillOrderProducer}). MySQL persistence is completed
 * asynchronously by {@link com.localdeals.mq.SeckillOrderConsumer}.</p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private MeterRegistry meterRegistry;

    private Counter requestAcceptedCounter;
    private Counter requestStockRejectedCounter;
    private Counter requestDuplicateRejectedCounter;
    private Counter duplicateOrderCounter;
    private Counter stockRollbackCounter;

    @PostConstruct
    private void registerMetrics() {
        requestAcceptedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "accepted").register(meterRegistry);
        requestStockRejectedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "rejected_stock").register(meterRegistry);
        requestDuplicateRejectedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "rejected_duplicate").register(meterRegistry);
        duplicateOrderCounter = Counter.builder("local_deals.seckill.db.orders")
                .tag("result", "duplicate").register(meterRegistry);
        stockRollbackCounter = Counter.builder("local_deals.seckill.db.orders")
                .tag("result", "stock_rollback").register(meterRegistry);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        long orderId = redisIdWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();

        int luaResult = seckillOrderProducer.sendSeckillTransaction(voucherId, userId, orderId);

        switch (luaResult) {
            case 0:
                requestAcceptedCounter.increment();
                return Result.ok(orderId);
            case 1:
                requestStockRejectedCounter.increment();
                return Result.fail("库存不足");
            default:
                requestDuplicateRejectedCounter.increment();
                return Result.fail("您已抢过该优惠券");
        }
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        try {
            this.save(voucherOrder);
        } catch (DuplicateKeyException e) {
            duplicateOrderCounter.increment();
            log.warn("Duplicate voucher order ignored. userId={}, voucherId={}, orderId={}",
                    voucherOrder.getUserId(), voucherOrder.getVoucherId(), voucherOrder.getId());
            return;
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!success) {
            stockRollbackCounter.increment();
            throw new IllegalStateException("DB stock exhausted. voucherId=" + voucherOrder.getVoucherId());
        }
    }
}
