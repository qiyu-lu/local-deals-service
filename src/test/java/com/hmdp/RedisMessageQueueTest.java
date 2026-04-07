package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class RedisMessageQueueTest {

    // 公共依赖，所有测试都会用到
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisConnectionFactory connectionFactory;


    // =================================================================
    // == 1. 基于 List 实现的消息队列测试
    // =================================================================

    private static final String LIST_QUEUE_KEY = "mq:list:test";

    /** 为 List 队列模式清理残留数据 */
    private void cleanupListQueue() {
        stringRedisTemplate.delete(LIST_QUEUE_KEY);
    }

    /** 为 List 队列模式模拟生产者 */
    private void startListProducer() {
        new Thread(() -> {
            System.out.println("====== [List] 生产者线程已启动 ======");
            for (int i = 1; i <= 10; i++) {
                try {
                    String message = "任务消息-" + i;
                    System.out.println("[List-生产者] 发送消息: " + message);
                    stringRedisTemplate.opsForList().leftPush(LIST_QUEUE_KEY, message);
                    Thread.sleep(1000); //每秒发送一次
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("====== [List] 生产者线程已结束 ======");
        }, "list-producer-thread").start();
    }

    @Test
    void testListQueue() {
        System.out.println("\n\n====== [List] 队列测试开始 ======");
        cleanupListQueue(); // 测试前清理
        startListProducer();

        System.out.println("====== [List-消费者] 开始监听队列 ======");
        int emptyPolls = 0;
        final int maxEmptyPolls = 3;

        while (emptyPolls < maxEmptyPolls) {
            String message = stringRedisTemplate.opsForList().rightPop(LIST_QUEUE_KEY, 2, TimeUnit.SECONDS);
            if (message != null) {
                System.out.println("[List-消费者] 接收并处理消息: " + message);
                emptyPolls = 0;
            } else {
                System.out.println("[List-消费者] 队列暂无消息，继续等待...");
                emptyPolls++;
            }
        }
        System.out.println("====== [List-消费者] 结束监听 ======");
        cleanupListQueue(); // 测试后清理
    }


    // =================================================================
    // == 2. 基于 Pub/Sub 实现的消息队列测试
    // =================================================================

    private static final String PUBSUB_CHANNEL_NAME = "mq:pubsub:test";

    @Test
    void testPubSubQueue() throws Exception {
        System.out.println("\n\n====== [Pub/Sub] 队列测试开始 ======");
        CountDownLatch latch = new CountDownLatch(5);

        // 1. 演示 "Fire-and-Forget"
        String lostMessage = "我是注定丢失的消息-0";
        System.out.println("[Pub/Sub-发布者] 准备发送一条消息，但此时还没有订阅者...");
        stringRedisTemplate.convertAndSend(PUBSUB_CHANNEL_NAME, lostMessage);
        System.out.println("[Pub/Sub-发布者] 已发送 -> " + lostMessage);
        System.out.println("-------------------------------------------------");
        Thread.sleep(500);

        // 2. 创建并启动订阅者
        System.out.println("[Pub/Sub-订阅者] 准备启动并订阅频道: " + PUBSUB_CHANNEL_NAME);
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        MessageListener listener = (message, pattern) -> {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            System.out.printf("[Pub/Sub-订阅者] 从频道[%s]接收到消息: %s%n", channel, body);
            latch.countDown();
        };
        container.addMessageListener(listener, new ChannelTopic(PUBSUB_CHANNEL_NAME));

        // 手动创建的Container必须手动初始化，以装配默认的TaskExecutor
        container.afterPropertiesSet();

        container.start();
        System.out.println("[Pub/Sub-订阅者] 启动成功，正在监听...");
        System.out.println("-------------------------------------------------");
        Thread.sleep(500);

        // 3. 启动发布者线程发送正常消息
        new Thread(() -> {
            System.out.println("====== [Pub/Sub] 发布者线程已启动 ======");
            for (int i = 1; i <= 5; i++) {
                try {
                    String message = "任务消息-" + i;
                    System.out.println("[Pub/Sub-发布者] 发送消息 -> " + message);
                    stringRedisTemplate.convertAndSend(PUBSUB_CHANNEL_NAME, message);
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("====== [Pub/Sub] 发布者线程已结束 ======");
        }, "pubsub-publisher-thread").start();

        // 4. 等待消息接收
        System.out.println("[Pub/Sub-主线程] 等待所有消息被接收...");
        latch.await();

        // 5. 清理资源
        System.out.println("[Pub/Sub-主线程] 所有消息已接收，测试结束，清理资源。");
        container.stop();
        container.destroy();
    }


    // =================================================================
    // == 3. 基于 Stream 实现的消息队列（高可靠）
    // =================================================================

    private static final String STREAM_KEY = "mq:stream:test";
    private static final String GROUP_NAME = "g1";
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(2);

    @Test
    void testStreamQueue() throws InterruptedException {
        System.out.println("\n\n====== [Stream] 队列测试开始 ======");
        // 1. 清理并初始化消费组
        stringRedisTemplate.delete(STREAM_KEY);
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
            System.out.println("[Stream-初始化] 消费组 " + GROUP_NAME + " 创建成功");
        } catch (Exception e) {
            System.out.println("[Stream-初始化] 消费组 " + GROUP_NAME + " 已存在，无需创建");
        }

        // 2. 启动两个消费者
        CountDownLatch latch = new CountDownLatch(10); // 总共10条消息
        submitConsumer("consumer-A", latch);
        submitConsumer("consumer-B", latch);
        System.out.println("====== [Stream] 2个消费者已启动 ======");
        Thread.sleep(500); // 等待消费者启动

        // 3. 启动生产者
        new Thread(() -> {
            System.out.println("====== [Stream] 生产者线程已启动 ======");
            for (int i = 1; i <= 10; i++) {
                Map<String, String> payload = Collections.singletonMap("task", "任务-" + i);
                stringRedisTemplate.opsForStream().add(STREAM_KEY, payload);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
             System.out.println("====== [Stream] 生产者线程已结束 ======");
        }, "stream-producer-thread").start();

        // 4. 等待消费完毕
        latch.await(10, TimeUnit.SECONDS);

        // 5. 清理
        System.out.println("====== [Stream] 测试结束，清理资源 ======");
        streamExecutor.shutdownNow();
        stringRedisTemplate.delete(STREAM_KEY);
    }

    private void submitConsumer(String consumerName, CountDownLatch latch) {
        streamExecutor.submit(() -> {
            Consumer consumer = Consumer.from(GROUP_NAME, consumerName);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            consumer,
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                    );
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = records.get(0);
                    System.out.printf("[Stream-%s] 接收到消息: ID=%s, Payload=%s%n",
                            consumerName, record.getId(), record.getValue());
                    
                    // 确认消息
                    stringRedisTemplate.opsForStream().acknowledge(GROUP_NAME, record);
                    latch.countDown();
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    // 在实际业务中，这里应该记录错误日志
                }
            }
        });
    }
}
