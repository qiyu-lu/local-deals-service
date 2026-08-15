package com.localdeals.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.auth.AdminRoleCodes;
import com.localdeals.dto.AdminLoginRequest;
import com.localdeals.dto.AdminLoginResponse;
import com.localdeals.dto.AdminPasswordChangeRequest;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.AdminWebSocketTicketResponse;
import com.localdeals.entity.AdminAccount;
import com.localdeals.entity.AdminRole;
import com.localdeals.entity.Merchant;
import com.localdeals.entity.Shop;
import com.localdeals.entity.Voucher;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.AdminAccountMapper;
import com.localdeals.mapper.AdminRoleMapper;
import com.localdeals.mapper.MerchantMapper;
import com.localdeals.mapper.ShopMapper;
import com.localdeals.mapper.VoucherMapper;
import com.localdeals.utils.AdminPrincipalHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static com.localdeals.utils.RedisConstants.ADMIN_LOGIN_TOKEN_KEY;
import static com.localdeals.utils.RedisConstants.LOGIN_USER_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminRbacIT {
    @Autowired
    private MerchantMapper merchantMapper;
    @Autowired
    private AdminAccountMapper accountMapper;
    @Autowired
    private AdminRoleMapper roleMapper;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private VoucherMapper voucherMapper;
    @Autowired
    private AdminCatalogService catalogService;
    @Autowired
    private AdminAuthService authService;
    @Autowired
    private AdminSessionService sessionService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private MockMvc mockMvc;

    private String issuedToken;
    private String consumerToken;

    @AfterEach
    void removeRedisSession() {
        AdminPrincipalHolder.remove();
        if (issuedToken != null) {
            redisTemplate.delete(ADMIN_LOGIN_TOKEN_KEY + issuedToken);
        }
        if (consumerToken != null) {
            redisTemplate.delete(LOGIN_USER_KEY + consumerToken);
        }
    }

    @Test
    void merchantScopeIsEnforcedByTheSameVoucherQueryThatReturnsRows() {
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase();
        Merchant merchantA = insertMerchant("IT_SCOPE_A_" + suffix);
        Merchant merchantB = insertMerchant("IT_SCOPE_B_" + suffix);
        Shop shopA = insertShop(merchantA.getId(), "Scoped shop A");
        Shop shopB = insertShop(merchantB.getId(), "Scoped shop B");
        Voucher voucherA = insertVoucher(shopA.getId(), "Voucher A");
        insertVoucher(shopB.getId(), "Voucher B");

        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(100L);
        principal.setMerchantId(merchantA.getId());
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        AdminPrincipalHolder.save(principal);

        assertThat(catalogService.getShop(shopA.getId()).getId()).isEqualTo(shopA.getId());
        assertThat(catalogService.listVouchers(shopA.getId()))
                .extracting(Voucher::getId)
                .containsExactly(voucherA.getId());
        assertThat(voucherMapper.queryVoucherOfShopForAdmin(shopB.getId(), merchantA.getId()))
                .isEmpty();
        assertThatThrownBy(() -> catalogService.getShop(shopB.getId()))
                .isInstanceOf(ApiStatusException.class)
                .hasMessage("资源不存在");
    }

    @Test
    void rbacMigrationsBackfillShopsAndEnableCredentialVersioning() {
        Integer unscoped = shopMapper.selectCount(
                new QueryWrapper<com.localdeals.entity.Shop>().isNull("merchant_id"));
        Merchant legacy = merchantMapper.selectOne(new QueryWrapper<Merchant>()
                .eq("code", "LEGACY_UNASSIGNED").last("LIMIT 1"));

        assertThat(unscoped).isZero();
        assertThat(legacy).isNotNull();
        assertThat(legacy.getStatus()).isZero();
        AdminAccount anyAccount = accountMapper.selectOne(
                new QueryWrapper<AdminAccount>().last("LIMIT 1"));
        if (anyAccount != null) {
            assertThat(anyAccount.getAuthVersion()).isNotNull();
        }
    }

    @Test
    void realMysqlRedisLoginReloadAndOneTimeWebSocketTicketAreClosedLoop() {
        String suffix = Long.toHexString(System.nanoTime());
        Merchant merchant = new Merchant();
        merchant.setCode(("IT_" + suffix).toUpperCase());
        merchant.setName("RBAC integration merchant");
        merchant.setStatus(1);
        assertThat(merchantMapper.insert(merchant)).isEqualTo(1);

        String username = "it.owner." + suffix;
        String rawPassword = "integration-password-123";
        AdminAccount account = new AdminAccount();
        account.setMerchantId(merchant.getId());
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setDisplayName("Integration owner");
        account.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        account.setStatus(1);
        assertThat(accountMapper.insert(account)).isEqualTo(1);

        AdminRole owner = roleMapper.selectOne(new QueryWrapper<AdminRole>()
                .eq("code", AdminRoleCodes.MERCHANT_OWNER).last("LIMIT 1"));
        assertThat(owner).isNotNull();
        assertThat(accountMapper.insertAccountRole(account.getId(), owner.getId())).isEqualTo(1);

        AdminLoginRequest login = new AdminLoginRequest();
        login.setUsername(username);
        login.setPassword(rawPassword);
        AdminLoginResponse response = authService.login(login, "127.0.0.1");
        issuedToken = response.getToken();

        assertThat(response.getPrincipal().getMerchantId()).isEqualTo(merchant.getId());
        assertThat(response.getPrincipal().getPermissions())
                .contains(AdminPermissionCodes.SHOP_WRITE, AdminPermissionCodes.ORDER_REALTIME)
                .doesNotContain(AdminPermissionCodes.MERCHANT_MANAGE);
        assertThat(sessionService.resolve(issuedToken, false).getAccountId()).isEqualTo(account.getId());

        AdminWebSocketTicketResponse ticket = sessionService.issueWebSocketTicket(issuedToken);
        assertThat(ticket).isNotNull();
        assertThat(sessionService.consumeWebSocketTicket(ticket.getTicket())).isEqualTo(issuedToken);
        assertThat(sessionService.consumeWebSocketTicket(ticket.getTicket())).isNull();

        AdminPasswordChangeRequest changePassword = new AdminPasswordChangeRequest();
        changePassword.setCurrentPassword(rawPassword);
        changePassword.setNewPassword("integration-password-456");
        authService.changePassword(response.getPrincipal(), changePassword);
        assertThat(sessionService.resolve(issuedToken, false)).isNull();
        assertThat(redisTemplate.hasKey(ADMIN_LOGIN_TOKEN_KEY + issuedToken)).isFalse();

        login.setPassword("integration-password-456");
        AdminLoginResponse refreshed = authService.login(login, "127.0.0.1");
        issuedToken = refreshed.getToken();
        assertThat(refreshed.getPrincipal().getAuthVersion()).isEqualTo(1);

        AdminAccount disabled = new AdminAccount();
        disabled.setId(account.getId());
        disabled.setStatus(2);
        assertThat(accountMapper.updateById(disabled)).isEqualTo(1);
        assertThat(sessionService.resolve(issuedToken, false)).isNull();
        assertThat(redisTemplate.hasKey(ADMIN_LOGIN_TOKEN_KEY + issuedToken)).isFalse();
    }

    @Test
    void realMvcChainSeparatesConsumerAndAdminBoundaries() throws Exception {
        String suffix = Long.toHexString(System.nanoTime());
        Merchant ownMerchant = insertMerchant(("IT_MVC_" + suffix).toUpperCase());
        Merchant foreignMerchant = insertMerchant(("IT_FOREIGN_" + suffix).toUpperCase());
        Shop foreignShop = insertShop(foreignMerchant.getId(), "Foreign shop");
        String username = "it.mvc." + suffix;
        String rawPassword = "integration-password-123";
        AdminAccount account = new AdminAccount();
        account.setMerchantId(ownMerchant.getId());
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setDisplayName("MVC owner");
        account.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        account.setStatus(1);
        assertThat(accountMapper.insert(account)).isEqualTo(1);
        AdminRole owner = roleMapper.selectOne(new QueryWrapper<AdminRole>()
                .eq("code", AdminRoleCodes.MERCHANT_OWNER).last("LIMIT 1"));
        assertThat(accountMapper.insertAccountRole(account.getId(), owner.getId())).isEqualTo(1);

        AdminLoginRequest login = new AdminLoginRequest();
        login.setUsername(username);
        login.setPassword(rawPassword);
        issuedToken = authService.login(login, "127.0.0.1").getToken();
        String bearer = "Bearer " + issuedToken;

        mockMvc.perform(get("/admin/shops"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/auth/me").header("Authorization", bearer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/shops/{id}", foreignShop.getId())
                        .header("Authorization", bearer))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/merchants")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        consumerToken = "mvc-consumer-" + suffix;
        redisTemplate.opsForHash().put(LOGIN_USER_KEY + consumerToken, "id", "900001");
        mockMvc.perform(get("/admin/shops").header("authorization", consumerToken))
                .andExpect(status().isUnauthorized());
        assertRetiredWriteIsUnavailable(post("/shop")
                .header("authorization", consumerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertRetiredWriteIsUnavailable(put("/shop")
                .header("authorization", consumerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertRetiredWriteIsUnavailable(post("/voucher")
                .header("authorization", consumerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertRetiredWriteIsUnavailable(post("/voucher/seckill")
                .header("authorization", consumerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        mockMvc.perform(put("/admin/auth/password")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"integration-password-123\"," +
                                "\"newPassword\":\"integration-password-456\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/auth/me").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
    }

    private Merchant insertMerchant(String code) {
        Merchant merchant = new Merchant();
        merchant.setCode(code);
        merchant.setName(code);
        merchant.setStatus(1);
        assertThat(merchantMapper.insert(merchant)).isEqualTo(1);
        return merchant;
    }

    private Shop insertShop(Long merchantId, String name) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setName(name);
        shop.setTypeId(1L);
        shop.setImages("scope-test.png");
        shop.setAddress("scope test address");
        shop.setX(120.0);
        shop.setY(30.0);
        shop.setSold(0);
        shop.setComments(0);
        shop.setScore(0);
        assertThat(shopMapper.insert(shop)).isEqualTo(1);
        return shop;
    }

    private Voucher insertVoucher(Long shopId, String title) {
        Voucher voucher = new Voucher();
        voucher.setShopId(shopId);
        voucher.setTitle(title);
        voucher.setPayValue(8000L);
        voucher.setActualValue(10000L);
        voucher.setType(0);
        voucher.setStatus(1);
        assertThat(voucherMapper.insert(voucher)).isEqualTo(1);
        return voucher;
    }

    private void assertRetiredWriteIsUnavailable(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isIn(404, 405));
    }
}
