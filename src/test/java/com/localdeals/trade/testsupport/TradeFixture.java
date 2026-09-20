package com.localdeals.trade.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * One merchant, shop, seckill voucher and stock row with ids derived from {@code base}, so each
 * M2 integration test owns disjoint rows and can delete everything it created.
 */
public final class TradeFixture {

    public static final long PAY_VALUE = 990L;

    public final long merchantId;
    public final long shopId;
    public final long voucherId;
    private final JdbcTemplate jdbc;

    public TradeFixture(JdbcTemplate jdbc, long base) {
        this.jdbc = jdbc;
        this.merchantId = base;
        this.shopId = base;
        this.voucherId = base;
    }

    public TradeFixture create(int stock) {
        delete();
        jdbc.update("INSERT INTO tb_merchant (id, code, name, status) VALUES (?, ?, ?, 1)",
                merchantId, "IT_M2_" + merchantId, "M2 IT merchant " + merchantId);
        jdbc.update("INSERT INTO tb_shop (id, merchant_id, name, type_id, images, address, x, y, " +
                        "sold, comments, score) VALUES (?, ?, ?, 1, 'it.jpg', 'it address', 120.1, 30.2, 0, 0, 0)",
                shopId, merchantId, "M2 IT shop " + shopId);
        jdbc.update("INSERT INTO tb_voucher (id, shop_id, title, pay_value, actual_value, type, status) " +
                "VALUES (?, ?, 'M2 IT voucher', ?, 1000, 1, 1)", voucherId, shopId, PAY_VALUE);
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) " +
                "VALUES (?, ?, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 1 HOUR)", voucherId, stock);
        return this;
    }

    /**
     * An order number for {@code userId}, one per {@code seed}.
     *
     * <p>The low ten bits repeat {@code user_id % 1024}, which is what the Snowflake generator
     * does and what the sharding rules require: an insert names both order_no and user_id, and
     * ShardingSphere refuses a row whose two keys would route to different databases. A test
     * that makes up an order number has to make up a real one.</p>
     */
    public static long orderNo(long seed, long userId) {
        // The seed keeps its low 52 bits so that consecutive seeds stay distinct after the
        // shift; fixtures that number their orders from 9e17 would otherwise overflow into a
        // negative, which BIGINT UNSIGNED rejects.
        return ((seed & ((1L << 52) - 1)) << 10) | Math.floorMod(userId, 1024L);
    }

    /**
     * A PAID order for fixtures that only need one to exist. The insert cannot select from
     * tb_voucher any more — trade_order is sharded and the voucher lives in one database — so
     * the snapshot is read first, and the order number is derived so it carries the user's gene.
     */
    public static long insertPaidOrder(JdbcTemplate jdbc, long seed, long userId, long voucherId) {
        Map<String, Object> voucher = jdbc.queryForMap(
                "SELECT v.shop_id AS shopId, s.merchant_id AS merchantId, v.pay_value AS payValue " +
                        "FROM tb_voucher v JOIN tb_shop s ON s.id = v.shop_id WHERE v.id = ?", voucherId);
        long orderNo = orderNo(seed, userId);
        jdbc.update("INSERT INTO trade_order(order_no,user_id,voucher_id,shop_id,merchant_id,amount," +
                        "status,expire_at) VALUES (?,?,?,?,?,?,'PAID',NOW(3))",
                orderNo, userId, voucherId, voucher.get("shopId"), voucher.get("merchantId"),
                voucher.get("payValue"));
        return orderNo;
    }

    public int dbStock() {
        return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class, voucherId);
    }

    public void expire(long orderNo) {
        jdbc.update("UPDATE trade_order SET expire_at = NOW(3) - INTERVAL 1 SECOND WHERE order_no = ?", orderNo);
    }

    public String orderStatus(long orderNo) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE order_no = ?", String.class, orderNo);
    }

    public void delete() {
        // The attached rows used to be deleted through a join and a subquery on trade_order.
        // A sharded table supports neither: the rows of one statement live in different
        // databases. Read the order numbers first, then delete each attached row by the key that
        // routes it.
        for (Long orderNo : orderNos()) {
            deleteIfPresent("DELETE FROM order_state_log WHERE order_no = ?", orderNo);
            deleteIfPresent("DELETE FROM refund_record WHERE order_no = ?", orderNo);
            deleteIfPresent("DELETE FROM payment_record WHERE order_no = ?", orderNo);
        }
        deleteIfPresent("DELETE FROM user_coupon WHERE voucher_id = ?", voucherId);
        deleteIfPresent("DELETE FROM admin_audit_log WHERE merchant_id = ?", merchantId);
        deleteIfPresent("DELETE FROM trade_order WHERE voucher_id = ?", voucherId);
        jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
        jdbc.update("DELETE FROM tb_voucher WHERE id = ?", voucherId);
        jdbc.update("DELETE FROM tb_shop WHERE id = ?", shopId);
        jdbc.update("DELETE FROM tb_merchant WHERE id = ?", merchantId);
    }

    /** Every order of this fixture's voucher; empty before the M2 tables exist. */
    private List<Long> orderNos() {
        try {
            return jdbc.queryForList("SELECT order_no FROM trade_order WHERE voucher_id = ?",
                    Long.class, voucherId);
        } catch (org.springframework.jdbc.BadSqlGrammarException tableNotMigratedYet) {
            return List.of();
        }
    }

    private void deleteIfPresent(String sql, Object... args) {
        try {
            jdbc.update(sql, args);
        } catch (org.springframework.jdbc.BadSqlGrammarException tableNotMigratedYet) {
            // The red commit runs before the M2 tables exist.
        }
    }
}
