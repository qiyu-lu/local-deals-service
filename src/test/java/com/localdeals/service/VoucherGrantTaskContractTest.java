package com.localdeals.service;

import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.entity.VoucherGrant;
import com.localdeals.mapper.VoucherGrantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoucherGrantTaskContractTest {
    @Test
    void taskKeyIsGeneratedByServerFromTheBusinessDate() {
        VoucherGrantMapper grantMapper = mock(VoucherGrantMapper.class);
        VoucherGrantTransactionService transaction = mock(VoucherGrantTransactionService.class);
        ObjectProvider<com.localdeals.observability.LocalDealsMetrics> metrics = mock(ObjectProvider.class);
        BusinessDateProvider dateProvider = new BusinessDateProvider(
                Clock.fixed(Instant.parse("2026-08-21T16:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ZoneId.of("Asia/Shanghai"));
        AtomicReference<VoucherGrantCommand> captured = new AtomicReference<>();
        VoucherGrant result = new VoucherGrant();
        result.setId(99L);

        when(grantMapper.selectByCampaignAndUserAndKey(3L, 7L,
                "TASK_REWARD:DAILY_SIGN_IN:2026-08-22")).thenReturn(null);
        when(transaction.grant(any(VoucherGrantCommand.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return result;
        });

        VoucherGrantCommand command = new VoucherGrantCommand();
        command.setCampaignId(3L);
        command.setUserId(7L);
        command.setExpectedRuleVersion(1L);
        command.setSource(VoucherGrantCommand.TASK_REWARD);

        VoucherGrant actual = new VoucherGrantService(grantMapper, transaction, metrics, dateProvider)
                .grant(command);

        assertThat(actual.getId()).isEqualTo(99L);
        assertThat(captured.get().getIdempotencyKey())
                .isEqualTo("TASK_REWARD:DAILY_SIGN_IN:2026-08-22");
        assertThat(captured.get().getTaskDate().toString()).isEqualTo("2026-08-22");
    }
}
