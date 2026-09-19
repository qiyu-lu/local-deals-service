package com.localdeals.trade.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** "No scattered setStatus": trade_order.status is written by OrderStateMachine only. */
class OrderStatusWriteContractTest {

    private static final Path MAIN = Paths.get("src/main");
    private static final Pattern TRANSITION_CALL = Pattern.compile("\\.transition\\(");
    private static final Pattern RAW_STATUS_UPDATE =
            Pattern.compile("UPDATE\\s+trade_order\\s+SET\\s+((?!WHERE)[^\"])*\\bstatus\\s*=",
                    Pattern.CASE_INSENSITIVE);

    @Test
    void onlyTheStateMachineCallsTheTransitionStatement() throws IOException {
        assertThat(filesMatching(TRANSITION_CALL))
                .containsExactly("java/com/localdeals/trade/service/OrderStateMachine.java");
    }

    @Test
    void onlyTheMapperTransitionStatementUpdatesTheStatusColumn() throws IOException {
        assertThat(filesMatching(RAW_STATUS_UPDATE))
                .containsExactly("java/com/localdeals/trade/mapper/TradeOrderMapper.java");
    }

    private List<String> filesMatching(Pattern pattern) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".xml"))
                    .filter(path -> contains(path, pattern))
                    .map(path -> MAIN.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static boolean contains(Path path, Pattern pattern) {
        try {
            return pattern.matcher(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).find();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
