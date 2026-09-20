package com.localdeals.trade.service;

import com.localdeals.platform.dto.Result;
import com.localdeals.platform.exception.ApiErrorCodes;
import com.localdeals.trade.config.SeckillProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeckillTokenServiceTest {

    private static final long NOW = 1_790_000_000L;
    private static final long USER_ID = 10_000_001L;
    private static final SeckillBucketRouter ROUTER = new SeckillBucketRouter(16);

    private final AtomicLong clock = new AtomicLong(NOW);
    private HashOperations<String, Object, Object> hashes;
    private SeckillTokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        hashes = mock(HashOperations.class);
        when(redis.<Object, Object>opsForHash()).thenReturn(hashes);
        SeckillProperties.Token config = new SeckillProperties.Token();
        config.setSecret("bench-secret");
        config.setTtl(Duration.ofMinutes(10));
        service = new SeckillTokenService(redis, config, clock::get, ROUTER);
        activity("ACTIVE", NOW - 60, NOW + 600);
    }

    private void activity(String status, long beginAt, long endAt) {
        when(hashes.multiGet(ROUTER.metaKey(42L, ROUTER.bucketOfUser(USER_ID)),
                Arrays.asList("status", "beginAt", "endAt")))
                .thenReturn(Arrays.asList(status, Long.toString(beginAt), Long.toString(endAt)));
    }

    private String issue() {
        Result result = service.issue(USER_ID, 42L);
        assertThat(result.getSuccess()).isTrue();
        return (String) result.getData();
    }

    @Test
    void aTokenIsBoundToItsUserAndVoucher() {
        String token = issue();

        assertThat(service.verify(USER_ID, 42L, token)).isTrue();
        assertThat(service.verify(10_000_002L, 42L, token)).isFalse();
        assertThat(service.verify(USER_ID, 43L, token)).isFalse();
    }

    @Test
    void theWireFormatIsExpiryDotBase64UrlHmacSoLoadClientsCanComputeIt() {
        // Same vector as benchmark/v2/scripts/seckill.js computes with k6/crypto.
        assertThat(service.verify(USER_ID, 42L, "1790000000.EcOsZDGq-DorZnQvRzgbXRYmPscDrwYek2QS6TwkdP8"))
                .isTrue();
        assertThat(issue()).isEqualTo((NOW + 600) + "." + issue().substring(11));
    }

    @Test
    void anExpiredTokenIsRejected() {
        String token = issue();
        clock.addAndGet(601);

        assertThat(service.verify(USER_ID, 42L, token)).isFalse();
    }

    @Test
    void tamperedAndMalformedTokensAreRejected() {
        String token = issue();
        String forgedExpiry = (NOW + 86_400) + token.substring(token.indexOf('.'));

        assertThat(service.verify(USER_ID, 42L, forgedExpiry)).isFalse();
        assertThat(service.verify(USER_ID, 42L, token + "x")).isFalse();
        for (String bad : new String[]{null, "", ".", "abc", "123.", ".abc", "x.y"}) {
            assertThat(service.verify(USER_ID, 42L, bad)).isFalse();
        }
    }

    @Test
    void noTokenBeforeTheStartOrAfterTheEnd() {
        activity("ACTIVE", NOW + 60, NOW + 600);
        assertThat(service.issue(USER_ID, 42L).getCode()).isEqualTo(ApiErrorCodes.SECKILL_NOT_STARTED);

        activity("ACTIVE", NOW - 600, NOW - 60);
        assertThat(service.issue(USER_ID, 42L).getCode()).isEqualTo(ApiErrorCodes.SECKILL_ENDED);

        activity("SUSPENDED", NOW - 60, NOW + 600);
        assertThat(service.issue(USER_ID, 42L).getCode()).isEqualTo(ApiErrorCodes.SECKILL_ENDED);
    }
}
