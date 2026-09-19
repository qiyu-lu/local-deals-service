package com.localdeals.trade.service;

import com.localdeals.platform.config.TrafficControlProperties;
import com.localdeals.trade.config.SeckillProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Stub for the red commit. */
@Service
public class SeckillAdmissionService {

    public static final int ACCEPTED = 0;
    public static final int OUT_OF_STOCK = 1;
    public static final int DUPLICATE = 2;
    public static final int NOT_STARTED = 3;
    public static final int ENDED = 4;
    public static final int META_NOT_READY = 5;
    public static final int USER_RATE_LIMITED = 6;
    public static final int IP_RATE_LIMITED = 7;
    public static final int ORDER_ID_IN_USE = 8;

    public SeckillAdmissionService(StringRedisTemplate redis, SeckillProperties seckillProperties,
                                   TrafficControlProperties trafficProperties) {
    }

    public Admission admit(long voucherId, long userId, long orderId, String clientIp) {
        throw new UnsupportedOperationException("not implemented");
    }

    public record Admission(int code, long remainingStock) {
    }
}
