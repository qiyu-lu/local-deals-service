package com.localdeals.trade.mq;

import com.localdeals.platform.config.TrafficControlProperties;
import com.localdeals.trade.config.SeckillProperties;
import com.localdeals.trade.service.SeckillAdmissionService;

import com.localdeals.trade.service.OrderCloseService;
import com.localdeals.trade.service.OrderCloseService.Outcome;
import com.localdeals.trade.service.OrderTimeoutScanner;
import com.localdeals.trade.testsupport.TradeFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Timeout close against real MySQL and Redis, driven through the real admission Lua and seckill
 * consumer. The timer message itself is covered by {@link OrderCloseTimerIT} on a real broker.
 *
 * <ul>
 *   <li>Plan race 3: duplicate close messages return the stock exactly once, in MySQL and Redis.</li>
 *   <li>Plan race 5, end to end: after the close the same user passes admission again and gets a
 *       second order.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderCloseIT {

    private static final long BASE = 9_202_000L;
    private static final long USER = 9_202_001L;
    private static final int STOCK = 5;

    /**
     * Order numbers carry the buyer's gene, which is what routes their Redis state to the
     * buyer's bucket. Spacing them by the gene width keeps them distinct and in one bucket.
     */
    private static long order(long n) {
        return ((BASE + n * 1024L) & ~1023L) | (USER & 1023L);
    }

    @Autowired
    private com.localdeals.trade.service.SeckillBucketRouter router;
    @Autowired
    private SeckillOrderConsumer seckillConsumer;
    @Autowired
    private OrderCloseConsumer closeConsumer;
    @Autowired
    private OrderCloseService closeService;
    @Autowired
    private OrderTimeoutScanner scanner;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private OrderTimeoutScheduler timeoutScheduler;

    private TradeFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new TradeFixture(jdbc, BASE).create(STOCK);
        clearRedis();
        long now = Instant.now().getEpochSecond();
        redis.opsForValue().set(stockKey(), Integer.toString(STOCK));
        redis.opsForHash().putAll(metaKey(), Map.of(
                "status", "ACTIVE", "beginAt", Long.toString(now - 3600), "endAt", Long.toString(now + 3600)));
    }

    @AfterEach
    void tearDown() {
        clearRedis();
        fixture.delete();
    }

    @Test
    void anAdmittedOrderIsPersistedPendingAndGetsItsTimeoutScheduled() {
        buy(USER, order(1));

        assertThat(fixture.orderStatus(order(1))).isEqualTo("PENDING_PAY");
        assertThat(redisStock()).isEqualTo(STOCK - 1);
        assertThat(fixture.dbStock()).isEqualTo(STOCK - 1);
        org.mockito.Mockito.verify(timeoutScheduler).scheduleClose(order(1));
    }

    /** Plan race 3. */
    @Test
    void duplicateCloseMessagesReturnTheStockExactlyOnce() throws Exception {
        buy(USER, order(2));
        fixture.expire(order(2));

        List<Outcome> outcomes = concurrently(10, () -> closeService.closeIfExpired(order(2), "SYSTEM"));
        closeConsumer.onMessage(new OrderCloseMessage(order(2)));

        assertThat(outcomes).containsOnlyOnce(Outcome.CLOSED);
        assertThat(outcomes).filteredOn(outcome -> outcome != Outcome.CLOSED)
                .containsOnly(Outcome.ALREADY_CLOSED);
        assertThat(fixture.orderStatus(order(2))).isEqualTo("CLOSED");
        assertThat(fixture.dbStock()).isEqualTo(STOCK);
        assertThat(redisStock()).isEqualTo(STOCK);
        assertThat(redis.opsForHash().hasKey(reservationKey(), Long.toString(USER)))
                .isFalse();
        assertThat(releasePending(order(2))).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_state_log WHERE order_no = ? AND event = 'CLOSE'",
                Integer.class, order(2))).isEqualTo(1);
    }

    /** Plan race 5, through Redis admission as well as the database. */
    @Test
    void theSameUserCanBuyAgainAfterTheOrderIsClosed() {
        buy(USER, order(3));
        assertThat(admit(USER, order(4))).isEqualTo(SeckillAdmissionService.DUPLICATE);

        fixture.expire(order(3));
        assertThat(closeService.closeIfExpired(order(3), "SYSTEM")).isEqualTo(Outcome.CLOSED);

        buy(USER, order(5));
        assertThat(fixture.orderStatus(order(3))).isEqualTo("CLOSED");
        assertThat(fixture.orderStatus(order(5))).isEqualTo("PENDING_PAY");
        assertThat(redisStock()).isEqualTo(STOCK - 1);
        assertThat(fixture.dbStock()).isEqualTo(STOCK - 1);
    }

    @Test
    void anOrderBeforeItsDeadlineIsNotClosed() {
        buy(USER, order(6));

        assertThat(closeService.closeIfExpired(order(6), "SYSTEM")).isEqualTo(Outcome.NOT_DUE);
        assertThat(closeService.closeIfExpired(order(999), "SYSTEM")).isEqualTo(Outcome.NOT_FOUND);
        assertThat(fixture.dbStock()).isEqualTo(STOCK - 1);
    }

    @Test
    void theScanClosesOrdersWhoseTimerMessageWasLost() {
        buy(USER, order(7));
        jdbc.update("UPDATE trade_order SET expire_at = NOW(3) - INTERVAL 5 MINUTE WHERE order_no = ?", order(7));

        assertThat(scanner.scanOnce()).isGreaterThanOrEqualTo(1);

        assertThat(fixture.orderStatus(order(7))).isEqualTo("CLOSED");
        assertThat(redisStock()).isEqualTo(STOCK);
        assertThat(releasePending(order(7))).isNull();
    }

    @Test
    void theScanFinishesARedisReleaseThatFailedAfterCommit() {
        buy(USER, order(8));
        fixture.expire(order(8));
        assertThat(closeService.closeIfExpired(order(8), "SYSTEM")).isEqualTo(Outcome.CLOSED);
        // Simulate a Redis outage right after the commit: the unit is back in MySQL only.
        redis.opsForHash().put(reservationKey(), Long.toString(USER), Long.toString(order(8)));
        redis.opsForValue().decrement(stockKey());
        jdbc.update("UPDATE trade_order SET release_pending = 1, update_time = NOW(3) - INTERVAL 1 MINUTE " +
                "WHERE order_no = ?", order(8));

        scanner.scanOnce();

        assertThat(releasePending(order(8))).isNull();
        assertThat(redisStock()).isEqualTo(STOCK);
        assertThat(redis.opsForHash().hasKey(reservationKey(), Long.toString(USER)))
                .isFalse();
        // Releasing twice must not add a second unit.
        scanner.scanOnce();
        assertThat(redisStock()).isEqualTo(STOCK);
    }

    private void buy(long userId, long orderNo) {
        assertThat(admit(userId, orderNo)).isEqualTo(SeckillAdmissionService.ACCEPTED);
        seckillConsumer.onMessage(new SeckillOrderMessage(fixture.voucherId, userId, orderNo));
    }

    private int admit(long userId, long orderNo) {
        TrafficControlProperties noLimits = new TrafficControlProperties();
        noLimits.getSeckill().setEnabled(false);
        return new SeckillAdmissionService(redis, new SeckillProperties(), noLimits, router)
                .admit(fixture.voucherId, userId, orderNo, "127.0.0.1").code();
    }

    private int redisStock() {
        return Integer.parseInt(redis.opsForValue().get(stockKey()));
    }

    private Integer releasePending(long orderNo) {
        return jdbc.queryForObject("SELECT release_pending FROM trade_order WHERE order_no = ?", Integer.class, orderNo);
    }

    private <T> List<T> concurrently(int threads, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void clearRedis() {
        redis.delete(List.of(stockKey(), metaKey(), reservationKey()));
        for (long n = 0; n < 1_000; n++) {
            long orderNo = order(n);
            redis.opsForZSet().remove(router.processingKey(bucket()), Long.toString(orderNo));
            redis.delete(router.statusKeyOfOrder(orderNo));
        }
    }

    private int bucket() {
        return router.bucketOfUser(USER);
    }

    private String stockKey() {
        return router.stockKey(fixture.voucherId, bucket());
    }

    private String metaKey() {
        return router.metaKey(fixture.voucherId, bucket());
    }

    private String reservationKey() {
        return router.reservationKey(fixture.voucherId, bucket());
    }
}
