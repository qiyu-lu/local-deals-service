package com.localdeals.service;

import com.localdeals.dto.VoucherGrantCommand;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.VoucherGrantMapper;
import com.localdeals.observability.LocalDealsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VoucherGrantFailureTest {
    @Test
    void databaseFailureIs503WithoutEnteringTransactionOrWritingSideEffects() {
        VoucherGrantMapper grantMapper = mock(VoucherGrantMapper.class);
        VoucherGrantTransactionService transactionService = mock(VoucherGrantTransactionService.class);
        ObjectProvider<LocalDealsMetrics> metricsProvider = mock(ObjectProvider.class);
        LocalDealsMetrics metrics = new LocalDealsMetrics(new SimpleMeterRegistry());
        when(metricsProvider.getIfAvailable()).thenReturn(metrics);
        when(grantMapper.selectByCampaignAndUser(7L, 9L))
                .thenThrow(new DataAccessResourceFailureException("mysql stopped"));

        VoucherGrantService service = new VoucherGrantService(
                grantMapper, transactionService, metricsProvider);
        VoucherGrantCommand command = new VoucherGrantCommand();
        command.setCampaignId(7L);
        command.setUserId(9L);
        command.setExpectedRuleVersion(1L);
        command.setSource(VoucherGrantCommand.USER_CLAIM);

        assertThatThrownBy(() -> service.grant(command))
                .isInstanceOf(ApiStatusException.class)
                .satisfies(error -> {
                    ApiStatusException apiError = (ApiStatusException) error;
                    assertThat(apiError.getStatus().value()).isEqualTo(503);
                    assertThat(apiError.getCode()).isEqualTo(ApiErrorCodes.DATABASE_UNAVAILABLE);
                });
        verifyNoInteractions(transactionService);
        verify(grantMapper).selectByCampaignAndUser(7L, 9L);
        assertThat(metricsProvider.getIfAvailable()).isSameAs(metrics);
    }
}
