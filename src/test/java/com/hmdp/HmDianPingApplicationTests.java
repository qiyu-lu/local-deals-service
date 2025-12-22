package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient  cacheClient;

    @Test
    void testSaveShop2RedisCache() throws InterruptedException {
        shopService.saveShop2RedisCache(1L,10L);
    }

    @Test
    void testCacheClient(){
        Shop shop = new Shop()
                .setId(100L).setName("test").setTypeId(100L).setArea("test");
        cacheClient.queryWithPassThrough("test", 100L, Shop.class,
                shopService::getById,
                30L,
                TimeUnit.SECONDS);
//        log.info("shop:{} expireTime: {}",shop, System.currentTimeMillis() + 30_000L);
    }

    //测试全局ID生成器
    @Autowired
    private RedisIdWorker redisIdWorker;
    // 线程池
    private ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Test
    void testNextId() throws InterruptedException {
        // 用于控制并发开始 让主线程等所有子线程执行完  300 个线程
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> { //一段可以被线程执行的任务说明书 此时还没有真正启动线程
            for (int i = 0; i < 100; i++) {//每个线程的任务 生成 100 个ID
                long id = redisIdWorker.nextId("voucher_order");
                System.out.println(id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();

        // 提交 300 个并发任务 我一共要执行 300 次这个任务，至于用几个线程、怎么调度，你线程池自己决定
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }

        // 等待所有任务执行完成
        latch.await();

        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin) + " ms");
    }
}
