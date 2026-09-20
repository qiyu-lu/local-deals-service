package com.localdeals.trade.sharding;

import com.google.common.collect.Range;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;
import org.apache.shardingsphere.sharding.spi.ShardingAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class OrderShardingAlgorithmTest {

    private static final List<String> DATABASES = List.of("ds_0", "ds_1");
    private static final List<String> TABLES =
            List.of("trade_order_0", "trade_order_1", "trade_order_2", "trade_order_3");

    private final OrderDatabaseShardingAlgorithm databases = new OrderDatabaseShardingAlgorithm();
    private final OrderTableShardingAlgorithm tables = new OrderTableShardingAlgorithm();

    @Test
    void aUserIdPicksOneDatabaseAndOneTable() {
        long userId = 5L; // slot 5 -> database 1, table 2

        assertThat(databases.doSharding(DATABASES, values(Map.of("user_id", List.of(userId)))))
                .containsExactly("ds_1");
        assertThat(tables.doSharding(TABLES, values(Map.of("user_id", List.of(userId)))))
                .containsExactly("trade_order_2");
    }

    @Test
    void anOrderNumberReachesTheSameOneAsItsUser() {
        long userId = 4321L;
        long orderNo = (1L << 60) | (userId % 1024); // the gene M3 puts in the low ten bits

        assertThat(OrderShardSlots.ofOrderNo(orderNo)).isEqualTo(OrderShardSlots.ofUserId(userId));
        assertThat(databases.doSharding(DATABASES, values(Map.of("order_no", List.of(orderNo)))))
                .isEqualTo(databases.doSharding(DATABASES, values(Map.of("user_id", List.of(userId)))));
        assertThat(tables.doSharding(TABLES, values(Map.of("order_no", List.of(orderNo)))))
                .isEqualTo(tables.doSharding(TABLES, values(Map.of("user_id", List.of(userId)))));
    }

    @Test
    void severalValuesReachSeveralShardsButNotAllOfThem() {
        Collection<String> targets =
                tables.doSharding(TABLES, values(Map.of("user_id", List.of(0L, 2L))));

        assertThat(targets).containsExactlyInAnyOrder("trade_order_0", "trade_order_1");
    }

    @Test
    void aNumberDerivedFromAnOrderRoutesLikeThatOrder() {
        long orderNo = (1L << 60) | 6L;
        Collection<String> expected = tables.doSharding(TABLES, values(Map.of("order_no", List.of(orderNo))));

        assertThat(tables.doSharding(TABLES, values(Map.of("pay_no", List.of(orderNo + "-1")))))
                .isEqualTo(expected);
        assertThat(tables.doSharding(TABLES, values(Map.of("refund_no", List.of("RU" + orderNo)))))
                .isEqualTo(expected);
        assertThat(tables.doSharding(TABLES, values(Map.of("coupon_no", List.of("P" + orderNo)))))
                .isEqualTo(expected);
    }

    @Test
    void aValueWithoutAGeneBroadcastsRatherThanGuessing() {
        assertThat(tables.doSharding(TABLES, values(Map.of("coupon_no", List.of("G91")))))
                .containsExactlyElementsOf(TABLES);
        assertThat(databases.doSharding(DATABASES, values(Map.of("coupon_no", List.of("G91")))))
                .containsExactlyElementsOf(DATABASES);
    }

    @Test
    void oneUnreadableValueBroadcastsTheWholeStatement() {
        Collection<String> targets =
                tables.doSharding(TABLES, values(Map.of("coupon_no", List.of("P" + (1L << 60), "G91"))));

        assertThat(targets).containsExactlyElementsOf(TABLES);
    }

    /**
     * Every column of one statement describes the same row, so a column that carries no slot
     * only fails to narrow — it does not widen. A grant coupon is exactly this: its coupon_no
     * has no gene, and the insert next to it names user_id.
     */
    @Test
    void aColumnWithoutASlotDefersToOneThatHasIt() {
        long userId = 5L;
        Collection<String> byUserAlone = tables.doSharding(TABLES, values(Map.of("user_id", List.of(userId))));

        Collection<String> withGrantCoupon = tables.doSharding(TABLES, values(Map.of(
                "user_id", List.of(userId), "coupon_no", List.of("G91"))));

        assertThat(withGrantCoupon).isEqualTo(byUserAlone).containsExactly("trade_order_2");
    }

    /** Two keys of one row that name different slots is a bug; the answer must not be one of them. */
    @Test
    void twoColumnsThatDisagreeBroadcastRatherThanPickASide() {
        Collection<String> targets = tables.doSharding(TABLES, values(Map.of(
                "user_id", List.of(5L), "order_no", List.of((1L << 60) | 2L))));

        assertThat(targets).containsExactlyElementsOf(TABLES);
    }

    @Test
    void aRangeBroadcastsBecauseASlotIsNotAnOrdering() {
        Range<Comparable<?>> span = Range.closed(cast(1L), cast(9L));
        ComplexKeysShardingValue<Comparable<?>> between =
                new ComplexKeysShardingValue<>("trade_order", Map.of(), Map.of("user_id", span));

        assertThat(tables.doSharding(TABLES, between)).containsExactlyElementsOf(TABLES);
    }

    @Test
    void aStatementNamingNoShardingColumnBroadcasts() {
        assertThat(tables.doSharding(TABLES, values(Map.of()))).containsExactlyElementsOf(TABLES);
        assertThat(tables.doSharding(TABLES, values(Map.of("verify_code", List.of("ABCD1234ABCD1234")))))
                .containsExactlyElementsOf(TABLES);
    }

    @Test
    void bothAlgorithmsAreDiscoverableUnderTheNamesTheRulesUse() {
        List<String> types = StreamSupport
                .stream(ServiceLoader.load(ShardingAlgorithm.class).spliterator(), false)
                .map(algorithm -> String.valueOf(algorithm.getType()))
                .collect(Collectors.toList());

        assertThat(types).contains(OrderDatabaseShardingAlgorithm.TYPE, OrderTableShardingAlgorithm.TYPE);
    }

    private static ComplexKeysShardingValue<Comparable<?>> values(Map<String, List<? extends Comparable<?>>> columns) {
        Map<String, Collection<Comparable<?>>> shardingValues = columns.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(OrderShardingAlgorithmTest::cast)
                                .collect(Collectors.toList())));
        return new ComplexKeysShardingValue<>("trade_order", shardingValues, Map.of());
    }

    private static Comparable<?> cast(Object value) {
        return (Comparable<?>) value;
    }
}
