package com.localdeals.trade.service;

import com.localdeals.platform.config.TrafficControlProperties;
import com.localdeals.trade.config.SeckillProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * The Redis layer of the admission funnel: one script, one round trip, decides rate limits,
 * activity window, duplicate buyer, stock and the PROCESSING reservation.
 *
 * <p>Every key it touches belongs to the buyer's stock bucket and shares its hash tag, so the
 * call is a single-slot call on a Cluster. The buyer only ever sees their own bucket's stock:
 * the remaining stock in the reply is the bucket's, not the voucher's.</p>
 */
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

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("lua/seckill_check.lua"));
        SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate redis;
    private final SeckillProperties seckillProperties;
    private final TrafficControlProperties trafficProperties;
    private final SeckillBucketRouter router;

    public SeckillAdmissionService(StringRedisTemplate redis, SeckillProperties seckillProperties,
                                   TrafficControlProperties trafficProperties,
                                   SeckillBucketRouter router) {
        this.redis = redis;
        this.seckillProperties = seckillProperties;
        this.trafficProperties = trafficProperties;
        this.router = router;
    }

    /** @throws RuntimeException when Redis fails; the script may or may not have run */
    public Admission admit(long voucherId, long userId, long orderId, String clientIp) {
        TrafficControlProperties.Seckill limits = trafficProperties.getSeckill();
        boolean limited = limits.isEnabled();
        int bucket = router.bucketOfUser(userId);
        if (router.bucketOfOrder(orderId) != bucket) {
            // The order number repeats the buyer's gene. If it does not, its status key would
            // land in another bucket — another slot — and no lookup by order id would find it.
            throw new IllegalArgumentException(
                    "order " + orderId + " does not carry the gene of user " + userId);
        }
        String prefix = router.trafficPrefix(voucherId, bucket);
        List<?> reply = redis.execute(SCRIPT,
                Arrays.asList(
                        router.stockKey(voucherId, bucket),
                        router.metaKey(voucherId, bucket),
                        router.reservationKey(voucherId, bucket),
                        router.statusKey(orderId, bucket),
                        router.processingKey(bucket),
                        prefix + "user:" + userId + ":",
                        prefix + "ip:" + sha256(clientIp) + ":"),
                Long.toString(userId),
                Long.toString(voucherId),
                Long.toString(orderId),
                Long.toString(seckillProperties.getReconciliation().getStaleAfter().getSeconds()),
                Long.toString(limits.getWindow().toMillis()),
                Integer.toString(limited ? limits.getUserLimit() : 0),
                Integer.toString(limited ? limits.getIpLimit() : 0));
        if (reply == null || reply.size() != 2) {
            throw new IllegalStateException("Unexpected seckill admission reply: " + reply);
        }
        return new Admission(((Number) reply.get(0)).intValue(), ((Number) reply.get(1)).longValue());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** @param remainingStock Redis stock after this request, or -1 when the script did not read it */
    public record Admission(int code, long remainingStock) {
    }
}
