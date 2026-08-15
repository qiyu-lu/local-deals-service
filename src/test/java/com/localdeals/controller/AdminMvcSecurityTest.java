package com.localdeals.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.config.WebConfig;
import com.localdeals.config.WebExceptionAdvice;
import com.localdeals.dto.AdminLoginResponse;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.entity.Shop;
import com.localdeals.service.AdminAuthService;
import com.localdeals.service.AdminClientIpResolver;
import com.localdeals.service.AdminCatalogService;
import com.localdeals.service.AdminManagementService;
import com.localdeals.service.AdminSessionService;
import com.localdeals.service.IShopService;
import com.localdeals.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.ContextConfiguration;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.localdeals.utils.RedisConstants.LOGIN_USER_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AdminAuthController.class,
        AdminManagementController.class,
        AdminShopController.class,
        ShopController.class,
        VoucherController.class
})
@ContextConfiguration(classes = AdminMvcSecurityTest.MvcTestConfiguration.class)
class AdminMvcSecurityTest {
    private static final String ADMIN_TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String READ_ONLY_TOKEN = "abcdef0123456789abcdef0123456789";
    private static final String CONSUMER_TOKEN = "consumer-mvc-token";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private AdminAuthService adminAuthService;
    @MockBean
    private AdminSessionService adminSessionService;
    @MockBean
    private AdminClientIpResolver adminClientIpResolver;
    @MockBean
    private AdminCatalogService adminCatalogService;
    @MockBean
    private AdminManagementService adminManagementService;
    @MockBean
    private IShopService shopService;
    @MockBean
    private IVoucherService voucherService;
    @MockBean
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(adminClientIpResolver.resolve(any())).thenReturn("127.0.0.1");
        AdminPrincipal shopReader = principal(AdminPermissionCodes.SHOP_READ);
        AdminPrincipal noMerchantPermission = principal(AdminPermissionCodes.SHOP_READ);
        when(adminSessionService.extractBearerToken("Bearer " + ADMIN_TOKEN)).thenReturn(ADMIN_TOKEN);
        when(adminSessionService.extractBearerToken("Bearer " + READ_ONLY_TOKEN))
                .thenReturn(READ_ONLY_TOKEN);
        when(adminSessionService.resolve(ADMIN_TOKEN, true)).thenReturn(shopReader);
        when(adminSessionService.resolve(READ_ONLY_TOKEN, true)).thenReturn(noMerchantPermission);
        when(adminCatalogService.listShops(1, 20, null)).thenReturn(new Page<Shop>(1, 20));

        HashOperations<String, Object, Object> hashes =
                (HashOperations<String, Object, Object>) org.mockito.Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashes);
        Map<Object, Object> user = new HashMap<>();
        user.put("id", "900001");
        when(hashes.entries(LOGIN_USER_KEY + CONSUMER_TOKEN)).thenReturn(user);
    }

    @Test
    void loginIsTheOnlyAnonymousAdminEntryPoint() throws Exception {
        AdminLoginResponse loginResponse = new AdminLoginResponse(
                ADMIN_TOKEN, 1800L, principal(AdminPermissionCodes.SHOP_READ));
        when(adminAuthService.login(any(), anyString())).thenReturn(loginResponse);

        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"platform.admin\",\"password\":\"password-1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").value(ADMIN_TOKEN));
        mockMvc.perform(get("/admin/shops"))
                .andExpect(status().isUnauthorized());

        verify(adminAuthService).login(any(), anyString());
    }

    @Test
    void actualInterceptorChainRequiresCurrentAdminPermission() throws Exception {
        mockMvc.perform(get("/admin/shops")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/merchants")
                        .header("Authorization", "Bearer " + READ_ONLY_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/shops").header("authorization", CONSUMER_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void retiredConsumerFacingWriteMappingsCannotBeReachedByALoggedInConsumer() throws Exception {
        assertRetired(post("/shop")
                .header("authorization", CONSUMER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertRetired(put("/shop")
                .header("authorization", CONSUMER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertRetired(post("/voucher")
                .header("authorization", CONSUMER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertRetired(post("/voucher/seckill")
                .header("authorization", CONSUMER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private AdminPrincipal principal(String permission) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(7L);
        principal.setScopeType(AdminPrincipal.SCOPE_PLATFORM);
        principal.setPermissions(Collections.singleton(permission));
        return principal;
    }

    private void assertRetired(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isIn(404, 405));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WebConfig.class,
            WebExceptionAdvice.class,
            AdminAuthController.class,
            AdminManagementController.class,
            AdminShopController.class,
            ShopController.class,
            VoucherController.class
    })
    static class MvcTestConfiguration {
    }
}
