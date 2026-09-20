package com.localdeals.trade.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trade_order lives in two databases as four tables each, and each database is migrated by its
 * own Flyway (db/migration builds ds_0, db/shard builds ds_1). A column added to one of them and
 * forgotten in the other is invisible until a buyer whose user id is odd tries to order — which
 * is half of them, on a machine nobody is watching. So the column is checked in all eight.
 */
class TraceColumnContractTest {

    private static final Path MIGRATIONS = Paths.get("src/main/resources/db");
    private static final Pattern ORDER_TABLE = Pattern.compile(
            "CREATE TABLE `(trade_order_[0-9]+)` \\((.*?)\n\\) ENGINE", Pattern.DOTALL);

    @Test
    void everyPhysicalOrderTableHasTheTraceColumn() throws IOException {
        List<String> without = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MIGRATIONS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).toList()) {
                String sql = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher table = ORDER_TABLE.matcher(sql);
                while (table.find()) {
                    if (!table.group(2).contains("`trace_id`")) {
                        without.add(file.getFileName() + ":" + table.group(1));
                    }
                }
            }
        }
        assertThat(without).isEmpty();
    }

    /** Both order-creating statements fill it: the batch fast path and its one-by-one fallback. */
    @Test
    void bothInsertStatementsWriteTheTrace() throws IOException {
        String mapper = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/localdeals/trade/mapper/TradeOrderMapper.java")),
                StandardCharsets.UTF_8);

        assertThat(countOccurrences(mapper, "trace_id")).isGreaterThanOrEqualTo(2);
        assertThat(mapper).contains("#{traceId}").contains("#{row.traceId}");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
