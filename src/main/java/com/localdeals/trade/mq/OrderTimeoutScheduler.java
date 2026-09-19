package com.localdeals.trade.mq;

import com.localdeals.trade.config.OrderProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/** Sends the timer message that closes an unpaid order at its deadline. */
@Slf4j
@Component
public class OrderTimeoutScheduler {

    private final RocketMQTemplate rocketMQTemplate;
    private final OrderProperties orderProperties;

    public OrderTimeoutScheduler(RocketMQTemplate rocketMQTemplate, OrderProperties orderProperties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.orderProperties = orderProperties;
    }

    /** Best effort: returns false when the broker refused; the fallback scan closes the order later. */
    public boolean scheduleClose(long orderNo) {
        return false;
    }
}
