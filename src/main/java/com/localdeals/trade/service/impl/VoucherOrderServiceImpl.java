package com.localdeals.trade.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.localdeals.platform.dto.Result;
import com.localdeals.trade.dto.SeckillOrderPersistenceResult;
import com.localdeals.trade.dto.SeckillOrderStatusDTO;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.trade.exception.OrderReservationConflictException;
import com.localdeals.trade.exception.OrderIdConflictException;
import com.localdeals.trade.exception.StockExhaustedException;
import com.localdeals.trade.config.OrderProperties;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.TradeOrderMapper;
import com.localdeals.trade.mq.SeckillOrderMessage;
import com.localdeals.trade.mq.SeckillOrderProducer;
import com.localdeals.trade.service.ISeckillVoucherService;
import com.localdeals.trade.service.IVoucherOrderService;
import com.localdeals.trade.service.OrderStateMachine;
import com.localdeals.trade.service.SeckillOrderStateService;
import com.localdeals.trade.service.SeckillAdmissionService;
import com.localdeals.trade.service.SeckillBucketRouter;
import com.localdeals.trade.service.SeckillLocalRateLimiter;
import com.localdeals.trade.service.SeckillSoldOutRegistry;
import com.localdeals.platform.observability.LocalDealsMetrics;
import com.localdeals.trade.utils.SnowflakeOrderIdGenerator;
import com.localdeals.platform.utils.UserHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import static com.localdeals.platform.observability.LocalDealsMetrics.TrafficReason.ACTIVITY;
import static com.localdeals.platform.observability.LocalDealsMetrics.TrafficReason.IP;
import static com.localdeals.platform.observability.LocalDealsMetrics.TrafficReason.REDIS;
import static com.localdeals.platform.observability.LocalDealsMetrics.TrafficReason.USER;
import static com.localdeals.platform.observability.LocalDealsMetrics.TrafficResource.SECKILL;
import static com.localdeals.platform.observability.LocalDealsMetrics.TrafficResult.REJECTED;
import static com.localdeals.platform.observability.LocalDealsMetrics.TrafficResult.UNAVAILABLE;

/**
 * Voucher seckill service.
 *
 * <p>The HTTP thread admits with one Redis round trip ({@link SeckillAdmissionService}); only an
 * admitted order is published ({@link SeckillOrderProducer}). MySQL persistence is completed
 * asynchronously by {@link com.localdeals.trade.mq.SeckillOrderConsumer}.</p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<TradeOrderMapper, TradeOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SnowflakeOrderIdGenerator orderIdGenerator;

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private SeckillAdmissionService seckillAdmissionService;

    @Resource
    private LocalDealsMetrics localDealsMetrics;

    @Resource
    private SeckillSoldOutRegistry seckillSoldOutRegistry;

    @Resource
    private SeckillLocalRateLimiter seckillLocalRateLimiter;

    @Resource
    private SeckillBucketRouter seckillBucketRouter;

    @Resource
    private SeckillOrderStateService seckillOrderStateService;


    @Resource
    private OrderStateMachine orderStateMachine;

    @Resource
    private OrderProperties orderProperties;

    @Resource
    private MeterRegistry meterRegistry;

    private Counter requestAcceptedCounter;
    private Counter requestRateRejectedCounter;
    private Counter requestBusyRejectedCounter;
    private Counter requestStockRejectedCounter;
    private Counter requestDuplicateRejectedCounter;
    private Counter requestActivityRejectedCounter;
    private Counter requestUnavailableCounter;
    private Counter duplicateOrderCounter;
    private Counter stockRollbackCounter;
    private Counter publishFailureCounter;

    @PostConstruct
    private void registerMetrics() {
        requestAcceptedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "accepted").register(meterRegistry);
        requestRateRejectedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "rejected_rate").register(meterRegistry);
        requestBusyRejectedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "rejected_busy").register(meterRegistry);
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
        publishFailureCounter = Counter.builder("local_deals.seckill.publish")
                .tag("result", "failure").register(meterRegistry);
    }

    @Override
    public Result seckillVoucher(Long voucherId, String clientIp) {
        Long userId = UserHolder.getUser().getId();
        // The buyer's stock bucket decides every Redis key and both local funnel layers.
        int bucket = seckillBucketRouter.bucketOfUser(userId);
        // L2: more requests than the remaining stock can satisfy never cost a Redis round trip.
        if (!seckillLocalRateLimiter.tryAcquire(voucherId, bucket)) {
            requestBusyRejectedCounter.increment();
            localDealsMetrics.recordTraffic(SECKILL, REJECTED, ACTIVITY);
            throw new ApiStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    ApiErrorCodes.SECKILL_BUSY, "排队人数过多，请稍后重试");
        }
        long orderId = allocateOrderId(voucherId, userId);
        SeckillAdmissionService.Admission admission = admit(voucherId, userId, orderId, clientIp);
        if (admission.code() == SeckillAdmissionService.ORDER_ID_IN_USE) {
            // Only possible while two instances briefly share a worker id; the script wrote nothing.
            log.warn("Seckill order id already in use; retrying with a new one. orderId={}", orderId);
            orderId = allocateOrderId(voucherId, userId);
            admission = admit(voucherId, userId, orderId, clientIp);
        }
        seckillLocalRateLimiter.observe(voucherId, bucket, admission.remainingStock());
        updateSoldOutFlag(voucherId, bucket, admission);

        switch (admission.code()) {
            case SeckillAdmissionService.ACCEPTED:
                requestAcceptedCounter.increment();
                publishBestEffort(new SeckillOrderMessage(voucherId, userId, orderId));
                return Result.ok(Long.toString(orderId));
            case SeckillAdmissionService.OUT_OF_STOCK:
                requestStockRejectedCounter.increment();
                return Result.fail(ApiErrorCodes.SECKILL_OUT_OF_STOCK, "库存不足");
            case SeckillAdmissionService.DUPLICATE:
                requestDuplicateRejectedCounter.increment();
                return Result.fail(ApiErrorCodes.SECKILL_DUPLICATE, "您已抢过该优惠券");
            case SeckillAdmissionService.NOT_STARTED:
                requestActivityRejectedCounter.increment();
                return Result.fail(ApiErrorCodes.SECKILL_NOT_STARTED, "秒杀活动尚未开始");
            case SeckillAdmissionService.ENDED:
                requestActivityRejectedCounter.increment();
                return Result.fail(ApiErrorCodes.SECKILL_ENDED, "秒杀活动已结束或暂停");
            case SeckillAdmissionService.USER_RATE_LIMITED:
            case SeckillAdmissionService.IP_RATE_LIMITED:
                requestRateRejectedCounter.increment();
                localDealsMetrics.recordTraffic(SECKILL, REJECTED,
                        admission.code() == SeckillAdmissionService.USER_RATE_LIMITED ? USER : IP);
                throw new ApiStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        ApiErrorCodes.SECKILL_RATE_LIMITED, "请求过于频繁，请稍后重试");
            case SeckillAdmissionService.META_NOT_READY:
                requestUnavailableCounter.increment();
                throw new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        ApiErrorCodes.SECKILL_STATE_UNAVAILABLE,
                        "活动正在初始化，请稍后重试");
            default:
                requestUnavailableCounter.increment();
                log.error("Unexpected seckill admission result. voucherId={} code={}",
                        voucherId, admission.code());
                throw submitUnavailable();
        }
    }

    /** L1 bookkeeping: Redis just told us whether this voucher still has stock. */
    private void updateSoldOutFlag(Long voucherId, int bucket, SeckillAdmissionService.Admission admission) {
        if (admission.code() == SeckillAdmissionService.OUT_OF_STOCK
                || (admission.code() == SeckillAdmissionService.ACCEPTED && admission.remainingStock() == 0)) {
            // Only this bucket is empty; the other buyers of the voucher still reach Redis.
            seckillSoldOutRegistry.markSoldOut(voucherId, bucket);
        } else if (admission.code() == SeckillAdmissionService.ACCEPTED
                && seckillSoldOutRegistry.isSoldOut(voucherId, bucket)) {
            // This request was the probe of a stale flag, and there was stock after all.
            seckillSoldOutRegistry.clear(voucherId, bucket);
        }
    }

    private long allocateOrderId(Long voucherId, Long userId) {
        try {
            return orderIdGenerator.nextId(userId);
        } catch (RuntimeException e) {
            requestUnavailableCounter.increment();
            log.warn("Unable to allocate seckill order id. voucherId={}, userId={}",
                    voucherId, userId, e);
            throw submitUnavailable();
        }
    }

    private SeckillAdmissionService.Admission admit(Long voucherId, Long userId, long orderId, String clientIp) {
        try {
            return seckillAdmissionService.admit(voucherId, userId, orderId, clientIp);
        } catch (RuntimeException e) {
            // The script is atomic but its reply can be lost after it ran. If the exact
            // reservation exists, the request was admitted.
            if (isAcceptedDespiteAdmissionError(voucherId, userId, orderId)) {
                log.warn("Seckill admission reply lost after the reservation was written. orderId={}", orderId, e);
                return new SeckillAdmissionService.Admission(SeckillAdmissionService.ACCEPTED, -1L);
            }
            localDealsMetrics.recordTraffic(SECKILL, UNAVAILABLE, REDIS);
            requestUnavailableCounter.increment();
            log.warn("Seckill admission unavailable. voucherId={}, userId={}", voucherId, userId, e);
            throw submitUnavailable();
        }
    }

    /**
     * The reservation is already durable in Redis. If the message is lost here the order stays
     * PROCESSING and the reconciler repairs it, so the caller still gets its order id.
     */
    private void publishBestEffort(SeckillOrderMessage message) {
        try {
            seckillOrderProducer.publish(message);
        } catch (RuntimeException e) {
            publishFailureCounter.increment();
            log.warn("Seckill order message not published; left to the reconciler. orderId={}",
                    message.getOrderId(), e);
        }
    }

    private static ApiStatusException submitUnavailable() {
        return new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCodes.SECKILL_SUBMIT_UNAVAILABLE, "系统繁忙，请稍后重试");
    }

    private boolean isAcceptedDespiteAdmissionError(Long voucherId, Long userId, Long orderId) {
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
                TradeOrder persisted = findOwnedPersistedOrder(orderId, userId);
                if (persisted != null && state.getVoucherId().equals(persisted.getVoucherId())) {
                    repairSuccessStateBestEffort(state);
                    return Result.ok(persistedStatus(persisted));
                }
            }
            if (SeckillOrderStateService.STATUS_SUCCESS.equals(state.getStatus())) {
                TradeOrder persisted = findOwnedPersistedOrder(orderId, userId);
                if (persisted != null) {
                    return Result.ok(persistedStatus(persisted));
                }
            }
            return Result.ok(toStatusDto(state));
        }

        TradeOrder persisted = findOwnedPersistedOrder(orderId, userId);
        if (persisted != null) {
            return Result.ok(persistedStatus(persisted));
        }
        return Result.fail(redisUnavailable ? "订单状态暂不可用，请稍后重试" : "订单不存在或状态已过期");
    }

    private TradeOrder findOwnedPersistedOrder(Long orderId, Long userId) {
        TradeOrder persisted = getById(orderId);
        return persisted != null && userId.equals(persisted.getUserId()) ? persisted : null;
    }

    /** Admission succeeded and the order is durable; orderStatus tells the client what to do next. */
    private static SeckillOrderStatusDTO persistedStatus(TradeOrder order) {
        return new SeckillOrderStatusDTO(order.getOrderNo().toString(), order.getVoucherId(),
                SeckillOrderStateService.STATUS_SUCCESS, null, order.getStatus().name());
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
                userFacingReason(state.getReason()),
                null);
    }

    private String userFacingReason(String reason) {
        if ("DB_STOCK_EXHAUSTED".equals(reason)) {
            return "数据库库存不足，预占已释放";
        }
        if ("DB_ORDER_CONFLICT".equals(reason)) {
            return "订单状态冲突，预占已释放";
        }
        if ("PROCESSING_TIMEOUT".equals(reason)) {
            return "订单处理超时，预占已释放";
        }
        return reason == null ? null : "订单处理失败，预占已释放";
    }

    /**
     * Reads the writer database and classifies whether an exact Redis reservation has already
     * been durably persisted. Datasource routing for this method must never select a read replica.
     * Database failures deliberately propagate to the reconciler: an unavailable database must
     * never be interpreted as an absent order.
     */
    @Override
    @Transactional(readOnly = true)
    public SeckillOrderPersistenceResult classifyPersistence(Long orderId, Long userId, Long voucherId) {
        if (orderId == null || userId == null || voucherId == null) {
            throw new IllegalArgumentException("Seckill order ownership identifiers are required");
        }

        TradeOrder byId = getById(orderId);
        if (byId != null) {
            if (userId.equals(byId.getUserId()) && voucherId.equals(byId.getVoucherId())) {
                return SeckillOrderPersistenceResult.exact(
                        byId.getOrderNo(), byId.getUserId(), byId.getVoucherId());
            }
            return SeckillOrderPersistenceResult.orderIdConflict(
                    byId.getOrderNo(), byId.getUserId(), byId.getVoucherId());
        }

        // Only an active order competes for the purchase limit; CLOSED/REFUNDED ones do not.
        TradeOrder active = getBaseMapper().selectActive(userId, voucherId);
        if (active != null) {
            return SeckillOrderPersistenceResult.userVoucherConflict(
                    active.getOrderNo(), active.getUserId(), active.getVoucherId());
        }
        return SeckillOrderPersistenceResult.absent();
    }

    @Override
    @Transactional
    public void createPendingOrder(SeckillOrderMessage message) {
        long orderNo = message.getOrderId();
        long userId = message.getUserId();
        long voucherId = message.getVoucherId();
        try {
            if (getBaseMapper().insertPendingFromVoucher(orderNo, userId, voucherId,
                    orderProperties.getPayTimeout().getSeconds()) != 1) {
                throw new IllegalStateException("Voucher or its shop is missing. voucherId=" + voucherId);
            }
        } catch (DuplicateKeyException e) {
            duplicateOrderCounter.increment();
            TradeOrder persistedById = getById(orderNo);
            if (persistedById != null) {
                if (persistedById.getUserId() == userId && persistedById.getVoucherId() == voucherId) {
                    log.info("Idempotent seckill message replay. orderId={}", orderNo);
                    return;
                }
                throw new OrderIdConflictException(
                        "Seckill order id belongs to another DB order. requestedOrderId=" + orderNo +
                                ", persistedUserId=" + persistedById.getUserId() +
                                ", persistedVoucherId=" + persistedById.getVoucherId());
            }
            TradeOrder active = getBaseMapper().selectActive(userId, voucherId);
            throw new OrderReservationConflictException(
                    "Redis reservation conflicts with an active DB order. requestedOrderId=" + orderNo +
                            ", persistedOrderId=" + (active == null ? null : active.getOrderNo()) +
                            ", userId=" + userId + ", voucherId=" + voucherId);
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            stockRollbackCounter.increment();
            throw new StockExhaustedException("DB stock exhausted. voucherId=" + voucherId);
        }
        orderStateMachine.recordCreated(orderNo, "SYSTEM");
    }
}
