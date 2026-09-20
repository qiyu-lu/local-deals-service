package com.localdeals.trade.mq;

import com.localdeals.platform.websocket.WebSocketNotifier;
import com.localdeals.trade.service.SeckillAdmissionService;
import com.localdeals.trade.service.SeckillOrderReconciler;
import com.localdeals.trade.testsupport.TradeFixture;
import com.localdeals.trade.utils.SnowflakeOrderIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Variant B's fault case: the admission Lua succeeded and the process died before anything was
 * sent. Nothing but the Redis reservation is left; the reconciler must still get the order into
 * MySQL through the real broker and consumer.
 */
@SpringBootTest(properties = {
        // The seckill topic is drained by the batch consumer since M4, not by a rocketmq-spring listener.
        "local-deals.seckill.consume.enabled=true",
        "local-deals.seckill.reconciliation.enabled=true",
        "local-deals.seckill.reconciliation.initial-delay=1h",
        "local-deals.seckill.reconciliation.stale-after=1s",
        "local-deals.seckill.reconciliation.retry-delay=2s",
        "local-deals.seckill.reconciliation.final-timeout=10m"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeckillRedriveIT {

    private static final long BASE = 9_208_000L;
    private static final long USER = 9_208_001L;
    private static final int STOCK = 3;

    @Autowired
    private com.localdeals.trade.service.SeckillBucketRouter router;
    @Autowired
    private SeckillAdmissionService admissionService;
    @Autowired
    private SnowflakeOrderIdGenerator orderIdGenerator;
    @Autowired
    private SeckillOrderReconciler reconciler;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private OrderTimeoutScheduler timeoutScheduler;
    @MockitoBean
    private WebSocketNotifier webSocketNotifier;

    private TradeFixture fixture;
    private long orderId;

    /** Everything this test does belongs to one buyer, so to one bucket. */
    private int bucket() {
        return router.bucketOfUser(USER);
    }

    @BeforeEach
    void setUp() {
        fixture = new TradeFixture(jdbc, BASE).create(STOCK);
        long now = Instant.now().getEpochSecond();
        redis.opsForValue().set(router.stockKey(fixture.voucherId, bucket()), Integer.toString(STOCK));
        redis.opsForHash().putAll(router.metaKey(fixture.voucherId, bucket()), Map.of(
                "status", "ACTIVE", "beginAt", Long.toString(now - 3600), "endAt", Long.toString(now + 3600)));
    }

    @AfterEach
    void tearDown() {
        redis.delete(java.util.List.of(router.stockKey(fixture.voucherId, bucket()), router.metaKey(fixture.voucherId, bucket()),
                router.reservationKey(fixture.voucherId, bucket()), router.statusKeyOfOrder(orderId)));
        redis.opsForZSet().remove(router.processingKey(bucket()), Long.toString(orderId));
        fixture.delete();
    }

    @Test
    void anAdmittedOrderWhoseMessageWasNeverSentStillReachesMySql() {
        orderId = orderIdGenerator.nextId(USER);
        assertThat(admissionService.admit(fixture.voucherId, USER, orderId, "127.0.0.1").code())
                .isEqualTo(SeckillAdmissionService.ACCEPTED);
        // The process is gone here: no message was published.

        await().atMost(60, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            reconciler.reconcileDueOrders();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE order_no = ?",
                    Integer.class, orderId)).isEqualTo(1);
        });
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(redis.opsForHash().get(router.statusKeyOfOrder(orderId), "status")).isEqualTo("SUCCESS"));

        assertThat(fixture.orderStatus(orderId)).isEqualTo("PENDING_PAY");
        assertThat(fixture.dbStock()).isEqualTo(STOCK - 1);
        assertThat(redis.opsForValue().get(router.stockKey(fixture.voucherId, bucket()))).isEqualTo(Integer.toString(STOCK - 1));
        assertThat(redis.opsForZSet().score(router.processingKey(bucket()), Long.toString(orderId))).isNull();
    }
}
