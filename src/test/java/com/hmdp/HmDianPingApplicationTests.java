package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import io.lettuce.core.api.async.RedisGeoAsyncCommands;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient  cacheClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

    //将数据商户数据存入redis
    @Test
    void loadShopData(){
        //查询店铺信息
        List<Shop> list = shopService.list();
        //将店铺按照tyepId分组，一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批完成写入redis
        for(Map.Entry<Long, List<Shop>> entry : map.entrySet()){
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            List<Shop> value = entry.getValue();

            //写入redis GEOADD key 经度纬度 member
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(), new Point(shop.getX(), shop.getY())
                ));
            }

            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @DisplayName("测试UV统计，插入数据")
    @Test
    void testHyperLogLog(){
        //准备数组，装用户数据
        String[] users = new String[1000];
        //数组角标
        int index = 0;
        for(int i = 1; i <= 1000000; i++){
            users[index++] = "user_" + i;
            //每1000条发送一次
            if(i % 1000 == 0){
                index = 0;
                stringRedisTemplate.opsForHyperLogLog()
                        .add("hll1", users);
            }
        }
        //统计数量
        Long size = stringRedisTemplate
                .opsForHyperLogLog().size("hll1");
        System.out.println("size = " + size);
    }
}
