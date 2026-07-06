package com.localdeals.mq;

import com.localdeals.utils.RedisIdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SeckillWithRocketMQIT {

    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final Long TEST_VOUCHER_ID = 88888L;
    private static final int STOCK = 100;
    private static final int TOTAL_USERS = 500;

    @BeforeEach
    void setup() {
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + TEST_VOUCHER_ID, String.valueOf(STOCK));
        stringRedisTemplate.delete(SECKILL_ORDER_KEY + TEST_VOUCHER_ID);
    }

    @AfterEach
    void cleanup() {
        // Clear Redis test data so leftover state doesn't bleed into the next run.
        // Note: this test only validates Lua admission control (the in-memory gate).
        // Messages queued to RocketMQ during this test will be gracefully ACKed by
        // SeckillOrderConsumer via StockExhaustedException — no DB row exists for
        // TEST_VOUCHER_ID=88888, so no infinite retry loop can occur.
        stringRedisTemplate.delete(SECKILL_STOCK_KEY + TEST_VOUCHER_ID);
        stringRedisTemplate.delete(SECKILL_ORDER_KEY + TEST_VOUCHER_ID);
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
                    int r = seckillOrderProducer.sendSeckillTransaction(TEST_VOUCHER_ID, userId, orderId);
                    results.add(r);
                    if (r == 0) acceptedOrderIds.add(orderId);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        // Accepted orders must not exceed stock.
        assertThat(acceptedOrderIds).hasSizeLessThanOrEqualTo(STOCK);
        // All accepted order IDs are unique.
        assertThat(acceptedOrderIds).doesNotHaveDuplicates();

        // Redis purchased-user set size equals the accepted order count.
        Long setSize = stringRedisTemplate.opsForSet().size(SECKILL_ORDER_KEY + TEST_VOUCHER_ID);
        assertThat(setSize).isEqualTo((long) acceptedOrderIds.size());

        System.out.println("Accepted orders: " + acceptedOrderIds.size() + "/" + TOTAL_USERS);
    }
}
