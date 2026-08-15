package com.localdeals.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.localdeals.entity.VoucherOrder;
import com.localdeals.exception.OrderReservationConflictException;
import com.localdeals.exception.StockExhaustedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-layer reliability of {@link VoucherOrderServiceImpl#createVoucherOrder}:
 * <ul>
 *   <li>Stock exhaustion → StockExhaustedException AND the order insert is rolled back
 *       by {@code @Transactional} (no orphan order row survives).</li>
 *   <li>Same-order replay is idempotent, while a different Redis order id for an existing
 *       (user, voucher) purchase is a permanent reservation conflict.</li>
 * </ul>
 * Uses seckill voucher id=10 whose DB stock is kept at 0 for this test.
 */
@SpringBootTest
@ActiveProfiles("test")
class VoucherOrderReliabilityIT {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    private static final Long VOUCHER_ID = 10L;
    private static final Long USER_ID = 999001L;
    private static final Long ORDER_ID = 999000001L;
    private static final Long DUP_ORDER_ID_1 = 999000002L;
    private static final Long DUP_ORDER_ID_2 = 999000003L;
    private Integer originalStock;

    @BeforeEach
    void setup() {
        originalStock = seckillVoucherService.query()
                .eq("voucher_id", VOUCHER_ID).one().getStock();
        // Force DB stock to 0 so the stock-guard update matches 0 rows.
        seckillVoucherService.update().setSql("stock = 0").eq("voucher_id", VOUCHER_ID).update();
        clearTestOrders();
    }

    @AfterEach
    void cleanup() {
        clearTestOrders();
        if (originalStock != null) {
            seckillVoucherService.update()
                    .set("stock", originalStock)
                    .eq("voucher_id", VOUCHER_ID)
                    .update();
        }
    }

    private void clearTestOrders() {
        voucherOrderService.remove(new QueryWrapper<VoucherOrder>()
                .eq("user_id", USER_ID).eq("voucher_id", VOUCHER_ID));
    }

    @Test
    void createVoucherOrder_stockExhausted_throwsAndRollsBackInsert() {
        VoucherOrder order = new VoucherOrder();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setVoucherId(VOUCHER_ID);

        // Stock is 0 → the "UPDATE ... WHERE stock > 0" matches nothing → StockExhaustedException.
        assertThatThrownBy(() -> voucherOrderService.createVoucherOrder(order))
                .isInstanceOf(StockExhaustedException.class);

        // @Transactional must have rolled back the earlier save() — no orphan order row.
        assertThat(voucherOrderService.getById(ORDER_ID)).isNull();
    }

    @Test
    void createVoucherOrder_replayIsIdempotentButDifferentOrderIdIsRejected() {
        // Seed one order for (USER_ID, VOUCHER_ID).
        VoucherOrder first = new VoucherOrder();
        first.setId(DUP_ORDER_ID_1);
        first.setUserId(USER_ID);
        first.setVoucherId(VOUCHER_ID);
        voucherOrderService.save(first);

        long stockBefore = seckillVoucherService.query()
                .eq("voucher_id", VOUCHER_ID).one().getStock();

        // Replaying the exact same message is idempotent and does not decrement stock again.
        assertThatCode(() -> voucherOrderService.createVoucherOrder(first))
                .doesNotThrowAnyException();

        // A different Redis order id for the same purchase is not the same operation.
        VoucherOrder duplicate = new VoucherOrder();
        duplicate.setId(DUP_ORDER_ID_2);
        duplicate.setUserId(USER_ID);
        duplicate.setVoucherId(VOUCHER_ID);

        assertThatThrownBy(() -> voucherOrderService.createVoucherOrder(duplicate))
                .isInstanceOf(OrderReservationConflictException.class)
                .hasMessageContaining("persistedOrderId=" + DUP_ORDER_ID_1);

        // The duplicate must not have been persisted.
        assertThat(voucherOrderService.getById(DUP_ORDER_ID_2)).isNull();

        // Stock must be untouched (the decrement is skipped on the duplicate path).
        long stockAfter = seckillVoucherService.query()
                .eq("voucher_id", VOUCHER_ID).one().getStock();
        assertThat(stockAfter).isEqualTo(stockBefore);
    }
}
