package com.localdeals.trade.service;

import com.localdeals.trade.entity.OrderEvent;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.exception.OrderIdConflictException;
import com.localdeals.trade.exception.OrderReservationConflictException;
import com.localdeals.trade.exception.StockExhaustedException;
import com.localdeals.trade.mapper.TradeOrderMapper;
import com.localdeals.trade.mq.SeckillOrderMessage;
import com.localdeals.trade.testsupport.TradeFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * trade_order persistence from the seckill consumer, and plan race 5: a closed order must not
 * block the same user from buying the voucher again (generated-column unique key).
 */
@SpringBootTest
@ActiveProfiles("test")
class TradeOrderPersistenceIT {

    private static final long BASE = 9_201_000L;
    private static final long USER = 9_201_001L;
    /** Same gene as USER, so an order number of USER can legally be offered with this one. */
    private static final long SAME_SHARD_USER = USER + 1024;

    @Autowired
    private IVoucherOrderService orderService;
    @Autowired
    private TradeOrderMapper orderMapper;
    @Autowired
    private OrderStateMachine stateMachine;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private TradeFixture fixture;

    /** A routable order number of USER; see TradeFixture.orderNo. */
    private static long order(long seed) {
        return TradeFixture.orderNo(BASE + seed, USER);
    }

    @BeforeEach
    void setUp() {
        fixture = new TradeFixture(jdbc, BASE).create(5);
    }

    @AfterEach
    void tearDown() {
        fixture.delete();
    }

    @Test
    void pendingOrderSnapshotsPriceShopAndMerchantAndTakesOneUnitOfStock() {
        orderService.createPendingOrder(message(order(11), USER));

        TradeOrder order = orderMapper.selectById(order(11));
        assertThat(order.getStatus().name()).isEqualTo("PENDING_PAY");
        assertThat(order.getAmount()).isEqualTo(TradeFixture.PAY_VALUE);
        assertThat(order.getShopId()).isEqualTo(fixture.shopId);
        assertThat(order.getMerchantId()).isEqualTo(fixture.merchantId);
        Long secondsToExpiry = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, NOW(3), expire_at) FROM trade_order WHERE order_no = ?",
                Long.class, order(11));
        assertThat(secondsToExpiry).isBetween(14 * 60L, 15 * 60L);
        assertThat(fixture.dbStock()).isEqualTo(4);
        assertThat(stateLog(order(11))).containsExactly("null->PENDING_PAY:CREATE");
    }

    @Test
    void exhaustedStockRollsTheOrderBack() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock = 0 WHERE voucher_id = ?", fixture.voucherId);

        assertThatThrownBy(() -> orderService.createPendingOrder(message(order(12), USER)))
                .isInstanceOf(StockExhaustedException.class);

        assertThat(orderMapper.selectById(order(12))).isNull();
        assertThat(stateLog(order(12))).isEmpty();
    }

    @Test
    void replayingTheSameOrderIsIdempotent() {
        orderService.createPendingOrder(message(order(13), USER));
        orderService.createPendingOrder(message(order(13), USER));

        assertThat(fixture.dbStock()).isEqualTo(4);
        assertThat(stateLog(order(13))).hasSize(1);
    }

    @Test
    void aSecondActiveOrderForTheSameUserAndVoucherIsAReservationConflict() {
        orderService.createPendingOrder(message(order(14), USER));

        assertThatThrownBy(() -> orderService.createPendingOrder(message(order(15), USER)))
                .isInstanceOf(OrderReservationConflictException.class);
        assertThat(fixture.dbStock()).isEqualTo(4);
    }

    @Test
    void anOrderNoOwnedByAnotherUserIsAnOrderIdConflict() {
        orderService.createPendingOrder(message(order(16), USER));

        assertThatThrownBy(() -> orderService.createPendingOrder(message(order(16), SAME_SHARD_USER)))
                .isInstanceOf(OrderIdConflictException.class);
    }

    @Test
    void closeIsRefusedBeforeTheDeadlineAndRequiresATransaction() {
        orderService.createPendingOrder(message(order(17), USER));

        assertThat(close(order(17))).isFalse();
        assertThatThrownBy(() -> stateMachine.fire(order(17), OrderEvent.CLOSE, "SYSTEM"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(fixture.orderStatus(order(17))).isEqualTo("PENDING_PAY");
    }

    /** Plan race 5, database half: the unique key covers active orders only. */
    @Test
    void aClosedOrderNoLongerBlocksTheSameUserFromBuyingAgain() {
        orderService.createPendingOrder(message(order(18), USER));
        fixture.expire(order(18));
        assertThat(close(order(18))).isTrue();

        orderService.createPendingOrder(message(order(19), USER));

        assertThat(fixture.orderStatus(order(18))).isEqualTo("CLOSED");
        assertThat(fixture.orderStatus(order(19))).isEqualTo("PENDING_PAY");
        assertThat(stateLog(order(18))).containsExactly("null->PENDING_PAY:CREATE", "PENDING_PAY->CLOSED:CLOSE");
        assertThatThrownBy(() -> orderService.createPendingOrder(message(order(20), USER)))
                .isInstanceOf(OrderReservationConflictException.class);
    }

    @Test
    void concurrentOrdersForTheSameUserAndVoucherLetExactlyOneWin() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                long orderNo = order(100 + i);
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        orderService.createPendingOrder(message(orderNo, USER));
                        return true;
                    } catch (OrderReservationConflictException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(fixture.dbStock()).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean close(long orderNo) {
        return Boolean.TRUE.equals(tx.execute(status -> stateMachine.fire(orderNo, OrderEvent.CLOSE, "SYSTEM")));
    }

    private SeckillOrderMessage message(long orderNo, long userId) {
        return new SeckillOrderMessage(fixture.voucherId, userId, orderNo);
    }

    private List<String> stateLog(long orderNo) {
        try {
            return jdbc.queryForList("SELECT CONCAT(IFNULL(from_status, 'null'), '->', to_status, ':', event) " +
                    "FROM order_state_log WHERE order_no = ? ORDER BY id", String.class, orderNo);
        } catch (org.springframework.jdbc.BadSqlGrammarException tableNotMigratedYet) {
            return List.of();
        }
    }
}
