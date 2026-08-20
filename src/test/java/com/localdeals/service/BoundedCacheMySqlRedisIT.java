package com.localdeals.service;

import com.localdeals.LocalDealsApplication;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.AdminShopUpdateRequest;
import com.localdeals.dto.Result;
import com.localdeals.entity.Shop;
import com.localdeals.entity.ShopType;
import com.localdeals.utils.AdminPrincipalHolder;
import com.localdeals.utils.RedisConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LocalDealsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M5B_ISOLATED", matches = "true")
class BoundedCacheMySqlRedisIT {

    private static final String RUN_ID = requiredEnv("M5B_RUN_ID");
    private static final Long SHOP_ID = 1L;
    private static final String FIXTURE_NAME = "M5B Fixture " + RUN_ID;

    @Autowired
    private IShopService shopService;
    @Autowired
    private IShopTypeService shopTypeService;
    @Autowired
    private AdminCatalogService adminCatalogService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnv("M5B_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> requiredEnv("M5B_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> requiredEnv("M5B_MYSQL_PASSWORD"));
        registry.add("spring.redis.host", () -> requiredEnv("M5B_REDIS_HOST"));
        registry.add("spring.redis.port", () -> requiredEnv("M5B_REDIS_PORT"));
        registry.add("spring.redis.password", () -> requiredEnv("M5B_REDIS_PASSWORD"));
        registry.add("spring.redis.timeout", () -> "500ms");
        registry.add("spring.redis.lettuce.pool.max-wait", () -> "500ms");
        registry.add("spring.elasticsearch.rest.uris",
                () -> "http://127.0.0.1:" + requiredEnv("M5B_ES_PORT"));
        registry.add("rocketmq.name-server",
                () -> "127.0.0.1:" + requiredEnv("M5B_RMQ_NAMESRV_PORT"));
        registry.add("local-deals.observability.sampling-enabled", () -> "false");
    }

    @BeforeEach
    void prepareOwnedFixtureAndVerifyBothSentinels() {
        String expectedSchema = "m5b_" + RUN_ID.replace('-', '_');
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo(expectedSchema);
        assertThat(redisTemplate.opsForValue().get("m5b:sentinel:" + RUN_ID))
                .isEqualTo(RUN_ID);
        jdbcTemplate.update("UPDATE tb_shop SET name=? WHERE id=?", FIXTURE_NAME, SHOP_ID);
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + SHOP_ID);
        redisTemplate.delete(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY);
    }

    @AfterEach
    void cleanupThreadAndCacheState() {
        AdminPrincipalHolder.remove();
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + SHOP_ID);
        redisTemplate.delete(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY);
    }

    @Test
    void shopDetailAndTypeListUseMySqlTruthThenRedisPayloads() {
        Result cold = shopService.queryShopById(SHOP_ID);
        Result warm = shopService.queryShopById(SHOP_ID);
        assertThat(cold.getSuccess()).isTrue();
        assertThat(((Shop) cold.getData()).getName()).isEqualTo(FIXTURE_NAME);
        assertThat(((Shop) warm.getData()).getName()).isEqualTo(FIXTURE_NAME);
        assertThat(redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + SHOP_ID))
                .contains(FIXTURE_NAME);

        List<ShopType> coldTypes = shopTypeService.queryTypeList();
        List<ShopType> warmTypes = shopTypeService.queryTypeList();
        assertThat(coldTypes).isNotEmpty().extracting(ShopType::getId).doesNotHaveDuplicates();
        assertThat(warmTypes).extracting(ShopType::getId)
                .containsExactlyElementsOf(coldTypes.stream().map(ShopType::getId)
                        .collect(java.util.stream.Collectors.toList()));
        assertThat(redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_LIST_KEY))
                .startsWith("[");
    }

    @Test
    void committedAdminUpdateEvictsTheExactDetailKey() {
        loginPlatform();
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + SHOP_ID,
                "{\"id\":1,\"name\":\"stale\"}");
        AdminShopUpdateRequest request = new AdminShopUpdateRequest();
        request.setName(FIXTURE_NAME + " committed");

        adminCatalogService.updateShop(SHOP_ID, request);

        assertThat(redisTemplate.hasKey(RedisConstants.CACHE_SHOP_KEY + SHOP_ID)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM tb_shop WHERE id=?",
                String.class, SHOP_ID)).isEqualTo(FIXTURE_NAME + " committed");
    }

    @Test
    void rolledBackAdminUpdateLeavesThePreexistingCacheUntouched() {
        loginPlatform();
        String exactKey = RedisConstants.CACHE_SHOP_KEY + SHOP_ID;
        String cached = "{\"id\":1,\"name\":\"pre-transaction\"}";
        redisTemplate.opsForValue().set(exactKey, cached);
        AdminShopUpdateRequest request = new AdminShopUpdateRequest();
        request.setName(FIXTURE_NAME + " rolled-back");

        transactionTemplate.execute(status -> {
            adminCatalogService.updateShop(SHOP_ID, request);
            status.setRollbackOnly();
            return null;
        });

        assertThat(redisTemplate.opsForValue().get(exactKey)).isEqualTo(cached);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM tb_shop WHERE id=?",
                String.class, SHOP_ID)).isEqualTo(FIXTURE_NAME);
    }

    private static void loginPlatform() {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(1L);
        principal.setScopeType(AdminPrincipal.SCOPE_PLATFORM);
        AdminPrincipalHolder.save(principal);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(name + " must be set for isolated M5B IT");
        }
        return value;
    }
}
