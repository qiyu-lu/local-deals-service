package com.localdeals.dto;

/**
 * Classifies the durable MySQL state for one exact Redis seckill reservation.
 *
 * <p>The persisted identifiers are included for diagnostics. Callers must never treat a
 * different order with the same user/voucher pair, or an order-id collision, as success.</p>
 */
public final class SeckillOrderPersistenceResult {

    public enum Type {
        EXACT_MATCH,
        ABSENT,
        USER_VOUCHER_CONFLICT,
        ORDER_ID_CONFLICT
    }

    private final Type type;
    private final Long persistedOrderId;
    private final Long persistedUserId;
    private final Long persistedVoucherId;

    private SeckillOrderPersistenceResult(Type type, Long persistedOrderId,
                                          Long persistedUserId, Long persistedVoucherId) {
        this.type = type;
        this.persistedOrderId = persistedOrderId;
        this.persistedUserId = persistedUserId;
        this.persistedVoucherId = persistedVoucherId;
    }

    public static SeckillOrderPersistenceResult exact(Long orderId, Long userId, Long voucherId) {
        return new SeckillOrderPersistenceResult(Type.EXACT_MATCH, orderId, userId, voucherId);
    }

    public static SeckillOrderPersistenceResult absent() {
        return new SeckillOrderPersistenceResult(Type.ABSENT, null, null, null);
    }

    public static SeckillOrderPersistenceResult userVoucherConflict(Long orderId, Long userId,
                                                                      Long voucherId) {
        return new SeckillOrderPersistenceResult(
                Type.USER_VOUCHER_CONFLICT, orderId, userId, voucherId);
    }

    public static SeckillOrderPersistenceResult orderIdConflict(Long orderId, Long userId,
                                                                 Long voucherId) {
        return new SeckillOrderPersistenceResult(Type.ORDER_ID_CONFLICT, orderId, userId, voucherId);
    }

    public Type getType() {
        return type;
    }

    public Long getPersistedOrderId() {
        return persistedOrderId;
    }

    public Long getPersistedUserId() {
        return persistedUserId;
    }

    public Long getPersistedVoucherId() {
        return persistedVoucherId;
    }
}
