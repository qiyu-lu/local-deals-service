package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker  redisIdWorker;

    @Lazy
    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    // 在类初始化完毕后，就立即执行这个任务
    @Profile("!test")
    @PostConstruct
    private void init() {
        // 确保Redis Stream和消费者组存在
        try {
            stringRedisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);
        } catch (RedisSystemException e) {
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if (message != null && message.contains("NOGROUP No such key")) {
                // Stream不存在，创建Stream和Group
                log.info("Stream '{}' not found, creating stream and group '{}'.", streamKey, groupName);
                // 创建Stream（通过添加一个虚拟消息）
                stringRedisTemplate.opsForStream().add(streamKey, Collections.singletonMap("init", "true"));
                // 创建Group
                stringRedisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);
            } else if (message != null && message.contains("BUSYGROUP Consumer Group name already exists")) {
                // Group已存在，属于正常情况
                log.info("Consumer group '{}' already exists.", groupName);
            } else {
                // 其他异常
                log.error("Failed to create Redis stream consumer group.", e);
            }
        }
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private final String streamKey = "stream.orders";
    private final String groupName = "g1";
    private final String consumerName = "c1";


    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明没有新消息，继续下一次循环
                        continue;
                    }
                    // 3. 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = new VoucherOrder();
                    voucherOrder.setVoucherId(Long.valueOf(value.get("voucherId").toString()));
                    voucherOrder.setUserId(Long.valueOf(value.get("userId").toString()));
                    voucherOrder.setId(Long.valueOf(value.get("orderId").toString()));

                    // 4. 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    // 5. ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());

                }
                catch (RedisSystemException e) {
                    String msg = e.getMessage();
                    if(msg != null && msg.contains("Connection closed")){
                        log.warn("Redis 连接关闭，稍后重试");
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ex) {
                            throw new RuntimeException(ex);
                        }
                        continue; // 不再去处理 pending-list，直接下一轮
                    }
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
                catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 处理异常消息 (pending-list)
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(streamKey, ReadOffset.from("0"))
                    );
                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明pending-list没有异常消息，结束循环
                        break;
                    }
                    // 3. 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = new VoucherOrder();
                    voucherOrder.setVoucherId(Long.valueOf(value.get("voucherId").toString()));
                    voucherOrder.setUserId(Long.valueOf(value.get("userId").toString()));
                    voucherOrder.setId(Long.valueOf(value.get("orderId").toString()));

                    // 4. 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    // 5. ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());

                } catch (Exception e) {
                    if (e instanceof RedisSystemException) {
                        String msg = e.getMessage();
                        if (msg != null && msg.contains("Connection closed")) {
                            log.warn("Redis 连接关闭，停止处理 pending-list");
                            break;
                        }
                    }
                    log.error("处理pending-list订单异常", e);
                    try {
                        // 稍作等待，避免CPU空转
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户id
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            //获取锁失败，直接返回错误信息
            log.error("不允许重复下单！");
            return;
        }
        try{
            //注意：由于是this.createVoucherOrder调用，事务想要生效需要通过代理对象调用
            // 我们在类中注入了IVoucherOrderService的代理对象来解决此问题
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 生成订单ID
        long orderId = redisIdWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();

        // 2. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(
                        SECKILL_STOCK_KEY + voucherId,
                        SECKILL_ORDER_KEY + voucherId,
                        streamKey
                ),
                userId.toString(), voucherId.toString(), String.valueOf(orderId)
        );

        // 3. 判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 3.1. 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "您已抢过该优惠券");
        }

        // 4. 返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //因为是异步线程不一样，不用使用原来的方式getid
        Long userId = voucherOrder.getUserId();

        // 4. 一人一单校验
        int count = this.query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户 {} 重复下单！", userId);
            return;
        }

        // 5. 扣减库存（乐观锁）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0) // where id =
                .update();// ? and stock > 0

        if (!success) {
            log.error("库存不足");
            return;
        }

        // 6. 创建订单
        // voucherOrder 对象已经由主线程创建并放入了队列，这里直接保存即可
        this.save(voucherOrder);
    }
}
