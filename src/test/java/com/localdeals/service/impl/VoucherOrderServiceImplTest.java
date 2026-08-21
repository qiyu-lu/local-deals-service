package com.localdeals.service.impl;

import com.localdeals.dto.Result;
import com.localdeals.dto.SeckillOrderPersistenceResult;
import com.localdeals.dto.SeckillOrderStatusDTO;
import com.localdeals.dto.UserDTO;
import com.localdeals.entity.VoucherOrder;
import com.localdeals.mapper.VoucherOrderMapper;
import com.localdeals.mq.SeckillOrderProducer;
import com.localdeals.service.SeckillOrderStateService;
import com.localdeals.service.SeckillTrafficGuard;
import com.localdeals.utils.RedisIdWorker;
import com.localdeals.utils.UserHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VoucherOrderServiceImplTest {

    private static final long LARGE_ORDER_ID = 90071992547409931L;

    private VoucherOrderServiceImpl service;
    private VoucherOrderMapper voucherOrderMapper;
    private RedisIdWorker redisIdWorker;
    private SeckillOrderProducer producer;
    private SeckillOrderStateService stateService;
    private SeckillTrafficGuard trafficGuard;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderServiceImpl();
        redisIdWorker = mock(RedisIdWorker.class);
        producer = mock(SeckillOrderProducer.class);
        stateService = mock(SeckillOrderStateService.class);
        trafficGuard = mock(SeckillTrafficGuard.class);

        voucherOrderMapper = mock(VoucherOrderMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "seckillOrderProducer", producer);
        ReflectionTestUtils.setField(service, "seckillOrderStateService", stateService);
        ReflectionTestUtils.setField(service, "seckillTrafficGuard", trafficGuard);
        ReflectionTestUtils.setField(service, "meterRegistry", new SimpleMeterRegistry());
        ReflectionTestUtils.invokeMethod(service, "registerMetrics");

        UserDTO user = new UserDTO();
        user.setId(23L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void acceptedOrderIdUsesExactStringWireContract() {
        when(redisIdWorker.nextId("order")).thenReturn(LARGE_ORDER_ID);
        when(producer.sendSeckillTransaction(17L, 23L, LARGE_ORDER_ID)).thenReturn(0);

        Result result = service.seckillVoucher(17L, "203.0.113.9");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("90071992547409931");
    }

    @Test
    void ambiguousProducerFailureRecoversOnlyTheExactProcessingReservation() {
        when(redisIdWorker.nextId("order")).thenReturn(LARGE_ORDER_ID);
        when(producer.sendSeckillTransaction(17L, 23L, LARGE_ORDER_ID)).thenReturn(-1);
        when(stateService.find(LARGE_ORDER_ID)).thenReturn(new SeckillOrderStateService.Snapshot(
                LARGE_ORDER_ID, 23L, 17L, SeckillOrderStateService.STATUS_PROCESSING, null));

        Result result = service.seckillVoucher(17L, "203.0.113.9");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(Long.toString(LARGE_ORDER_ID));
    }

    @Test
    void businessRejectionsKeepHttp200BodyContractWithStableCodes() {
        when(redisIdWorker.nextId("order")).thenReturn(LARGE_ORDER_ID);
        String[] expectedCodes = {
                com.localdeals.exception.ApiErrorCodes.SECKILL_OUT_OF_STOCK,
                com.localdeals.exception.ApiErrorCodes.SECKILL_DUPLICATE,
                com.localdeals.exception.ApiErrorCodes.SECKILL_NOT_STARTED,
                com.localdeals.exception.ApiErrorCodes.SECKILL_ENDED
        };
        for (int resultCode = 1; resultCode <= 4; resultCode++) {
            when(producer.sendSeckillTransaction(17L, 23L, LARGE_ORDER_ID))
                    .thenReturn(resultCode);

            Result result = service.seckillVoucher(17L, "203.0.113.9");

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getCode()).isEqualTo(expectedCodes[resultCode - 1]);
        }
    }

    @Test
    void unavailableMetadataAndUnrecoveredProducerFailureReturn503Codes() {
        when(redisIdWorker.nextId("order")).thenReturn(LARGE_ORDER_ID);
        when(producer.sendSeckillTransaction(17L, 23L, LARGE_ORDER_ID)).thenReturn(5);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.seckillVoucher(17L, "203.0.113.9"))
                .isInstanceOfSatisfying(com.localdeals.exception.ApiStatusException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(503);
                    assertThat(error.getCode()).isEqualTo(
                            com.localdeals.exception.ApiErrorCodes.SECKILL_STATE_UNAVAILABLE);
                });

        when(producer.sendSeckillTransaction(17L, 23L, LARGE_ORDER_ID)).thenReturn(-1);
        when(stateService.find(LARGE_ORDER_ID)).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.seckillVoucher(17L, "203.0.113.9"))
                .isInstanceOfSatisfying(com.localdeals.exception.ApiStatusException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(503);
                    assertThat(error.getCode()).isEqualTo(
                            com.localdeals.exception.ApiErrorCodes.SECKILL_SUBMIT_UNAVAILABLE);
                });
    }

    @Test
    void idAllocationFailureDoesNotSendMq() {
        when(redisIdWorker.nextId("order")).thenThrow(new RuntimeException("redis down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.seckillVoucher(17L, "203.0.113.9"))
                .isInstanceOfSatisfying(com.localdeals.exception.ApiStatusException.class, error ->
                        assertThat(error.getCode()).isEqualTo(
                                com.localdeals.exception.ApiErrorCodes.SECKILL_SUBMIT_UNAVAILABLE));

        verifyNoInteractions(producer, stateService);
    }

    @Test
    void rateRejectionOccursBeforeIdAllocationAndMqSubmission() {
        org.mockito.Mockito.doThrow(new com.localdeals.exception.ApiStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                com.localdeals.exception.ApiErrorCodes.SECKILL_RATE_LIMITED, "busy"))
                .when(trafficGuard).check(17L, 23L, "203.0.113.9");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.seckillVoucher(17L, "203.0.113.9"))
                .isInstanceOf(com.localdeals.exception.ApiStatusException.class);

        verifyNoInteractions(redisIdWorker, producer, stateService);
    }

    @Test
    void statusLookupIsUserScopedAndKeepsOrderIdAsString() {
        when(stateService.find(LARGE_ORDER_ID)).thenReturn(new SeckillOrderStateService.Snapshot(
                LARGE_ORDER_ID, 23L, 17L, SeckillOrderStateService.STATUS_PROCESSING, null));

        Result ownResult = service.querySeckillOrderStatus(LARGE_ORDER_ID);

        assertThat(ownResult.getSuccess()).isTrue();
        SeckillOrderStatusDTO dto = (SeckillOrderStatusDTO) ownResult.getData();
        assertThat(dto.getOrderId()).isEqualTo("90071992547409931");
        assertThat(dto.getStatus()).isEqualTo("PROCESSING");

        when(stateService.find(LARGE_ORDER_ID)).thenReturn(new SeckillOrderStateService.Snapshot(
                LARGE_ORDER_ID, 24L, 17L, SeckillOrderStateService.STATUS_PROCESSING, null));
        Result otherUsersResult = service.querySeckillOrderStatus(LARGE_ORDER_ID);
        assertThat(otherUsersResult.getSuccess()).isFalse();
        assertThat(otherUsersResult.getErrorMsg()).contains("无权");
    }

    @Test
    void persistedOrderWinsOverStaleProcessingStateAndRepairsRedis() {
        when(stateService.find(LARGE_ORDER_ID)).thenReturn(new SeckillOrderStateService.Snapshot(
                LARGE_ORDER_ID, 23L, 17L, SeckillOrderStateService.STATUS_PROCESSING, null));
        VoucherOrder persisted = new VoucherOrder();
        persisted.setId(LARGE_ORDER_ID);
        persisted.setUserId(23L);
        persisted.setVoucherId(17L);
        when(voucherOrderMapper.selectById(LARGE_ORDER_ID)).thenReturn(persisted);
        when(stateService.markSuccess(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        Result result = service.querySeckillOrderStatus(LARGE_ORDER_ID);

        assertThat(result.getSuccess()).isTrue();
        SeckillOrderStatusDTO dto = (SeckillOrderStatusDTO) result.getData();
        assertThat(dto.getStatus()).isEqualTo(SeckillOrderStateService.STATUS_SUCCESS);
        assertThat(dto.getOrderId()).isEqualTo(Long.toString(LARGE_ORDER_ID));
        verify(stateService).markSuccess(argThat(message ->
                message.getOrderId().equals(LARGE_ORDER_ID) &&
                        message.getUserId().equals(23L) &&
                        message.getVoucherId().equals(17L)));
    }

    @Test
    void processingTimeoutHasDedicatedUserFacingReason() {
        when(stateService.find(LARGE_ORDER_ID)).thenReturn(new SeckillOrderStateService.Snapshot(
                LARGE_ORDER_ID, 23L, 17L, SeckillOrderStateService.STATUS_FAILED,
                "PROCESSING_TIMEOUT"));

        Result result = service.querySeckillOrderStatus(LARGE_ORDER_ID);

        assertThat(result.getSuccess()).isTrue();
        SeckillOrderStatusDTO dto = (SeckillOrderStatusDTO) result.getData();
        assertThat(dto.getReason()).isEqualTo("订单处理超时，预占已释放");
    }

    @Test
    void classifyPersistence_requiresExactOwnershipForSuccess() {
        VoucherOrder persisted = order(LARGE_ORDER_ID, 23L, 17L);
        when(voucherOrderMapper.selectById(LARGE_ORDER_ID)).thenReturn(persisted);

        SeckillOrderPersistenceResult result =
                service.classifyPersistence(LARGE_ORDER_ID, 23L, 17L);

        assertThat(result.getType()).isEqualTo(SeckillOrderPersistenceResult.Type.EXACT_MATCH);
        assertThat(result.getPersistedOrderId()).isEqualTo(LARGE_ORDER_ID);
    }

    @Test
    void classifyPersistence_detectsOrderIdOwnedByAnotherReservation() {
        VoucherOrder persisted = order(LARGE_ORDER_ID, 99L, 88L);
        when(voucherOrderMapper.selectById(LARGE_ORDER_ID)).thenReturn(persisted);

        SeckillOrderPersistenceResult result =
                service.classifyPersistence(LARGE_ORDER_ID, 23L, 17L);

        assertThat(result.getType()).isEqualTo(SeckillOrderPersistenceResult.Type.ORDER_ID_CONFLICT);
        assertThat(result.getPersistedUserId()).isEqualTo(99L);
        assertThat(result.getPersistedVoucherId()).isEqualTo(88L);
    }

    @Test
    void classifyPersistence_detectsDifferentOrderForSameUserAndVoucher() {
        when(voucherOrderMapper.selectById(LARGE_ORDER_ID)).thenReturn(null);
        when(voucherOrderMapper.selectOne(any())).thenReturn(order(777L, 23L, 17L));

        SeckillOrderPersistenceResult result =
                service.classifyPersistence(LARGE_ORDER_ID, 23L, 17L);

        assertThat(result.getType())
                .isEqualTo(SeckillOrderPersistenceResult.Type.USER_VOUCHER_CONFLICT);
        assertThat(result.getPersistedOrderId()).isEqualTo(777L);
    }

    @Test
    void classifyPersistence_reportsAbsentOnlyAfterBothWriterQueriesAreEmpty() {
        when(voucherOrderMapper.selectById(LARGE_ORDER_ID)).thenReturn(null);
        when(voucherOrderMapper.selectOne(any())).thenReturn(null);

        SeckillOrderPersistenceResult result =
                service.classifyPersistence(LARGE_ORDER_ID, 23L, 17L);

        assertThat(result.getType()).isEqualTo(SeckillOrderPersistenceResult.Type.ABSENT);
    }

    @Test
    void classifyPersistence_doesNotConvertDatabaseFailureToAbsence() {
        when(voucherOrderMapper.selectById(LARGE_ORDER_ID))
                .thenThrow(new IllegalStateException("writer unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.classifyPersistence(LARGE_ORDER_ID, 23L, 17L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("writer unavailable");
    }

    private static VoucherOrder order(Long orderId, Long userId, Long voucherId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        return order;
    }
}
