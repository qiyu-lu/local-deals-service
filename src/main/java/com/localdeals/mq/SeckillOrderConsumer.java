package com.localdeals.mq;

import com.localdeals.service.IVoucherOrderService;
import com.localdeals.websocket.WebSocketNotifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * Consumes seckill order messages produced after a successful Redis Lua admission check and
 * persists them to MySQL.
 */
@Slf4j
@Service
@RocketMQMessageListener(topic = "seckill-order-topic", consumerGroup = "seckill-consumer-group")
public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private MeterRegistry meterRegistry;

    @Resource
    private WebSocketNotifier webSocketNotifier;

    private Counter consumeSuccessCounter;
    private Counter consumeFailureCounter;

    @PostConstruct
    private void registerMetrics() {
        consumeSuccessCounter = Counter.builder("local_deals.seckill.mq.consume")
                .tag("result", "success").register(meterRegistry);
        consumeFailureCounter = Counter.builder("local_deals.seckill.mq.consume")
                .tag("result", "failure").register(meterRegistry);
    }

    @Override
    public void onMessage(SeckillOrderMessage msg) {
        RLock lock = redissonClient.getLock("lock:order:" + msg.getUserId());
        boolean locked = lock.tryLock();
        if (!locked) {
            log.warn("Seckill order lock busy. userId={}, orderId={}", msg.getUserId(), msg.getOrderId());
            throw new IllegalStateException("Order lock busy, will retry.");
        }
        try {
            voucherOrderService.createVoucherOrder(msg.toVoucherOrder());
            consumeSuccessCounter.increment();
            log.debug("Seckill order persisted. orderId={}", msg.getOrderId());
            webSocketNotifier.notify(msg.getUserId(), true, msg.getOrderId(), msg.getVoucherId());
        } catch (Exception e) {
            consumeFailureCounter.increment();
            log.error("Failed to persist seckill order. orderId={}", msg.getOrderId(), e);
            throw e;  // let RocketMQ retry
        } finally {
            lock.unlock();
        }
    }
}
