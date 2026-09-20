package com.localdeals.trade.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trade_order lives in two databases as four tables each, and each database is migrated by its
 * own Flyway: {@code db/migration} builds ds_0 and {@code db/shard} builds ds_1. A column added
 * to one of them and forgotten in the other is invisible until a buyer whose user id is odd
 * tries to order — which is half of them, on a machine nobody is watching.
 */
class TraceColumnContractTest {

    private static final Path PRIMARY = Paths.get("src/main/resources/db/migration");
    private static final Path SECOND = Paths.get("src/main/resources/db/shard");
    private static final Set<String> ALL_TABLES =
            Set.of("trade_order_0", "trade_order_1", "trade_order_2", "trade_order_3");

    /** A table gets the column either at creation or by a later ALTER; both count. */
    private static final Pattern CREATED_WITH_TRACE = Pattern.compile(
            "CREATE TABLE `(trade_order_[0-9]+)` \\((?:(?!\\n\\) ENGINE).)*?`trace_id`", Pattern.DOTALL);
    private static final Pattern ALTERED_WITH_TRACE = Pattern.compile(
            "ALTER TABLE `(trade_order_[0-9]+)`[^;]*ADD COLUMN `trace_id`", Pattern.DOTALL);

    @Test
    void theFirstOrderDatabaseHasTheTraceColumnOnAllFourTables() throws IOException {
        assertThat(tablesWithTrace(PRIMARY)).isEqualTo(ALL_TABLES);
    }

    @Test
    void theSecondOrderDatabaseHasItToo() throws IOException {
        assertThat(tablesWithTrace(SECOND)).isEqualTo(ALL_TABLES);
    }

    /** Both order-creating statements fill it: the batch fast path and its one-by-one fallback. */
    @Test
    void bothInsertStatementsWriteTheTrace() throws IOException {
        String mapper = read(Paths.get("src/main/java/com/localdeals/trade/mapper/TradeOrderMapper.java"));

        assertThat(mapper).contains("#{traceId}").contains("#{row.traceId}");
    }

    private static Set<String> tablesWithTrace(Path location) throws IOException {
        Set<String> tables = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(location)) {
            List<Path> sql = files.filter(path -> path.toString().endsWith(".sql")).sorted().toList();
            for (Path file : sql) {
                String text = read(file);
                for (Pattern pattern : List.of(CREATED_WITH_TRACE, ALTERED_WITH_TRACE)) {
                    Matcher match = pattern.matcher(text);
                    while (match.find()) {
                        tables.add(match.group(1));
                    }
                }
            }
        }
        return tables;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
