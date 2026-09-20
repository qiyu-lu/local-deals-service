package com.localdeals.trade.sharding;

import com.localdeals.trade.utils.SnowflakeOrderIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class OrderShardSlotsTest {

    private static final long NOW = Instant.parse("2026-09-20T12:00:00Z").toEpochMilli();

    /** A millisecond per id: the sequence is seven bits, and a clock that never moves spins. */
    private final AtomicLong clock = new AtomicLong(NOW);
    private final SnowflakeOrderIdGenerator generator =
            new SnowflakeOrderIdGenerator(() -> 7, clock::incrementAndGet);

    @Test
    void anOrderNumberLandsInTheSameSlotAsTheUserWhoPlacedIt() {
        for (long userId = 0; userId < 4096; userId++) {
            long orderNo = generator.nextId(userId);
            assertThat(OrderShardSlots.ofOrderNo(orderNo))
                    .as("order %s of user %s", orderNo, userId)
                    .isEqualTo(OrderShardSlots.ofUserId(userId));
        }
    }

    @Test
    void theSlotsCoverEveryDatabaseAndTableExactlyOnce() {
        Set<String> nodes = new HashSet<>();
        for (int slot = 0; slot < OrderShardSlots.SLOTS; slot++) {
            int database = OrderShardSlots.databaseOf(slot);
            int table = OrderShardSlots.tableOf(slot);
            assertThat(database).isBetween(0, OrderShardSlots.DATABASES - 1);
            assertThat(table).isBetween(0, OrderShardSlots.TABLES_PER_DATABASE - 1);
            nodes.add(database + ":" + table);
        }
        assertThat(nodes).hasSize(OrderShardSlots.SLOTS);
    }

    @Test
    void theSlotCountDividesTheGeneSoTheGeneCanAlwaysProduceIt() {
        assertThat(1024 % OrderShardSlots.SLOTS).isZero();
    }

    @Test
    void everyNumberDerivedFromAnOrderCarriesTheOrdersSlot() {
        long orderNo = generator.nextId(4321L);
        int slot = OrderShardSlots.ofOrderNo(orderNo);
        String payNo = orderNo + "-1";

        assertThat(OrderShardSlots.ofPayNo(payNo)).hasValue(slot);
        assertThat(OrderShardSlots.ofRefundNo("RU" + orderNo)).hasValue(slot);
        assertThat(OrderShardSlots.ofRefundNo("RA" + payNo)).hasValue(slot);
        assertThat(OrderShardSlots.ofCouponNo("P" + orderNo)).hasValue(slot);
    }

    @Test
    void aGrantCouponNumberHasNoUserGeneAndSaysSo() {
        assertThat(OrderShardSlots.ofCouponNo("G91")).isEmpty();
    }

    @Test
    void anUnreadableReferenceRoutesNowhereRatherThanThrowing() {
        assertThat(OrderShardSlots.ofPayNo(null)).isEmpty();
        assertThat(OrderShardSlots.ofPayNo("")).isEmpty();
        assertThat(OrderShardSlots.ofPayNo("not-a-number-1")).isEmpty();
        assertThat(OrderShardSlots.ofRefundNo("RX123")).isEmpty();
        assertThat(OrderShardSlots.ofRefundNo("RA")).isEmpty();
        assertThat(OrderShardSlots.ofCouponNo("P99999999999999999999999")).isEmpty();
    }

    @Test
    void anyUserIdLandsInARealSlot() {
        for (long userId : new long[]{0L, 1L, -9L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertThat(OrderShardSlots.ofUserId(userId))
                    .as("user %s", userId)
                    .isBetween(0, OrderShardSlots.SLOTS - 1);
        }
    }

    @Test
    void anOrderNumberIsPositiveSoANegativeOneIsNotAReadableReference() {
        assertThat(OrderShardSlots.ofPayNo("-9-1")).isEmpty();
        assertThat(OrderShardSlots.ofCouponNo("P-9")).isEmpty();
    }
}
