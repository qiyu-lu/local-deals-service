package com.localdeals.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.dto.Result;
import com.localdeals.dto.SeckillOrderStatusDTO;
import com.localdeals.exception.OrderReservationConflictException;
import com.localdeals.exception.StockExhaustedException;
import com.localdeals.entity.VoucherOrder;
import com.localdeals.mapper.VoucherOrderMapper;
import com.localdeals.mq.SeckillOrderMessage;
import com.localdeals.mq.SeckillOrderProducer;
import com.localdeals.service.ISeckillVoucherService;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.service.SeckillOrderStateService;
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
    private SeckillOrderStateService seckillOrderStateService;

    @Resource
    private MeterRegistry meterRegistry;

    private Counter requestAcceptedCounter;
    private Counter requestStockRejectedCounter;
    private Counter requestDuplicateRejectedCounter;
    private Counter requestActivityRejectedCounter;
    private Counter requestUnavailableCounter;
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
        requestActivityRejectedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "rejected_activity").register(meterRegistry);
        requestUnavailableCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "unavailable").register(meterRegistry);
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
                return Result.ok(Long.toString(orderId));
            case 1:
                requestStockRejectedCounter.increment();
                return Result.fail("库存不足");
            case 2:
                requestDuplicateRejectedCounter.increment();
                return Result.fail("您已抢过该优惠券");
            case 3:
                requestActivityRejectedCounter.increment();
                return Result.fail("秒杀活动尚未开始");
            case 4:
                requestActivityRejectedCounter.increment();
                return Result.fail("秒杀活动已结束或暂停");
            case 5:
                requestUnavailableCounter.increment();
                return Result.fail("活动正在初始化，请稍后重试");
            default:
                // The client call can fail after Redis admission. If the exact reservation
                // exists, expose its id so the caller can recover through the status API.
                if (isAcceptedDespiteProducerError(voucherId, userId, orderId)) {
                    requestAcceptedCounter.increment();
                    return Result.ok(Long.toString(orderId));
                }
                requestUnavailableCounter.increment();
                return Result.fail("系统繁忙，请稍后重试");
        }
    }

    private boolean isAcceptedDespiteProducerError(Long voucherId, Long userId, Long orderId) {
        try {
            SeckillOrderStateService.Snapshot state = seckillOrderStateService.find(orderId);
            return state != null && orderId.equals(state.getOrderId()) && userId.equals(state.getUserId()) &&
                    voucherId.equals(state.getVoucherId()) &&
                    (SeckillOrderStateService.STATUS_PROCESSING.equals(state.getStatus()) ||
                            SeckillOrderStateService.STATUS_SUCCESS.equals(state.getStatus()));
        } catch (RuntimeException e) {
            log.warn("Unable to recover ambiguous seckill submission. orderId={}", orderId, e);
            return false;
        }
    }

    @Override
    public Result querySeckillOrderStatus(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        boolean redisUnavailable = false;
        SeckillOrderStateService.Snapshot state = null;
        try {
            state = seckillOrderStateService.find(orderId);
        } catch (RuntimeException e) {
            redisUnavailable = true;
            log.warn("Redis seckill status lookup failed; falling back to DB. orderId={}", orderId, e);
        }
        if (state != null) {
            if (!userId.equals(state.getUserId())) {
                return Result.fail("订单不存在或无权查看");
            }
            if (SeckillOrderStateService.STATUS_PROCESSING.equals(state.getStatus())) {
                VoucherOrder persisted = findOwnedPersistedOrder(orderId, userId);
                if (persisted != null && state.getVoucherId().equals(persisted.getVoucherId())) {
                    repairSuccessStateBestEffort(state);
                    return Result.ok(new SeckillOrderStatusDTO(
                            persisted.getId().toString(), persisted.getVoucherId(),
                            SeckillOrderStateService.STATUS_SUCCESS, null));
                }
            }
            return Result.ok(toStatusDto(state));
        }

        VoucherOrder persisted = findOwnedPersistedOrder(orderId, userId);
        if (persisted != null) {
            return Result.ok(new SeckillOrderStatusDTO(
                    persisted.getId().toString(), persisted.getVoucherId(),
                    SeckillOrderStateService.STATUS_SUCCESS, null));
        }
        return Result.fail(redisUnavailable ? "订单状态暂不可用，请稍后重试" : "订单不存在或状态已过期");
    }

    private VoucherOrder findOwnedPersistedOrder(Long orderId, Long userId) {
        VoucherOrder persisted = getById(orderId);
        return persisted != null && userId.equals(persisted.getUserId()) ? persisted : null;
    }

    private void repairSuccessStateBestEffort(SeckillOrderStateService.Snapshot state) {
        try {
            boolean repaired = seckillOrderStateService.markSuccess(new SeckillOrderMessage(
                    state.getVoucherId(), state.getUserId(), state.getOrderId()));
            if (!repaired) {
                log.warn("DB order exists but Redis PROCESSING state could not be repaired. orderId={}",
                        state.getOrderId());
            }
        } catch (RuntimeException e) {
            // MySQL is authoritative after commit. Return SUCCESS to the owner even when the
            // best-effort Redis repair is temporarily unavailable; a later query/retry can heal it.
            log.warn("Failed to repair stale Redis PROCESSING state. orderId={}", state.getOrderId(), e);
        }
    }

    private SeckillOrderStatusDTO toStatusDto(SeckillOrderStateService.Snapshot state) {
        return new SeckillOrderStatusDTO(
                state.getOrderId().toString(),
                state.getVoucherId(),
                state.getStatus(),
                userFacingReason(state.getReason()));
    }

    private String userFacingReason(String reason) {
        if ("DB_STOCK_EXHAUSTED".equals(reason)) {
            return "数据库库存不足，预占已释放";
        }
        if ("DB_ORDER_CONFLICT".equals(reason)) {
            return "订单状态冲突，预占已释放";
        }
        return reason == null ? null : "订单处理失败，预占已释放";
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        try {
            if (!this.save(voucherOrder)) {
                throw new IllegalStateException("Voucher order insert affected no rows. orderId=" + voucherOrder.getId());
            }
        } catch (DuplicateKeyException e) {
            duplicateOrderCounter.increment();
            VoucherOrder persisted = lambdaQuery()
                    .eq(VoucherOrder::getUserId, voucherOrder.getUserId())
                    .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId())
                    .one();
            if (persisted != null && voucherOrder.getId().equals(persisted.getId())) {
                log.info("Idempotent seckill message replay. orderId={}", voucherOrder.getId());
                return;
            }
            Long persistedOrderId = persisted == null ? null : persisted.getId();
            throw new OrderReservationConflictException(
                    "Redis reservation conflicts with DB order. requestedOrderId=" + voucherOrder.getId() +
                            ", persistedOrderId=" + persistedOrderId +
                            ", userId=" + voucherOrder.getUserId() +
                            ", voucherId=" + voucherOrder.getVoucherId());
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!success) {
            stockRollbackCounter.increment();
            throw new StockExhaustedException("DB stock exhausted. voucherId=" + voucherOrder.getVoucherId());
        }
    }
}
