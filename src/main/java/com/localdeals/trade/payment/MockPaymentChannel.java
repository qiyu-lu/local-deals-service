package com.localdeals.trade.payment;

import com.localdeals.trade.config.PaymentProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * In-process stand-in for a payment channel. Like a real one it answers asynchronously, signs
 * its callbacks, delivers each at least once (here: {@code duplicates} times), retries on
 * failure with exponential backoff, and makes no ordering promise (random jitter per delivery).
 * State is in memory: a restart forgets unpaid payments, which prepay re-registers.
 */
@Slf4j
public class MockPaymentChannel implements PaymentChannel {

    /** How the channel reaches us; HTTP in the application, a fake in unit tests. */
    public interface Transport {
        /** @return true when we acknowledged (HTTP 2xx). */
        boolean deliverPayment(PaymentNotification notification);

        boolean deliverRefund(RefundNotification notification);
    }

    private static final long MAX_BACKOFF_MS = 60_000L;

    private final PaymentSigner signer;
    private final Transport transport;
    private final PaymentProperties.MockChannel settings;
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final Map<String, String> refunds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mock-payment-channel");
        thread.setDaemon(true);
        return thread;
    });

    public MockPaymentChannel(PaymentSigner signer, Transport transport, PaymentProperties.MockChannel settings) {
        this.signer = signer;
        this.transport = transport;
        this.settings = settings;
    }

    @Override
    public String createPayment(String payNo, long amount) {
        payments.putIfAbsent(payNo, new Payment(amount));
        return "/mock-channel/payments/" + payNo + "/pay";
    }

    /** The user pays at the channel's cashier. Returns the channel transaction number. */
    public String pay(String payNo) {
        Payment payment = payments.get(payNo);
        if (payment == null) {
            throw new IllegalArgumentException("Unknown payment " + payNo);
        }
        if (payment.paid.compareAndSet(false, true)) {
            PaymentNotification notification =
                    new PaymentNotification(payNo, payment.channelTxnNo, payment.amount, null);
            notification.setSign(signer.sign(notification.signedFields()));
            deliverAtLeastOnce(notification, transport::deliverPayment);
        }
        return payment.channelTxnNo;
    }

    @Override
    public void refund(String refundNo, String payNo, long amount) {
        String channelRefundNo = refunds.computeIfAbsent(refundNo, ignored -> "MOCKR" + compactUuid());
        RefundNotification notification = new RefundNotification(refundNo, channelRefundNo, amount, null);
        notification.setSign(signer.sign(notification.signedFields()));
        // Asking again re-sends the result, which is how a lost refund callback gets recovered.
        deliverAtLeastOnce(notification, transport::deliverRefund);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private <T> void deliverAtLeastOnce(T notification, Predicate<T> send) {
        for (int copy = 0; copy < settings.getDuplicates(); copy++) {
            schedule(notification, send, 1, jitterMs());
        }
    }

    private <T> void schedule(T notification, Predicate<T> send, int attempt, long delayMs) {
        scheduler.schedule(() -> {
            if (send.test(notification) || attempt >= settings.getMaxAttempts()) {
                return;
            }
            long backoff = Math.min(MAX_BACKOFF_MS, settings.getInitialBackoff().toMillis() << (attempt - 1));
            schedule(notification, send, attempt + 1, backoff + jitterMs());
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private long jitterMs() {
        long jitter = settings.getJitter().toMillis();
        return jitter <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitter + 1);
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static final class Payment {
        private final long amount;
        private final String channelTxnNo = "MOCK" + compactUuid();
        private final AtomicBoolean paid = new AtomicBoolean();

        private Payment(long amount) {
            this.amount = amount;
        }
    }
}
