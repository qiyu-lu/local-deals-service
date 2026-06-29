package com.localdeals.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.localdeals.config.SeckillProperties;
import com.localdeals.dto.Result;
import com.localdeals.entity.VoucherOrder;
import com.localdeals.mapper.VoucherOrderMapper;
import com.localdeals.service.ISeckillVoucherService;
import com.localdeals.service.IVoucherOrderService;
import com.localdeals.utils.RedisIdWorker;
import com.localdeals.utils.UserHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * Voucher seckill service.
 *
 * <p>The HTTP thread only performs Redis Lua admission control and enqueueing.
 * MySQL persistence is completed by Redis Stream consumers with DB idempotency
 * and bounded pending-message retry.</p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Lazy
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private Environment environment;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private SeckillProperties seckillProperties;
    @Resource
    private MeterRegistry meterRegistry;

    private ExecutorService seckillOrderExecutor;
    private volatile boolean running = true;

    private Counter requestAcceptedCounter;
    private Counter requestStockRejectedCounter;
    private Counter requestDuplicateRejectedCounter;
    private Counter consumeSuccessCounter;
    private Counter consumeFailureCounter;
    private Counter consumeRetryCounter;
    private Counter consumeDeadLetterCounter;
    private Counter duplicateOrderCounter;
    private Counter stockRollbackCounter;
    private Timer consumeTimer;
    private final AtomicLong streamLengthGauge = new AtomicLong();
    private final AtomicLong streamPendingGauge = new AtomicLong();
    private final AtomicLong deadLetterLengthGauge = new AtomicLong();

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        registerMetrics();
        if (Arrays.asList(environment.getActiveProfiles()).contains("test")) {
            log.info("Skip seckill order consumer in test profile.");
            return;
        }

        createStreamGroupIfNecessary();

        int workerCount = Math.max(1, streamProperties().getWorkerCount());
        seckillOrderExecutor = Executors.newFixedThreadPool(workerCount, namedThreadFactory());
        for (int i = 0; i < workerCount; i++) {
            String consumerName = consumerName(i, workerCount);
            seckillOrderExecutor.submit(new VoucherOrderHandler(consumerName));
        }
        log.info(
                "Started seckill order consumers. stream={}, group={}, baseConsumer={}, workers={}, readCount={}, maxRetry={}",
                streamProperties().getKey(),
                streamProperties().getGroup(),
                streamProperties().getConsumer(),
                workerCount,
                readCount(),
                streamProperties().getMaxRetry()
        );
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (seckillOrderExecutor != null) {
            seckillOrderExecutor.shutdownNow();
        }
    }

    private class VoucherOrderHandler implements Runnable {

        private final String consumerName;

        private VoucherOrderHandler(String consumerName) {
            this.consumerName = consumerName;
        }

        @Override
        public void run() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(streamProperties().getGroup(), consumerName),
                            StreamReadOptions.empty().count(readCount()).block(blockDuration()),
                            StreamOffset.create(streamProperties().getKey(), ReadOffset.lastConsumed())
                    );
                    if (records == null || records.isEmpty()) {
                        handlePendingList();
                        refreshStreamMetrics();
                        continue;
                    }

                    for (MapRecord<String, Object, Object> record : records) {
                        processRecord(record, false);
                    }
                    refreshStreamMetrics();
                } catch (RedisSystemException e) {
                    if (isConnectionClosed(e)) {
                        log.warn("Redis connection closed while consuming seckill orders, retrying later.");
                        sleepQuietly(100);
                        continue;
                    }
                    log.error("Failed to consume seckill order stream.", e);
                    handlePendingList();
                } catch (Exception e) {
                    log.error("Unexpected seckill order consumer error.", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(streamProperties().getGroup(), consumerName),
                            StreamReadOptions.empty().count(readCount()),
                            StreamOffset.create(streamProperties().getKey(), ReadOffset.from("0"))
                    );
                    if (records == null || records.isEmpty()) {
                        break;
                    }
                    for (MapRecord<String, Object, Object> record : records) {
                        processRecord(record, true);
                    }
                    refreshStreamMetrics();
                } catch (RedisSystemException e) {
                    if (isConnectionClosed(e)) {
                        log.warn("Redis connection closed while handling pending seckill orders.");
                        break;
                    }
                    log.error("Failed to handle pending seckill orders.", e);
                    break;
                } catch (Exception e) {
                    log.error("Unexpected pending-list handling error.", e);
                    break;
                }
            }
        }

        private void processRecord(MapRecord<String, Object, Object> record, boolean pending) {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                VoucherOrder voucherOrder = toVoucherOrder(record);
                handleVoucherOrder(voucherOrder);
                acknowledge(record);
                clearRetry(record);
                consumeSuccessCounter.increment();
            } catch (Exception e) {
                consumeFailureCounter.increment();
                handleProcessFailure(record, e, pending);
            } finally {
                sample.stop(consumeTimer);
            }
        }

        private void handleProcessFailure(MapRecord<String, Object, Object> record, Exception e, boolean pending) {
            try {
                long retries = incrementRetry(record);
                if (retries >= Math.max(0, streamProperties().getMaxRetry())) {
                    sendDeadLetter(record, e, retries);
                    acknowledge(record);
                    clearRetry(record);
                    consumeDeadLetterCounter.increment();
                    log.error(
                            "Seckill order message moved to dead letter. recordId={}, pending={}, retries={}",
                            record.getId().getValue(),
                            pending,
                            retries,
                            e
                    );
                    return;
                }
                consumeRetryCounter.increment();
                log.warn(
                        "Seckill order message will remain pending for retry. recordId={}, pending={}, retries={}/{}",
                        record.getId().getValue(),
                        pending,
                        retries,
                        streamProperties().getMaxRetry(),
                        e
                );
            } catch (Exception failureHandlingException) {
                log.error("Failed to handle seckill order processing failure. recordId={}", record.getId(), failureHandlingException);
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean locked = lock.tryLock();
        if (!locked) {
            throw new IllegalStateException("User order lock is busy. userId=" + userId);
        }
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        long orderId = redisIdWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(
                        SECKILL_STOCK_KEY + voucherId,
                        SECKILL_ORDER_KEY + voucherId,
                        streamProperties().getKey()
                ),
                userId.toString(), voucherId.toString(), String.valueOf(orderId)
        );
        if (result == null) {
            throw new IllegalStateException("Seckill Lua script returned null.");
        }

        int r = result.intValue();
        if (r == 0) {
            requestAcceptedCounter.increment();
            return Result.ok(orderId);
        }
        if (r == 1) {
            requestStockRejectedCounter.increment();
            return Result.fail("库存不足");
        }
        requestDuplicateRejectedCounter.increment();
        return Result.fail("您已抢过该优惠券");
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        try {
            this.save(voucherOrder);
        } catch (DuplicateKeyException e) {
            duplicateOrderCounter.increment();
            log.warn(
                    "Duplicate voucher order ignored by DB unique constraint. userId={}, voucherId={}, orderId={}",
                    voucherOrder.getUserId(),
                    voucherOrder.getVoucherId(),
                    voucherOrder.getId()
            );
            return;
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!success) {
            stockRollbackCounter.increment();
            throw new IllegalStateException(
                    "DB seckill stock is not enough. voucherId=" + voucherOrder.getVoucherId() +
                            ", orderId=" + voucherOrder.getId()
            );
        }
    }

    private VoucherOrder toVoucherOrder(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(Long.valueOf(requiredValue(value, "voucherId")));
        voucherOrder.setUserId(Long.valueOf(requiredValue(value, "userId")));
        voucherOrder.setId(Long.valueOf(requiredValue(value, "orderId")));
        return voucherOrder;
    }

    private String requiredValue(Map<Object, Object> value, String key) {
        Object result = value.get(key);
        if (result == null) {
            throw new IllegalArgumentException("Missing stream field: " + key);
        }
        return result.toString();
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream().acknowledge(
                streamProperties().getKey(),
                streamProperties().getGroup(),
                record.getId()
        );
    }

    private long incrementRetry(MapRecord<String, Object, Object> record) {
        String key = retryKey(record);
        Long retries = stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, 1, TimeUnit.DAYS);
        return retries == null ? 1L : retries;
    }

    private void clearRetry(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.delete(retryKey(record));
    }

    private String retryKey(MapRecord<String, Object, Object> record) {
        return streamProperties().getRetryKeyPrefix() + record.getId().getValue();
    }

    private void sendDeadLetter(MapRecord<String, Object, Object> record, Exception e, long retries) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("sourceStream", streamProperties().getKey());
        message.put("sourceGroup", streamProperties().getGroup());
        message.put("sourceRecordId", record.getId().getValue());
        message.put("retries", String.valueOf(retries));
        message.put("errorClass", e.getClass().getName());
        message.put("errorMessage", Objects.toString(e.getMessage(), ""));
        for (Map.Entry<Object, Object> entry : record.getValue().entrySet()) {
            message.put("payload." + entry.getKey(), Objects.toString(entry.getValue(), ""));
        }
        stringRedisTemplate.opsForStream().add(streamProperties().getDeadLetterKey(), message);
    }

    private void createStreamGroupIfNecessary() {
        try {
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP",
                    raw("CREATE"),
                    raw(streamProperties().getKey()),
                    raw(streamProperties().getGroup()),
                    raw("$"),
                    raw("MKSTREAM")
            ));
        } catch (RedisSystemException e) {
            if (containsMessage(e, "BUSYGROUP")) {
                log.info("Redis Stream consumer group already exists. stream={}, group={}",
                        streamProperties().getKey(), streamProperties().getGroup());
                return;
            }
            throw e;
        }
    }

    private byte[] raw(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void registerMetrics() {
        requestAcceptedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "accepted")
                .register(meterRegistry);
        requestStockRejectedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "rejected_stock")
                .register(meterRegistry);
        requestDuplicateRejectedCounter = Counter.builder("local_deals.seckill.requests")
                .tag("result", "rejected_duplicate")
                .register(meterRegistry);
        consumeSuccessCounter = Counter.builder("local_deals.seckill.stream.consume")
                .tag("result", "success")
                .register(meterRegistry);
        consumeFailureCounter = Counter.builder("local_deals.seckill.stream.consume")
                .tag("result", "failure")
                .register(meterRegistry);
        consumeRetryCounter = Counter.builder("local_deals.seckill.stream.consume")
                .tag("result", "retry")
                .register(meterRegistry);
        consumeDeadLetterCounter = Counter.builder("local_deals.seckill.stream.consume")
                .tag("result", "dead_letter")
                .register(meterRegistry);
        duplicateOrderCounter = Counter.builder("local_deals.seckill.db.orders")
                .tag("result", "duplicate")
                .register(meterRegistry);
        stockRollbackCounter = Counter.builder("local_deals.seckill.db.orders")
                .tag("result", "stock_rollback")
                .register(meterRegistry);
        consumeTimer = Timer.builder("local_deals.seckill.stream.consume.duration")
                .description("Time spent handling one Redis Stream order message")
                .register(meterRegistry);
        Gauge.builder("local_deals.seckill.stream.length", streamLengthGauge, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("local_deals.seckill.stream.pending", streamPendingGauge, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("local_deals.seckill.stream.dead_letter_length", deadLetterLengthGauge, AtomicLong::get)
                .register(meterRegistry);
    }

    private void refreshStreamMetrics() {
        try {
            Long streamLength = stringRedisTemplate.opsForStream().size(streamProperties().getKey());
            PendingMessagesSummary summary = stringRedisTemplate.opsForStream().pending(
                    streamProperties().getKey(),
                    streamProperties().getGroup()
            );
            Long deadLetterLength = stringRedisTemplate.opsForStream().size(streamProperties().getDeadLetterKey());
            streamLengthGauge.set(streamLength == null ? 0L : streamLength);
            streamPendingGauge.set(summary == null ? 0L : summary.getTotalPendingMessages());
            deadLetterLengthGauge.set(deadLetterLength == null ? 0L : deadLetterLength);
        } catch (Exception e) {
            log.debug("Failed to refresh seckill stream metrics.", e);
        }
    }

    private ThreadFactory namedThreadFactory() {
        AtomicInteger threadIndex = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("seckill-order-consumer-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private String consumerName(int index, int workerCount) {
        String baseConsumer = streamProperties().getConsumer();
        return workerCount == 1 ? baseConsumer : baseConsumer + "-" + (index + 1);
    }

    private int readCount() {
        return Math.max(1, streamProperties().getReadCount());
    }

    private Duration blockDuration() {
        Duration block = streamProperties().getBlock();
        return block == null ? Duration.ofSeconds(2) : block;
    }

    private SeckillProperties.Stream streamProperties() {
        return seckillProperties.getStream();
    }

    private boolean isConnectionClosed(Exception e) {
        return containsMessage(e, "Connection closed");
    }

    private boolean containsMessage(Throwable e, String content) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(content)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
