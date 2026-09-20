package com.localdeals.trade.sharding;

import com.localdeals.trade.entity.TradeOrder;
import com.localdeals.trade.exception.OrderReservationConflictException;
import com.localdeals.trade.mapper.TradeOrderMapper;
import com.localdeals.trade.mq.SeckillOrderMessage;
import com.localdeals.trade.service.IVoucherOrderService;
import com.localdeals.trade.service.SeckillOrderBatchPersister;
import com.localdeals.trade.testsupport.TradeFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where a sharded row actually lands, read straight out of the two physical databases rather
 * than through the routing layer that put it there.
 *
 * <p>The claim under test is the one the whole design rests on: a row reached by user_id and the
 * same row reached by the order number the user was handed are in one table, because M3 put
 * {@code user_id % 1024} in the low bits of every order number and 8 divides 1024.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderShardRoutingIT {

    private static final long BASE = 9_209_000L;
    /** A multiple of SLOTS, so USERS + n is the user whose slot is n. */
    private static final long USERS = 9_209_000L;

    @Autowired
    private IVoucherOrderService orderService;
    @Autowired
    private TradeOrderMapper orderMapper;
    @Autowired
    private SeckillOrderBatchPersister persister;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    @Qualifier("orderDatabase0")
    private DataSource database0;
    @Autowired
    @Qualifier("orderDatabase1")
    private DataSource database1;

    private TradeFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new TradeFixture(jdbc, BASE).create(64);
    }

    @AfterEach
    void tearDown() {
        fixture.delete();
    }

    @Test
    void theSlotSchemeIsAMultipleOfNothingLeftToChance() {
        assertThat(USERS % OrderShardSlots.SLOTS).isZero();
    }

    @Test
    void anOrderAndItsAuditRowLandInTheOneTableTheSchemeNames() {
        for (int slot = 0; slot < OrderShardSlots.SLOTS; slot++) {
            long userId = USERS + slot;
            long orderNo = TradeFixture.orderNo(BASE + 10 + slot, userId);
            assertThat(OrderShardSlots.ofUserId(userId)).isEqualTo(slot);
            assertThat(OrderShardSlots.ofOrderNo(orderNo)).isEqualTo(slot);

            orderService.createPendingOrder(new SeckillOrderMessage(fixture.voucherId, userId, orderNo));

            String expected = "ds_" + OrderShardSlots.databaseOf(slot) + ".trade_order_"
                    + OrderShardSlots.tableOf(slot);
            assertThat(nodesHolding("trade_order", "order_no", orderNo))
                    .as("order of user %s", userId)
                    .containsExactly(expected);
            assertThat(nodesHolding("order_state_log", "order_no", orderNo))
                    .as("audit row of order %s", orderNo)
                    .containsExactly(expected.replace("trade_order_", "order_state_log_"));
        }
    }

    @Test
    void bothWaysOfNamingAnOrderFindTheSameRow() {
        long userId = USERS + 3;
        long orderNo = TradeFixture.orderNo(BASE + 30, userId);

        orderService.createPendingOrder(new SeckillOrderMessage(fixture.voucherId, userId, orderNo));

        TradeOrder byOrderNo = orderMapper.selectById(orderNo);
        TradeOrder byUser = orderMapper.selectActive(userId, fixture.voucherId);
        assertThat(byOrderNo).isNotNull();
        assertThat(byUser).isNotNull();
        assertThat(byUser.getOrderNo()).isEqualTo(byOrderNo.getOrderNo());
        assertThat(orderMapper.selectByUser(userId, 10)).extracting(TradeOrder::getOrderNo).contains(orderNo);
    }

    /**
     * The purchase limit is uk(user_id, voucher_id, active_flag), which is now only unique
     * inside one table. It still means what it meant because a user's rows never leave that
     * table — and it has to keep letting other users through.
     */
    @Test
    void thePurchaseLimitBindsInsideAShardAndNowhereElse() {
        long userId = USERS + 5;
        orderService.createPendingOrder(new SeckillOrderMessage(
                fixture.voucherId, userId, TradeFixture.orderNo(BASE + 40, userId)));

        assertThatThrownBy(() -> orderService.createPendingOrder(new SeckillOrderMessage(
                fixture.voucherId, userId, TradeFixture.orderNo(BASE + 41, userId))))
                .isInstanceOf(OrderReservationConflictException.class);

        for (int slot = 0; slot < OrderShardSlots.SLOTS; slot++) {
            long other = USERS + 100 + slot;
            orderService.createPendingOrder(new SeckillOrderMessage(
                    fixture.voucherId, other, TradeFixture.orderNo(BASE + 50 + slot, other)));
        }
        assertThat(countEverywhere("trade_order", "voucher_id", fixture.voucherId)).isEqualTo(9);
    }

    /**
     * One consumer batch is one transaction across both databases, and that is worth stating
     * out loud: ShardingSphere's LOCAL transaction commits each physical connection in turn, so
     * a process that dies between the two commits leaves half a batch. The half that is missing
     * still holds its Redis reservation, so the reconciler republishes it and INSERT IGNORE
     * makes the replay a no-op — but the stock decrement for those rows has already happened
     * and happens again on replay. The error is bounded by the batch size and it is always in
     * the safe direction: stock reads lower than it is, never higher.
     */
    @Test
    void oneBatchOfOrdersIsOneTransactionOverBothDatabases() {
        List<SeckillOrderMessage> batch = new ArrayList<>();
        for (int slot = 0; slot < OrderShardSlots.SLOTS; slot++) {
            long userId = USERS + 200 + slot;
            batch.add(new SeckillOrderMessage(
                    fixture.voucherId, userId, TradeFixture.orderNo(BASE + 60 + slot, userId)));
        }

        persister.persistGroup(fixture.voucherId, batch);

        assertThat(countIn(database0, "trade_order", "voucher_id", fixture.voucherId)).isEqualTo(4);
        assertThat(countIn(database1, "trade_order", "voucher_id", fixture.voucherId)).isEqualTo(4);
        assertThat(fixture.dbStock()).isEqualTo(64 - OrderShardSlots.SLOTS);
    }

    /** The physical nodes that hold this row, named as the rules name them. */
    private List<String> nodesHolding(String table, String column, long value) {
        List<String> nodes = new ArrayList<>();
        for (int database = 0; database < OrderShardSlots.DATABASES; database++) {
            for (int index = 0; index < OrderShardSlots.TABLES_PER_DATABASE; index++) {
                DataSource source = database == 0 ? database0 : database1;
                if (count(source, table + "_" + index, column, value) > 0) {
                    nodes.add("ds_" + database + "." + table + "_" + index);
                }
            }
        }
        return nodes;
    }

    private int count(DataSource source, String physicalTable, String column, long value) {
        Integer rows = new JdbcTemplate(source).queryForObject(
                "SELECT COUNT(*) FROM " + physicalTable + " WHERE " + column + " = ?", Integer.class, value);
        return rows == null ? 0 : rows;
    }

    /** The same count over all eight physical tables, read outside the routing layer. */
    private int countEverywhere(String logicalTable, String column, long value) {
        return countIn(database0, logicalTable, column, value)
                + countIn(database1, logicalTable, column, value);
    }

    private int countIn(DataSource source, String logicalTable, String column, long value) {
        int total = 0;
        for (int index = 0; index < OrderShardSlots.TABLES_PER_DATABASE; index++) {
            total += count(source, logicalTable + "_" + index, column, value);
        }
        return total;
    }
}
