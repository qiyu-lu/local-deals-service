package com.localdeals.utils;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
class RedisIdWorkerIT {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    void nextId_30kConcurrentCalls_allUnique() throws InterruptedException {
        int threadCount = 300;
        int idsPerThread = 100;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(500);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                for (int j = 0; j < idsPerThread; j++) {
                    ids.add(redisIdWorker.nextId("test:id:worker"));
                }
                latch.countDown();
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(threadCount * idsPerThread, ids.size(),
                "All 30,000 IDs must be unique across concurrent calls");
    }
}
