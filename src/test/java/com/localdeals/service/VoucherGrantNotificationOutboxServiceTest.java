package com.localdeals.service;

import com.localdeals.config.VoucherBatchProperties;
import com.localdeals.entity.VoucherGrantNotificationOutbox;
import com.localdeals.mapper.VoucherGrantNotificationOutboxMapper;
import com.localdeals.websocket.WebSocketNotifier;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoucherGrantNotificationOutboxServiceTest {

    @Test
    void redisFailureLeavesRowPendingWithCappedRetryMetadata() {
        VoucherGrantNotificationOutboxMapper mapper = mock(VoucherGrantNotificationOutboxMapper.class);
        WebSocketNotifier notifier = mock(WebSocketNotifier.class);
        VoucherGrantNotificationOutbox row = row(11L, 21L);
        when(mapper.selectDueForUpdate(10)).thenReturn(Collections.singletonList(row));
        when(mapper.markRetry(eq(11L), eq(1), any(LocalDateTime.class), eq("REDIS_PUBLISH_FAILED")))
                .thenReturn(1);
        doThrow(new RuntimeException("redis unavailable")).when(notifier).notifyVoucherGranted(row);
        VoucherBatchProperties properties = new VoucherBatchProperties();
        properties.setNotificationMaxAttempts(3);
        VoucherGrantNotificationOutboxService service =
                new VoucherGrantNotificationOutboxService(mapper, notifier, properties);

        VoucherGrantNotificationOutboxService.FlushResult result = service.processNextBatch(10);

        assertThat(result.getSelected()).isEqualTo(1);
        assertThat(result.getPublished()).isZero();
        assertThat(result.getRetried()).isEqualTo(1);
        verify(mapper).markRetry(eq(11L), eq(1), any(LocalDateTime.class), eq("REDIS_PUBLISH_FAILED"));
        verify(mapper, never()).markPublished(11L);
    }

    @Test
    void acceptedRedisPublishMarksOnlyThatOutboxRowPublished() {
        VoucherGrantNotificationOutboxMapper mapper = mock(VoucherGrantNotificationOutboxMapper.class);
        WebSocketNotifier notifier = mock(WebSocketNotifier.class);
        VoucherGrantNotificationOutbox row = row(12L, 22L);
        when(mapper.selectDueForUpdate(10)).thenReturn(Collections.singletonList(row));
        when(mapper.markPublished(12L)).thenReturn(1);
        VoucherGrantNotificationOutboxService service =
                new VoucherGrantNotificationOutboxService(mapper, notifier, new VoucherBatchProperties());

        VoucherGrantNotificationOutboxService.FlushResult result = service.processNextBatch(10);

        assertThat(result.getPublished()).isEqualTo(1);
        assertThat(result.getRetried()).isZero();
        verify(notifier).notifyVoucherGranted(row);
        verify(mapper).markPublished(12L);
        verify(mapper, never()).markRetry(any(Long.class), any(Integer.class),
                any(LocalDateTime.class), any(String.class));
    }

    @Test
    void databaseMarkerFailureIsNotMisreportedAsRedisFailure() {
        VoucherGrantNotificationOutboxMapper mapper = mock(VoucherGrantNotificationOutboxMapper.class);
        WebSocketNotifier notifier = mock(WebSocketNotifier.class);
        VoucherGrantNotificationOutbox row = row(13L, 23L);
        when(mapper.selectDueForUpdate(10)).thenReturn(Collections.singletonList(row));
        when(mapper.markPublished(13L)).thenReturn(0);
        VoucherGrantNotificationOutboxService service =
                new VoucherGrantNotificationOutboxService(mapper, notifier, new VoucherBatchProperties());

        assertThatThrownBy(() -> service.processNextBatch(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publish marker");
        verify(mapper, never()).markRetry(any(Long.class), any(Integer.class),
                any(LocalDateTime.class), any(String.class));
    }

    private VoucherGrantNotificationOutbox row(Long id, Long grantId) {
        VoucherGrantNotificationOutbox row = new VoucherGrantNotificationOutbox();
        row.setId(id);
        row.setGrantId(grantId);
        row.setMerchantId(1L);
        row.setUserId(2L);
        row.setEventType("VOUCHER_GRANTED");
        row.setStatus("PENDING");
        row.setAttempts(0);
        row.setCampaignId(3L);
        row.setVoucherId(4L);
        return row;
    }
}
