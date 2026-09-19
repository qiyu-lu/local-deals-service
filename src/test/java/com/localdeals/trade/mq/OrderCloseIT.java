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

import static com.localdeals.platform.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.platform.utils.RedisConstants.SECKILL_STOCK_KEY;
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
        redis.opsForValue().set(SECKILL_STOCK_KEY + fixture.voucherId, Integer.toString(STOCK));
        redis.opsForHash().putAll(SECKILL_META_KEY + fixture.voucherId, Map.of(
                "status", "ACTIVE", "beginAt", Long.toString(now - 3600), "endAt", Long.toString(now + 3600)));
    }

    @AfterEach
    void tearDown() {
        clearRedis();
        fixture.delete();
    }

    @Test
    void anAdmittedOrderIsPersistedPendingAndGetsItsTimeoutScheduled() {
        buy(USER, BASE + 1);

        assertThat(fixture.orderStatus(BASE + 1)).isEqualTo("PENDING_PAY");
        assertThat(redisStock()).isEqualTo(STOCK - 1);
        assertThat(fixture.dbStock()).isEqualTo(STOCK - 1);
        org.mockito.Mockito.verify(timeoutScheduler).scheduleClose(BASE + 1);
    }

    /** Plan race 3. */
    @Test
    void duplicateCloseMessagesReturnTheStockExactlyOnce() throws Exception {
        buy(USER, BASE + 2);
        fixture.expire(BASE + 2);

        List<Outcome> outcomes = concurrently(10, () -> closeService.closeIfExpired(BASE + 2, "SYSTEM"));
        closeConsumer.onMessage(new OrderCloseMessage(BASE + 2));

        assertThat(outcomes).containsOnlyOnce(Outcome.CLOSED);
        assertThat(outcomes).filteredOn(outcome -> outcome != Outcome.CLOSED)
                .containsOnly(Outcome.ALREADY_CLOSED);
        assertThat(fixture.orderStatus(BASE + 2)).isEqualTo("CLOSED");
        assertThat(fixture.dbStock()).isEqualTo(STOCK);
        assertThat(redisStock()).isEqualTo(STOCK);
        assertThat(redis.opsForHash().hasKey(SECKILL_RESERVATION_KEY + fixture.voucherId, Long.toString(USER)))
                .isFalse();
        assertThat(releasePending(BASE + 2)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_state_log WHERE order_no = ? AND event = 'CLOSE'",
                Integer.class, BASE + 2)).isEqualTo(1);
    }

    /** Plan race 5, through Redis admission as well as the database. */
    @Test
    void theSameUserCanBuyAgainAfterTheOrderIsClosed() {
        buy(USER, BASE + 3);
        assertThat(admit(USER, BASE + 4)).isEqualTo(SeckillAdmissionService.DUPLICATE);

        fixture.expire(BASE + 3);
        assertThat(closeService.closeIfExpired(BASE + 3, "SYSTEM")).isEqualTo(Outcome.CLOSED);

        buy(USER, BASE + 5);
        assertThat(fixture.orderStatus(BASE + 3)).isEqualTo("CLOSED");
        assertThat(fixture.orderStatus(BASE + 5)).isEqualTo("PENDING_PAY");
        assertThat(redisStock()).isEqualTo(STOCK - 1);
        assertThat(fixture.dbStock()).isEqualTo(STOCK - 1);
    }

    @Test
    void anOrderBeforeItsDeadlineIsNotClosed() {
        buy(USER, BASE + 6);

        assertThat(closeService.closeIfExpired(BASE + 6, "SYSTEM")).isEqualTo(Outcome.NOT_DUE);
        assertThat(closeService.closeIfExpired(BASE + 999, "SYSTEM")).isEqualTo(Outcome.NOT_FOUND);
        assertThat(fixture.dbStock()).isEqualTo(STOCK - 1);
    }

    @Test
    void theScanClosesOrdersWhoseTimerMessageWasLost() {
        buy(USER, BASE + 7);
        jdbc.update("UPDATE trade_order SET expire_at = NOW(3) - INTERVAL 5 MINUTE WHERE order_no = ?", BASE + 7);

        assertThat(scanner.scanOnce()).isGreaterThanOrEqualTo(1);

        assertThat(fixture.orderStatus(BASE + 7)).isEqualTo("CLOSED");
        assertThat(redisStock()).isEqualTo(STOCK);
        assertThat(releasePending(BASE + 7)).isNull();
    }

    @Test
    void theScanFinishesARedisReleaseThatFailedAfterCommit() {
        buy(USER, BASE + 8);
        fixture.expire(BASE + 8);
        assertThat(closeService.closeIfExpired(BASE + 8, "SYSTEM")).isEqualTo(Outcome.CLOSED);
        // Simulate a Redis outage right after the commit: the unit is back in MySQL only.
        redis.opsForHash().put(SECKILL_RESERVATION_KEY + fixture.voucherId, Long.toString(USER), Long.toString(BASE + 8));
        redis.opsForValue().decrement(SECKILL_STOCK_KEY + fixture.voucherId);
        jdbc.update("UPDATE trade_order SET release_pending = 1, update_time = NOW(3) - INTERVAL 1 MINUTE " +
                "WHERE order_no = ?", BASE + 8);

        scanner.scanOnce();

        assertThat(releasePending(BASE + 8)).isNull();
        assertThat(redisStock()).isEqualTo(STOCK);
        assertThat(redis.opsForHash().hasKey(SECKILL_RESERVATION_KEY + fixture.voucherId, Long.toString(USER)))
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
        return new SeckillAdmissionService(redis, new SeckillProperties(), noLimits)
                .admit(fixture.voucherId, userId, orderNo, "127.0.0.1").code();
    }

    private int redisStock() {
        return Integer.parseInt(redis.opsForValue().get(SECKILL_STOCK_KEY + fixture.voucherId));
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
        redis.delete(List.of(SECKILL_STOCK_KEY + fixture.voucherId, SECKILL_META_KEY + fixture.voucherId,
                SECKILL_RESERVATION_KEY + fixture.voucherId));
        for (long orderNo = BASE; orderNo < BASE + 1000; orderNo++) {
            redis.opsForZSet().remove("seckill:order:processing", Long.toString(orderNo));
        }
        redis.delete(redis.keys(SECKILL_ORDER_STATUS_KEY + BASE / 1000 + "*"));
    }
}
