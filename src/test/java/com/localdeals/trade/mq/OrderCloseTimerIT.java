package com.localdeals.trade.mq;

import com.localdeals.trade.testsupport.TradeFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The primary close path on a real RocketMQ 5 broker: the timer message is delivered at the
 * order's deadline and the listener closes the order. Needs scripts/stack.sh (not run in CI).
 */
@SpringBootTest(properties = {
        "rocketmq.consumer.listeners[order-close-consumer-group][order-close-topic]=true",
        "local-deals.order.pay-timeout=3s"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderCloseTimerIT {

    private static final long BASE = 9_203_000L;

    @Autowired
    private com.localdeals.trade.service.IVoucherOrderService orderService;
    @Autowired
    private OrderTimeoutScheduler scheduler;
    @Autowired
    private JdbcTemplate jdbc;

    private TradeFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new TradeFixture(jdbc, BASE).create(3);
    }

    @AfterEach
    void tearDown() {
        fixture.delete();
    }

    @Test
    void theTimerMessageClosesTheUnpaidOrderAtItsDeadline() {
        // The seed still varies per run, but the order number has to carry the buyer's gene:
        // BASE + currentTimeMillis() % 1000 matched it one millisecond in eight.
        long buyer = BASE + 1;
        long orderNo = TradeFixture.orderNo(BASE + System.currentTimeMillis() % 1000, buyer);
        orderService.createPendingOrder(new SeckillOrderMessage(fixture.voucherId, buyer, orderNo));
        long sentAt = System.currentTimeMillis();

        assertThat(scheduler.scheduleClose(orderNo)).isTrue();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> "CLOSED".equals(fixture.orderStatus(orderNo)));
        long closedAfterMs = System.currentTimeMillis() - sentAt;
        assertThat(closedAfterMs).isGreaterThanOrEqualTo(3_000L);
        assertThat(fixture.dbStock()).isEqualTo(3);
    }
}
