package com.localdeals.benchmark;

import com.localdeals.entity.User;
import com.localdeals.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.localdeals.utils.RedisConstants.LOGIN_USER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_META_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_ORDER_STATUS_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_PROCESSING_INDEX_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.localdeals.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * Local benchmark fixture tool.
 *
 * <p>Run individual methods with Maven, for example:
 * mvn -Dtest=BenchmarkDataTool#prepareBenchmarkUsersAndTokens test</p>
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
public class BenchmarkDataTool {

    private static final String DEFAULT_PHONE_PREFIX = "1990000";
    private static final String DEFAULT_TOKENS_FILE = "benchmark/tokens.csv";
    private static final String BENCHMARK_VOUCHER_TITLE = "[BENCHMARK] Seckill Voucher";
    private static final String STREAM_KEY = "stream.orders";
    private static final String GROUP_NAME = "g1";
    private static final String DEAD_LETTER_KEY = "stream.orders.dlq";
    private static final String RETRY_KEY_PATTERN = "seckill:stream:retry:*";

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Prepare benchmark users and Redis tokens")
    public void prepareBenchmarkUsersAndTokens() throws IOException {
        int userCount = userCount();
        String phonePrefix = phonePrefix();
        Path tokensFile = tokensFile();
        validatePhoneConfig(phonePrefix, userCount);

        Path parent = tokensFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        int created = 0;
        int reused = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(tokensFile, StandardCharsets.UTF_8)) {
            for (int i = 0; i < userCount; i++) {
                String phone = phoneForIndex(phonePrefix, i);
                User user = userService.lambdaQuery()
                        .eq(User::getPhone, phone)
                        .one();
                if (user == null) {
                    user = createBenchmarkUser(phone, i);
                    created++;
                } else {
                    reused++;
                }

                String token = tokenForUser(user);
                writeRedisToken(user, token);
                writer.write(token);
                writer.write(',');
                writer.write(String.valueOf(user.getId()));
                writer.write(',');
                writer.write(phone);
                writer.newLine();
            }
        }

        log.info(
                "Prepared benchmark users. created={}, reused={}, tokensFile={}",
                created,
                reused,
                tokensFile.toAbsolutePath()
        );
    }

    @Test
    @DisplayName("Reset seckill benchmark data")
    public void resetSeckillBenchmarkData() {
        int stock = stock();
        long voucherId = resolveVoucherId(stock);

        Set<Long> previousOrderIds = new LinkedHashSet<>(benchmarkOrderIds(voucherId));
        previousOrderIds.addAll(reservationOrderIds(voucherId));
        previousOrderIds.addAll(indexedProcessingOrderIds(voucherId));
        int deletedOrders = deleteBenchmarkOrders(voucherId);
        jdbcTemplate.update(
                "UPDATE tb_seckill_voucher " +
                        "SET stock = ?, begin_time = DATE_SUB(NOW(), INTERVAL 1 DAY), " +
                        "end_time = DATE_ADD(NOW(), INTERVAL 1 DAY) " +
                        "WHERE voucher_id = ?",
                stock,
                voucherId
        );

        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, String.valueOf(stock));
        stringRedisTemplate.delete(Arrays.asList(
                SECKILL_ORDER_KEY + voucherId,
                SECKILL_RESERVATION_KEY + voucherId,
                SECKILL_META_KEY + voucherId,
                STREAM_KEY,
                DEAD_LETTER_KEY));
        long now = Instant.now().getEpochSecond();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("status", "ACTIVE");
        metadata.put("beginAt", Long.toString(now - TimeUnit.DAYS.toSeconds(1)));
        metadata.put("endAt", Long.toString(now + TimeUnit.DAYS.toSeconds(1)));
        stringRedisTemplate.opsForHash().putAll(SECKILL_META_KEY + voucherId, metadata);
        Long removedProcessingEntries = 0L;
        if (!previousOrderIds.isEmpty()) {
            java.util.List<String> statusKeys = previousOrderIds.stream()
                    .map(id -> SECKILL_ORDER_STATUS_KEY + id)
                    .collect(java.util.stream.Collectors.toList());
            removedProcessingEntries = stringRedisTemplate.opsForZSet().remove(
                    SECKILL_PROCESSING_INDEX_KEY,
                    previousOrderIds.stream().map(String::valueOf).toArray());
            stringRedisTemplate.delete(statusKeys);
        }
        Set<String> retryKeys = stringRedisTemplate.keys(RETRY_KEY_PATTERN);
        if (retryKeys != null && !retryKeys.isEmpty()) {
            stringRedisTemplate.delete(retryKeys);
        }
        recreateStreamGroup();

        log.info(
                "Reset seckill benchmark data. voucherId={}, stock={}, deletedOrders={}, " +
                        "removedProcessingEntries={}",
                voucherId,
                stock,
                deletedOrders,
                removedProcessingEntries == null ? 0L : removedProcessingEntries
        );
    }

    @Test
    @DisplayName("List seckill vouchers")
    public void listSeckillVouchers() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT v.id, v.title, sv.stock, sv.begin_time, sv.end_time " +
                        "FROM tb_voucher v " +
                        "INNER JOIN tb_seckill_voucher sv ON v.id = sv.voucher_id " +
                        "ORDER BY v.id"
        );
        if (rows.isEmpty()) {
            log.info("No rows found in tb_seckill_voucher.");
            return;
        }
        for (Map<String, Object> row : rows) {
            log.info("Seckill voucher: {}", row);
        }
    }

    @Test
    @DisplayName("Cleanup benchmark users and Redis tokens")
    public void cleanupBenchmarkUsersAndTokens() {
        String phonePrefix = phonePrefix();
        List<User> users = userService.lambdaQuery()
                .likeRight(User::getPhone, phonePrefix)
                .list();

        int deletedOrders = jdbcTemplate.update(
                "DELETE vo FROM tb_voucher_order vo " +
                        "INNER JOIN tb_user u ON vo.user_id = u.id " +
                        "WHERE u.phone LIKE ?",
                phonePrefix + "%"
        );

        for (User user : users) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + tokenForUser(user));
        }
        if (!users.isEmpty()) {
            userService.removeByIds(extractUserIds(users));
        }

        log.info(
                "Cleaned benchmark users. phonePrefix={}, users={}, deletedOrders={}",
                phonePrefix,
                users.size(),
                deletedOrders
        );
    }

    private User createBenchmarkUser(String phone, int index) {
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setPhone(phone);
        user.setNickName("bench_user_" + index);
        user.setIcon("");
        user.setCreateTime(now);
        user.setUpdateTime(now);
        userService.save(user);
        return user;
    }

    private void writeRedisToken(User user, String token) {
        Map<String, String> userMap = new LinkedHashMap<>();
        userMap.put("id", String.valueOf(user.getId()));
        userMap.put("nickName", nullToEmpty(user.getNickName()));
        userMap.put("icon", nullToEmpty(user.getIcon()));

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, tokenTtlMinutes(), TimeUnit.MINUTES);
    }

    private int deleteBenchmarkOrders(long voucherId) {
        return jdbcTemplate.update(
                "DELETE vo FROM tb_voucher_order vo " +
                        "INNER JOIN tb_user u ON vo.user_id = u.id " +
                        "WHERE vo.voucher_id = ? AND u.phone LIKE ?",
                voucherId,
                phonePrefix() + "%"
        );
    }

    private List<Long> benchmarkOrderIds(long voucherId) {
        return jdbcTemplate.queryForList(
                "SELECT vo.id FROM tb_voucher_order vo " +
                        "INNER JOIN tb_user u ON vo.user_id = u.id " +
                        "WHERE vo.voucher_id = ? AND u.phone LIKE ?",
                Long.class,
                voucherId,
                phonePrefix() + "%"
        );
    }

    /**
     * Lazily scans reservation values so reset can remove status/index state even when the
     * asynchronous order never reached MySQL. Never materialize the potentially large Hash
     * through HGETALL/HVALS in this helper.
     */
    private Set<Long> reservationOrderIds(long voucherId) {
        Set<Long> orderIds = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions().count(500).build();
        try (Cursor<Map.Entry<Object, Object>> cursor = stringRedisTemplate.opsForHash()
                .scan(SECKILL_RESERVATION_KEY + voucherId, options)) {
            while (cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                Long orderId = parseLong(entry.getValue());
                if (orderId != null) {
                    orderIds.add(orderId);
                }
            }
        }
        return orderIds;
    }

    /** Finds orphan due-index members for this voucher without loading the global ZSET. */
    private Set<Long> indexedProcessingOrderIds(long voucherId) {
        Set<Long> orderIds = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions().count(500).build();
        try (Cursor<ZSetOperations.TypedTuple<String>> cursor = stringRedisTemplate.opsForZSet()
                .scan(SECKILL_PROCESSING_INDEX_KEY, options)) {
            while (cursor.hasNext()) {
                String member = cursor.next().getValue();
                Long orderId = parseLong(member);
                if (orderId == null) {
                    continue;
                }
                Object storedVoucherId = stringRedisTemplate.opsForHash().get(
                        SECKILL_ORDER_STATUS_KEY + orderId, "voucherId");
                if (storedVoucherId != null && Long.toString(voucherId).equals(storedVoucherId.toString())) {
                    orderIds.add(orderId);
                }
            }
        }
        return orderIds;
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long resolveVoucherId(int stock) {
        Long configuredVoucherId = Long.getLong("bench.voucherId");
        if (configuredVoucherId != null) {
            assertSeckillVoucherExists(configuredVoucherId);
            return configuredVoucherId;
        }
        return findOrCreateBenchmarkVoucher(stock);
    }

    private void assertSeckillVoucherExists(long voucherId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class,
                voucherId
        );
        if (count == null || count == 0) {
            throw new IllegalStateException(
                    "No seckill voucher found for voucherId=" + voucherId +
                            ". Run BenchmarkDataTool#listSeckillVouchers, or omit bench.voucherId " +
                            "to auto-create a benchmark seckill voucher."
            );
        }
    }

    private long findOrCreateBenchmarkVoucher(int stock) {
        List<Long> voucherIds = jdbcTemplate.queryForList(
                "SELECT id FROM tb_voucher WHERE title = ? AND type = 1 ORDER BY id LIMIT 1",
                Long.class,
                BENCHMARK_VOUCHER_TITLE
        );
        long voucherId;
        if (voucherIds.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO tb_voucher " +
                            "(shop_id, title, sub_title, rules, pay_value, actual_value, type, status) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    benchmarkShopId(),
                    BENCHMARK_VOUCHER_TITLE,
                    "Local benchmark only",
                    "Generated by BenchmarkDataTool",
                    100L,
                    100L,
                    1,
                    1
            );
            voucherId = jdbcTemplate.queryForObject(
                    "SELECT id FROM tb_voucher WHERE title = ? AND type = 1 ORDER BY id DESC LIMIT 1",
                    Long.class,
                    BENCHMARK_VOUCHER_TITLE
            );
            log.info("Created benchmark voucher. voucherId={}", voucherId);
        } else {
            voucherId = voucherIds.get(0);
            jdbcTemplate.update(
                    "UPDATE tb_voucher SET status = 1, update_time = NOW() WHERE id = ?",
                    voucherId
            );
        }

        Integer seckillRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class,
                voucherId
        );
        if (seckillRows == null || seckillRows == 0) {
            jdbcTemplate.update(
                    "INSERT INTO tb_seckill_voucher " +
                            "(voucher_id, stock, begin_time, end_time) " +
                            "VALUES (?, ?, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY))",
                    voucherId,
                    stock
            );
            log.info("Created benchmark seckill voucher row. voucherId={}, stock={}", voucherId, stock);
        }
        return voucherId;
    }

    private void recreateStreamGroup() {
        try {
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP",
                    raw("CREATE"),
                    raw(STREAM_KEY),
                    raw(GROUP_NAME),
                    raw("$"),
                    raw("MKSTREAM")
            ));
        } catch (RedisSystemException e) {
            String message = e.getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private byte[] raw(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private List<Long> extractUserIds(List<User> users) {
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>(users.size());
        for (User user : users) {
            ids.add(user.getId());
        }
        return ids;
    }

    private String phoneForIndex(String phonePrefix, int index) {
        int suffixWidth = 11 - phonePrefix.length();
        String pattern = "%0" + suffixWidth + "d";
        return phonePrefix + String.format(Locale.ROOT, pattern, index);
    }

    private void validatePhoneConfig(String phonePrefix, int userCount) {
        if (!phonePrefix.matches("\\d{1,10}")) {
            throw new IllegalArgumentException("bench.phonePrefix must be 1-10 digits");
        }
        int suffixWidth = 11 - phonePrefix.length();
        int maxUsers = (int) Math.pow(10, suffixWidth);
        if (userCount <= 0 || userCount > maxUsers) {
            throw new IllegalArgumentException(
                    "bench.userCount must be between 1 and " + maxUsers +
                            " for phonePrefix=" + phonePrefix
            );
        }
    }

    private String tokenForUser(User user) {
        return "bench-token-" + user.getId();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int userCount() {
        return Integer.getInteger("bench.userCount", 1000);
    }

    private String phonePrefix() {
        return System.getProperty("bench.phonePrefix", DEFAULT_PHONE_PREFIX);
    }

    private Path tokensFile() {
        return Paths.get(System.getProperty("bench.tokensFile", DEFAULT_TOKENS_FILE));
    }

    private int stock() {
        return Integer.getInteger("bench.stock", 100);
    }

    private long tokenTtlMinutes() {
        return Long.getLong("bench.tokenTtlMinutes", 24L * 60L);
    }

    private long benchmarkShopId() {
        return Long.getLong("bench.shopId", 1L);
    }
}
