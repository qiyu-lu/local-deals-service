package com.localdeals.trade.mq;

import com.localdeals.trade.utils.SnowflakeOrderIdGenerator;
import com.localdeals.trade.service.IVoucherOrderService;
import com.localdeals.trade.service.SeckillAdmissionService;
import com.localdeals.platform.websocket.WebSocketNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        // The seckill topic is drained by the batch consumer since M4, not by a rocketmq-spring listener.
        "local-deals.seckill.consume.enabled=true",
        "local-deals.traffic.seckill.ip-limit=100000"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeckillWithRocketMQIT {

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private SnowflakeOrderIdGenerator orderIdGenerator;

    @Resource
    private SeckillAdmissionService seckillAdmissionService;

    @Resource
    private com.localdeals.trade.service.SeckillBucketRouter router;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private IVoucherOrderService voucherOrderService;

    @MockitoBean
    private WebSocketNotifier webSocketNotifier;

    private static final Long TEST_VOUCHER_ID = 88888L;
    private static final int STOCK = 100;
    private static final int TOTAL_USERS = 500;
    private final Set<Long> issuedOrderIds = java.util.Collections.synchronizedSet(new HashSet<>());

    @BeforeEach
    void setup() {
        issuedOrderIds.clear();
        long now = java.time.Instant.now().getEpochSecond();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "ACTIVE");
        metadata.put("beginAt", Long.toString(now - 60));
        metadata.put("endAt", Long.toString(now + 600));
        // The stock is split over the buckets; 500 consecutive buyers fill every one of them.
        for (int bucket = 0; bucket < router.count(); bucket++) {
            stringRedisTemplate.delete(Arrays.asList(
                    router.reservationKey(TEST_VOUCHER_ID, bucket),
                    router.metaKey(TEST_VOUCHER_ID, bucket)));
            stringRedisTemplate.opsForValue().set(router.stockKey(TEST_VOUCHER_ID, bucket),
                    Long.toString(router.stockShare(STOCK, bucket)));
            stringRedisTemplate.opsForHash().putAll(router.metaKey(TEST_VOUCHER_ID, bucket), metadata);
        }
    }

    @AfterEach
    void cleanup() {
        // Clear Redis test data so leftover state doesn't bleed into the next run.
        // This test isolates the Redis/RocketMQ admission gate: the DB service and
        // WebSocket notifier are mocks, so no persistent order or external push remains.
        for (int bucket = 0; bucket < router.count(); bucket++) {
            stringRedisTemplate.delete(Arrays.asList(
                    router.stockKey(TEST_VOUCHER_ID, bucket),
                    router.reservationKey(TEST_VOUCHER_ID, bucket),
                    router.metaKey(TEST_VOUCHER_ID, bucket)));
        }
        if (!issuedOrderIds.isEmpty()) {
            java.util.List<String> statusKeys = issuedOrderIds.stream()
                    .map(router::statusKeyOfOrder)
                    .collect(java.util.stream.Collectors.toList());
            stringRedisTemplate.delete(statusKeys);
            for (Long orderId : issuedOrderIds) {
                stringRedisTemplate.opsForZSet().remove(
                        router.processingKey(router.bucketOfOrder(orderId)), String.valueOf(orderId));
            }
        }
    }

    @Test
    void admitThenPublish_concurrentUsers_noOversell() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(TOTAL_USERS);
        Set<Integer> results = java.util.Collections.synchronizedSet(new HashSet<>());
        Set<Long> acceptedOrderIds = java.util.Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < TOTAL_USERS; i++) {
            final long userId = 10000L + i;
            pool.submit(() -> {
                try {
                    long orderId = orderIdGenerator.nextId(userId);
                    issuedOrderIds.add(orderId);
                    int r = seckillAdmissionService.admit(TEST_VOUCHER_ID, userId, orderId, "10.0.0.1").code();
                    results.add(r);
                    if (r == 0) {
                        acceptedOrderIds.add(orderId);
                        seckillOrderProducer.publish(new SeckillOrderMessage(TEST_VOUCHER_ID, userId, orderId));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // A healthy real Redis/RocketMQ path must consume the entire available stock. Merely
        // asserting "not over stock" would let a total infrastructure failure pass with zero.
        assertThat(results).containsOnly(0, 1);
        assertThat(acceptedOrderIds).hasSize(STOCK);
        // All accepted order IDs are unique.
        assertThat(acceptedOrderIds).doesNotHaveDuplicates();

        // The exact reservation Hash is the only purchased-user record in Redis.
        long reservations = 0;
        for (int bucket = 0; bucket < router.count(); bucket++) {
            reservations += stringRedisTemplate.opsForHash()
                    .size(router.reservationKey(TEST_VOUCHER_ID, bucket));
        }
        assertThat(reservations).isEqualTo((long) acceptedOrderIds.size());

        // Do not tear down reservations while the real asynchronous consumer is still
        // finalizing them; that creates artificial retries and can leak messages into DLQ.
        // The longer bound also covers an empty-broker cold start: the listener may need one
        // route refresh/rebalance cycle after the producer auto-creates the topic.
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> acceptedOrderIds.forEach(orderId ->
                        assertThat(stringRedisTemplate.opsForHash().get(
                                router.statusKeyOfOrder(orderId), "status"))
                                .isEqualTo("SUCCESS")));

        // SUCCESS and removal from the due index are one Lua transition. A status-only
        // assertion would miss stale index members that the reconciler scans forever.
        acceptedOrderIds.forEach(orderId -> {
            String member = String.valueOf(orderId);
            assertThat(stringRedisTemplate.opsForZSet().score(
                    router.processingKey(router.bucketOfOrder(orderId)), member)).isNull();
        });

        System.out.println("Accepted orders: " + acceptedOrderIds.size() + "/" + TOTAL_USERS);
    }
}
