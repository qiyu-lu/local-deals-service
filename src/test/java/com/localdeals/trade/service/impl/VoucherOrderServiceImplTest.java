package com.localdeals.trade.service.impl;

import com.localdeals.platform.testsupport.MybatisPlusMocks;
import com.localdeals.platform.dto.Result;
import com.localdeals.trade.dto.SeckillOrderPersistenceResult;
import com.localdeals.trade.dto.SeckillOrderStatusDTO;
import com.localdeals.platform.dto.UserDTO;
import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.mapper.TradeOrderMapper;
import com.localdeals.trade.mq.SeckillOrderProducer;
import com.localdeals.trade.service.SeckillOrderStateService;
import com.localdeals.trade.service.SeckillAdmissionService;
import com.localdeals.trade.service.SeckillLocalRateLimiter;
import com.localdeals.trade.service.SeckillSoldOutRegistry;
import com.localdeals.platform.observability.LocalDealsMetrics;
import com.localdeals.trade.utils.SnowflakeOrderIdGenerator;
import com.localdeals.platform.utils.UserHolder;
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

    private static final long SECOND_ORDER_ID = 90071992547409932L;

    private VoucherOrderServiceImpl service;
    private TradeOrderMapper tradeOrderMapper;
    private SnowflakeOrderIdGenerator orderIdGenerator;
    private SeckillAdmissionService admissionService;
    private SeckillOrderProducer producer;
    private SeckillOrderStateService stateService;
    private SeckillSoldOutRegistry soldOut;
    private SeckillLocalRateLimiter bucket;

    private static final com.localdeals.trade.service.SeckillBucketRouter ROUTER =
            new com.localdeals.trade.service.SeckillBucketRouter(16);
    private static final int BUCKET = ROUTER.bucketOfUser(23L);

    @BeforeEach
    void setUp() {
        service = new VoucherOrderServiceImpl();
        orderIdGenerator = mock(SnowflakeOrderIdGenerator.class);
        admissionService = mock(SeckillAdmissionService.class);
        producer = mock(SeckillOrderProducer.class);
        stateService = mock(SeckillOrderStateService.class);
        soldOut = mock(SeckillSoldOutRegistry.class);
        bucket = mock(SeckillLocalRateLimiter.class);
        when(bucket.tryAcquire(17L, BUCKET)).thenReturn(true);
        ReflectionTestUtils.setField(service, "seckillBucketRouter", ROUTER);

        tradeOrderMapper = mock(TradeOrderMapper.class);
        MybatisPlusMocks.injectMapper(service, tradeOrderMapper, TradeOrder.class);
        ReflectionTestUtils.setField(service, "orderIdGenerator", orderIdGenerator);
        ReflectionTestUtils.setField(service, "seckillAdmissionService", admissionService);
        ReflectionTestUtils.setField(service, "seckillOrderProducer", producer);
        ReflectionTestUtils.setField(service, "seckillOrderStateService", stateService);
        ReflectionTestUtils.setField(service, "seckillSoldOutRegistry", soldOut);
        ReflectionTestUtils.setField(service, "seckillLocalRateLimiter", bucket);
        ReflectionTestUtils.setField(service, "meterRegistry", new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "localDealsMetrics", mock(LocalDealsMetrics.class));
        ReflectionTestUtils.invokeMethod(service, "registerMetrics");

        UserDTO user = new UserDTO();
        user.setId(23L);
        UserHolder.saveUser(user);
        when(orderIdGenerator.nextId(23L)).thenReturn(LARGE_ORDER_ID);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private void admissionReturns(int code) {
        when(admissionService.admit(17L, 23L, LARGE_ORDER_ID, "203.0.113.9"))
                .thenReturn(new SeckillAdmissionService.Admission(code, 5L));
    }

    @Test
    void acceptedOrderIdUsesExactStringWireContractAndPublishesOnce() {
        admissionReturns(SeckillAdmissionService.ACCEPTED);

        Result result = service.seckillVoucher(17L, "203.0.113.9");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("90071992547409931");
        verify(producer).publish(argThat(message -> message.getOrderId().equals(LARGE_ORDER_ID)
                && message.getUserId().equals(23L) && message.getVoucherId().equals(17L)));
    }

    @Test
    void rejectedRequestsNeverTouchTheBroker() {
        String[] expectedCodes = {
                com.localdeals.platform.exception.ApiErrorCodes.SECKILL_OUT_OF_STOCK,
                com.localdeals.platform.exception.ApiErrorCodes.SECKILL_DUPLICATE,
                com.localdeals.platform.exception.ApiErrorCodes.SECKILL_NOT_STARTED,
                com.localdeals.platform.exception.ApiErrorCodes.SECKILL_ENDED
        };
        for (int code = 1; code <= 4; code++) {
            admissionReturns(code);

            Result result = service.seckillVoucher(17L, "203.0.113.9");

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getCode()).isEqualTo(expectedCodes[code - 1]);
        }
        verifyNoInteractions(producer);
    }

    @Test
    void userAndIpLimitsAnswer429WithoutTouchingTheBroker() {
        for (int code : new int[]{SeckillAdmissionService.USER_RATE_LIMITED,
                SeckillAdmissionService.IP_RATE_LIMITED}) {
            admissionReturns(code);

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.seckillVoucher(17L, "203.0.113.9"))
                    .isInstanceOfSatisfying(com.localdeals.platform.exception.ApiStatusException.class, error -> {
                        assertThat(error.getStatus().value()).isEqualTo(429);
                        assertThat(error.getCode()).isEqualTo(
                                com.localdeals.platform.exception.ApiErrorCodes.SECKILL_RATE_LIMITED);
                    });
        }
        verifyNoInteractions(producer);
    }

    @Test
    void unavailableMetadataReturns503() {
        admissionReturns(SeckillAdmissionService.META_NOT_READY);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.seckillVoucher(17L, "203.0.113.9"))
                .isInstanceOfSatisfying(com.localdeals.platform.exception.ApiStatusException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(503);
                    assertThat(error.getCode()).isEqualTo(
                            com.localdeals.platform.exception.ApiErrorCodes.SECKILL_STATE_UNAVAILABLE);
                });
        verifyNoInteractions(producer);
    }

    @Test
    void aFailedPublishStillReturnsTheReservedOrder() {
        admissionReturns(SeckillAdmissionService.ACCEPTED);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker unavailable"))
                .when(producer).publish(any());

        Result result = service.seckillVoucher(17L, "203.0.113.9");

        // The Redis reservation is durable; the reconciler repairs a missing message.
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(Long.toString(LARGE_ORDER_ID));
    }

    @Test
    void anAmbiguousAdmissionErrorRecoversOnlyTheExactProcessingReservation() {
        when(admissionService.admit(17L, 23L, LARGE_ORDER_ID, "203.0.113.9"))
                .thenThrow(new IllegalStateException("redis timeout after execution"));
        when(stateService.find(LARGE_ORDER_ID)).thenReturn(new SeckillOrderStateService.Snapshot(
                LARGE_ORDER_ID, 23L, 17L, SeckillOrderStateService.STATUS_PROCESSING, null));

        Result result = service.seckillVoucher(17L, "203.0.113.9");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(Long.toString(LARGE_ORDER_ID));
        verify(producer).publish(any());
    }

    @Test
    void anUnrecoveredAdmissionErrorReturns503WithoutPublishing() {
        when(admissionService.admit(17L, 23L, LARGE_ORDER_ID, "203.0.113.9"))
                .thenThrow(new IllegalStateException("redis down"));
        when(stateService.find(LARGE_ORDER_ID)).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.seckillVoucher(17L, "203.0.113.9"))
                .isInstanceOfSatisfying(com.localdeals.platform.exception.ApiStatusException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(503);
                    assertThat(error.getCode()).isEqualTo(
                            com.localdeals.platform.exception.ApiErrorCodes.SECKILL_SUBMIT_UNAVAILABLE);
                });
        verifyNoInteractions(producer);
    }

    @Test
    void anOrderIdAlreadyInUseIsReplacedOnce() {
        when(orderIdGenerator.nextId(23L)).thenReturn(LARGE_ORDER_ID, SECOND_ORDER_ID);
        admissionReturns(SeckillAdmissionService.ORDER_ID_IN_USE);
        when(admissionService.admit(17L, 23L, SECOND_ORDER_ID, "203.0.113.9"))
                .thenReturn(new SeckillAdmissionService.Admission(SeckillAdmissionService.ACCEPTED, 4L));

        Result result = service.seckillVoucher(17L, "203.0.113.9");

        assertThat(result.getData()).isEqualTo(Long.toString(SECOND_ORDER_ID));
        verify(producer).publish(argThat(message -> message.getOrderId().equals(SECOND_ORDER_ID)));
    }

    @Test
    void idAllocationFailureTouchesNeitherRedisNorTheBroker() {
        when(orderIdGenerator.nextId(23L)).thenThrow(new IllegalStateException("no worker lease"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.seckillVoucher(17L, "203.0.113.9"))
                .isInstanceOfSatisfying(com.localdeals.platform.exception.ApiStatusException.class, error ->
                        assertThat(error.getCode()).isEqualTo(
                                com.localdeals.platform.exception.ApiErrorCodes.SECKILL_SUBMIT_UNAVAILABLE));

        verifyNoInteractions(admissionService, producer, stateService);
    }

    @Test
    void theLocalBucketTurnsExcessAwayBeforeAnyIdOrRedisCall() {
        when(bucket.tryAcquire(17L, BUCKET)).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.seckillVoucher(17L, "203.0.113.9"))
                .isInstanceOfSatisfying(com.localdeals.platform.exception.ApiStatusException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(429);
                    assertThat(error.getCode()).isEqualTo(com.localdeals.platform.exception.ApiErrorCodes.SECKILL_BUSY);
                });
        verifyNoInteractions(orderIdGenerator, admissionService, producer);
    }

    @Test
    void theRemainingStockReportedByRedisSizesTheBucket() {
        admissionReturns(SeckillAdmissionService.ACCEPTED);

        service.seckillVoucher(17L, "203.0.113.9");

        verify(bucket).observe(17L, BUCKET, 5L);
    }

    @Test
    void outOfStockFlagsTheVoucherSoldOutForTheWholeCluster() {
        admissionReturns(SeckillAdmissionService.OUT_OF_STOCK);

        service.seckillVoucher(17L, "203.0.113.9");

        verify(soldOut).markSoldOut(17L, BUCKET);
    }

    @Test
    void takingTheLastUnitFlagsSoldOutRightAway() {
        when(admissionService.admit(17L, 23L, LARGE_ORDER_ID, "203.0.113.9"))
                .thenReturn(new SeckillAdmissionService.Admission(SeckillAdmissionService.ACCEPTED, 0L));

        service.seckillVoucher(17L, "203.0.113.9");

        verify(soldOut).markSoldOut(17L, BUCKET);
    }

    @Test
    void aProbeThatFindsStockClearsAStaleFlag() {
        when(soldOut.isSoldOut(17L, BUCKET)).thenReturn(true);
        admissionReturns(SeckillAdmissionService.ACCEPTED);

        service.seckillVoucher(17L, "203.0.113.9");

        verify(soldOut).clear(17L, BUCKET);
        verify(soldOut, org.mockito.Mockito.never()).markSoldOut(17L, BUCKET);
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
        TradeOrder persisted = order(LARGE_ORDER_ID, 23L, 17L);
        when(tradeOrderMapper.selectById(LARGE_ORDER_ID)).thenReturn(persisted);
        when(stateService.markSuccess(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        Result result = service.querySeckillOrderStatus(LARGE_ORDER_ID);

        assertThat(result.getSuccess()).isTrue();
        SeckillOrderStatusDTO dto = (SeckillOrderStatusDTO) result.getData();
        assertThat(dto.getStatus()).isEqualTo(SeckillOrderStateService.STATUS_SUCCESS);
        assertThat(dto.getOrderId()).isEqualTo(Long.toString(LARGE_ORDER_ID));
        assertThat(dto.getOrderStatus()).isEqualTo("PENDING_PAY");
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
        TradeOrder persisted = order(LARGE_ORDER_ID, 23L, 17L);
        when(tradeOrderMapper.selectById(LARGE_ORDER_ID)).thenReturn(persisted);

        SeckillOrderPersistenceResult result =
                service.classifyPersistence(LARGE_ORDER_ID, 23L, 17L);

        assertThat(result.getType()).isEqualTo(SeckillOrderPersistenceResult.Type.EXACT_MATCH);
        assertThat(result.getPersistedOrderId()).isEqualTo(LARGE_ORDER_ID);
    }

    @Test
    void classifyPersistence_detectsOrderIdOwnedByAnotherReservation() {
        TradeOrder persisted = order(LARGE_ORDER_ID, 99L, 88L);
        when(tradeOrderMapper.selectById(LARGE_ORDER_ID)).thenReturn(persisted);

        SeckillOrderPersistenceResult result =
                service.classifyPersistence(LARGE_ORDER_ID, 23L, 17L);

        assertThat(result.getType()).isEqualTo(SeckillOrderPersistenceResult.Type.ORDER_ID_CONFLICT);
        assertThat(result.getPersistedUserId()).isEqualTo(99L);
        assertThat(result.getPersistedVoucherId()).isEqualTo(88L);
    }

    @Test
    void classifyPersistence_detectsDifferentOrderForSameUserAndVoucher() {
        when(tradeOrderMapper.selectById(LARGE_ORDER_ID)).thenReturn(null);
        when(tradeOrderMapper.selectActive(23L, 17L)).thenReturn(order(777L, 23L, 17L));

        SeckillOrderPersistenceResult result =
                service.classifyPersistence(LARGE_ORDER_ID, 23L, 17L);

        assertThat(result.getType())
                .isEqualTo(SeckillOrderPersistenceResult.Type.USER_VOUCHER_CONFLICT);
        assertThat(result.getPersistedOrderId()).isEqualTo(777L);
    }

    @Test
    void classifyPersistence_reportsAbsentOnlyAfterBothWriterQueriesAreEmpty() {
        when(tradeOrderMapper.selectById(LARGE_ORDER_ID)).thenReturn(null);
        when(tradeOrderMapper.selectActive(23L, 17L)).thenReturn(null);

        SeckillOrderPersistenceResult result =
                service.classifyPersistence(LARGE_ORDER_ID, 23L, 17L);

        assertThat(result.getType()).isEqualTo(SeckillOrderPersistenceResult.Type.ABSENT);
    }

    @Test
    void classifyPersistence_doesNotConvertDatabaseFailureToAbsence() {
        when(tradeOrderMapper.selectById(LARGE_ORDER_ID))
                .thenThrow(new IllegalStateException("writer unavailable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.classifyPersistence(LARGE_ORDER_ID, 23L, 17L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("writer unavailable");
    }

    private static TradeOrder order(Long orderId, Long userId, Long voucherId) {
        TradeOrder order = new TradeOrder();
        order.setOrderNo(orderId);
        ReflectionTestUtils.setField(order, "status", com.localdeals.trade.entity.OrderStatus.PENDING_PAY);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        return order;
    }
}
