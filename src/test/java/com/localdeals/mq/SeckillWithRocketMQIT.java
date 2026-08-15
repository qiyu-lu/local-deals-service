package com.localdeals.mq;

import com.localdeals.utils.RedisIdWorker;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.websocket.WebSocketNotifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
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
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties =
        "rocketmq.consumer.listeners[seckill-consumer-group][seckill-order-topic]=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeckillWithRocketMQIT {

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private IVoucherOrderService voucherOrderService;

    @MockBean
    private WebSocketNotifier webSocketNotifier;

    private static final Long TEST_VOUCHER_ID = 88888L;
    private static final int STOCK = 100;
    private static final int TOTAL_USERS = 500;
    private final Set<Long> issuedOrderIds = java.util.Collections.synchronizedSet(new HashSet<>());

    @BeforeEach
    void setup() {
        issuedOrderIds.clear();
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + TEST_VOUCHER_ID, String.valueOf(STOCK));
        stringRedisTemplate.delete(Arrays.asList(
                SECKILL_ORDER_KEY + TEST_VOUCHER_ID,
                SECKILL_RESERVATION_KEY + TEST_VOUCHER_ID,
                SECKILL_META_KEY + TEST_VOUCHER_ID));
        long now = java.time.Instant.now().getEpochSecond();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("status", "ACTIVE");
        metadata.put("beginAt", Long.toString(now - 60));
        metadata.put("endAt", Long.toString(now + 600));
        stringRedisTemplate.opsForHash().putAll(SECKILL_META_KEY + TEST_VOUCHER_ID, metadata);
    }

    @AfterEach
    void cleanup() {
        // Clear Redis test data so leftover state doesn't bleed into the next run.
        // This test isolates the Redis/RocketMQ admission gate: the DB service and
        // WebSocket notifier are mocks, so no persistent order or external push remains.
        stringRedisTemplate.delete(Arrays.asList(
                SECKILL_STOCK_KEY + TEST_VOUCHER_ID,
                SECKILL_ORDER_KEY + TEST_VOUCHER_ID,
                SECKILL_RESERVATION_KEY + TEST_VOUCHER_ID,
                SECKILL_META_KEY + TEST_VOUCHER_ID));
        if (!issuedOrderIds.isEmpty()) {
            java.util.List<String> statusKeys = issuedOrderIds.stream()
                    .map(id -> SECKILL_ORDER_STATUS_KEY + id)
                    .collect(java.util.stream.Collectors.toList());
            stringRedisTemplate.delete(statusKeys);
        }
    }

    @Test
    void sendSeckillTransaction_concurrentUsers_noOversell() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(TOTAL_USERS);
        Set<Integer> results = java.util.Collections.synchronizedSet(new HashSet<>());
        Set<Long> acceptedOrderIds = java.util.Collections.synchronizedSet(new HashSet<>());

        for (int i = 0; i < TOTAL_USERS; i++) {
            final long userId = 10000L + i;
            pool.submit(() -> {
                try {
                    long orderId = redisIdWorker.nextId("order");
                    issuedOrderIds.add(orderId);
                    int r = seckillOrderProducer.sendSeckillTransaction(TEST_VOUCHER_ID, userId, orderId);
                    results.add(r);
                    if (r == 0) acceptedOrderIds.add(orderId);
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

        // Redis purchased-user set size equals the accepted order count.
        Long setSize = stringRedisTemplate.opsForSet().size(SECKILL_ORDER_KEY + TEST_VOUCHER_ID);
        assertThat(setSize).isEqualTo((long) acceptedOrderIds.size());

        // Do not tear down reservations while the real asynchronous consumer is still
        // finalizing them; that creates artificial retries and can leak messages into DLQ.
        // The longer bound also covers an empty-broker cold start: the listener may need one
        // route refresh/rebalance cycle after the producer auto-creates the topic.
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> acceptedOrderIds.forEach(orderId ->
                        assertThat(stringRedisTemplate.opsForHash().get(
                                SECKILL_ORDER_STATUS_KEY + orderId, "status"))
                                .isEqualTo("SUCCESS")));

        System.out.println("Accepted orders: " + acceptedOrderIds.size() + "/" + TOTAL_USERS);
    }
}
