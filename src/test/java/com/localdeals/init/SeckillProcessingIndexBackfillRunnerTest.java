package com.localdeals.init;

import com.localdeals.config.SeckillProperties;
import com.localdeals.mq.SeckillOrderMessage;
import com.localdeals.service.SeckillOrderStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import java.util.Arrays;

import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillProcessingIndexBackfillRunnerTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RKeys keys;
    @Mock
    private SeckillOrderStateService stateService;

    private SeckillProperties properties;
    private SeckillProcessingIndexBackfillRunner runner;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getReconciliation().setScanCount(37);
        runner = new SeckillProcessingIndexBackfillRunner(redissonClient, stateService, properties);
        when(redissonClient.getKeys()).thenReturn(keys);
    }

    @Test
    void lazyScanBackfillsExactStatesQuarantinesRawKeysAndFailsCanonicalUnsafeState() {
        String valid = statusKey(11L);
        String already = statusKey(12L);
        String terminal = statusKey(13L);
        String mismatch = statusKey(14L);
        String incomplete = statusKey(15L);
        String malformedKey = SECKILL_ORDER_STATUS_KEY + "not-a-long";
        String leadingZeroKey = SECKILL_ORDER_STATUS_KEY + "01";
        String signedKey = SECKILL_ORDER_STATUS_KEY + "+1";
        String emptySuffixKey = SECKILL_ORDER_STATUS_KEY;
        when(keys.getKeysByPattern(SECKILL_ORDER_STATUS_KEY + "*", 37))
                .thenReturn(Arrays.asList(valid, already, terminal, mismatch, incomplete,
                        malformedKey, leadingZeroKey, signedKey, emptySuffixKey));

        when(stateService.find(11L)).thenReturn(snapshot(11L));
        when(stateService.find(12L)).thenReturn(snapshot(12L));
        when(stateService.find(13L)).thenReturn(snapshot(13L));
        when(stateService.find(14L)).thenReturn(snapshot(14L));
        when(stateService.find(15L)).thenReturn(null);
        when(stateService.backfillProcessingOrder(message(11L)))
                .thenReturn(SeckillOrderStateService.ProcessingBackfillDecision.INDEXED);
        when(stateService.backfillProcessingOrder(message(12L)))
                .thenReturn(SeckillOrderStateService.ProcessingBackfillDecision.ALREADY_INDEXED);
        when(stateService.backfillProcessingOrder(message(13L)))
                .thenReturn(SeckillOrderStateService.ProcessingBackfillDecision.TERMINAL);
        when(stateService.backfillProcessingOrder(message(14L)))
                .thenReturn(SeckillOrderStateService.ProcessingBackfillDecision.RESERVATION_MISMATCH);
        when(stateService.quarantineProcessingMember(malformedKey, "BACKFILL_INVALID_STATUS_KEY"))
                .thenReturn(true);
        when(stateService.quarantineProcessingMember(leadingZeroKey, "BACKFILL_INVALID_STATUS_KEY"))
                .thenReturn(true);
        when(stateService.quarantineProcessingMember(signedKey, "BACKFILL_INVALID_STATUS_KEY"))
                .thenReturn(true);
        when(stateService.quarantineProcessingMember(emptySuffixKey, "BACKFILL_INVALID_STATUS_KEY"))
                .thenReturn(true);

        SeckillProcessingIndexBackfillRunner.BackfillSummary summary = runner.backfillOnce();

        assertThat(summary.getScanned()).isEqualTo(9);
        assertThat(summary.getIndexed()).isEqualTo(1);
        assertThat(summary.getAlreadyIndexed()).isEqualTo(1);
        assertThat(summary.getTerminal()).isEqualTo(1);
        assertThat(summary.getQuarantined()).isEqualTo(4);
        assertThat(summary.getUnsafe()).isEqualTo(2);
        verify(keys).getKeysByPattern(SECKILL_ORDER_STATUS_KEY + "*", 37);
    }

    @Test
    void startupFailsClosedWhenUnsafeEvidenceCannotBeQuarantined() {
        String malformedKey = SECKILL_ORDER_STATUS_KEY + "broken";
        when(keys.getKeysByPattern(SECKILL_ORDER_STATUS_KEY + "*", 37))
                .thenReturn(Arrays.asList(malformedKey));
        when(stateService.quarantineProcessingMember(malformedKey, "BACKFILL_INVALID_STATUS_KEY"))
                .thenReturn(false);

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 unsafe record");
    }

    private SeckillOrderStateService.Snapshot snapshot(Long orderId) {
        return new SeckillOrderStateService.Snapshot(orderId, 21L, 31L, "PROCESSING", null);
    }

    private SeckillOrderMessage message(Long orderId) {
        return new SeckillOrderMessage(31L, 21L, orderId);
    }

    private String statusKey(Long orderId) {
        return SECKILL_ORDER_STATUS_KEY + orderId;
    }
}
