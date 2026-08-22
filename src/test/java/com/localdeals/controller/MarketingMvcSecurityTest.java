package com.localdeals.controller;

import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.config.WebConfig;
import com.localdeals.config.WebExceptionAdvice;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.service.AdminSessionService;
import com.localdeals.service.MarketingAdminService;
import com.localdeals.service.VoucherCampaignUserService;
import com.localdeals.service.VoucherGrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.localdeals.utils.RedisConstants.LOGIN_USER_KEY;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        MarketingAdminController.class,
        VoucherCampaignController.class,
        VoucherGrantController.class
})
@ContextConfiguration(classes = MarketingMvcSecurityTest.MvcTestConfiguration.class)
class MarketingMvcSecurityTest {
    private static final String WRITE_TOKEN = "m6a-marketing-write-token";
    private static final String READ_TOKEN = "m6a-marketing-read-token";
    private static final String USER_TOKEN = "m6a-consumer-token";

    @Autowired private MockMvc mockMvc;
    @MockBean private MarketingAdminService marketingAdminService;
    @MockBean private VoucherGrantService grantService;
    @MockBean private VoucherCampaignUserService campaignUserService;
    @MockBean private AdminSessionService adminSessionService;
    @MockBean private StringRedisTemplate redisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(adminSessionService.extractBearerToken("Bearer " + WRITE_TOKEN)).thenReturn(WRITE_TOKEN);
        when(adminSessionService.extractBearerToken("Bearer " + READ_TOKEN)).thenReturn(READ_TOKEN);
        when(adminSessionService.resolve(WRITE_TOKEN, true)).thenReturn(
                principal(AdminPermissionCodes.MARKETING_READ, AdminPermissionCodes.MARKETING_WRITE));
        when(adminSessionService.resolve(READ_TOKEN, true)).thenReturn(
                principal(AdminPermissionCodes.MARKETING_READ));

        HashOperations<String, Object, Object> hashes =
                (HashOperations<String, Object, Object>) org.mockito.Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashes);
        Map<Object, Object> user = new HashMap<>();
        user.put("id", "900001");
        when(hashes.entries(LOGIN_USER_KEY + USER_TOKEN)).thenReturn(user);
    }

    @Test
    void adminMarketingEndpointsRequirePermission() throws Exception {
        mockMvc.perform(get("/admin/marketing/tags"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/marketing/tags")
                        .header("Authorization", "Bearer " + READ_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/marketing/tags")
                        .header("Authorization", "Bearer " + READ_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"1\",\"code\":\"VIP\",\"name\":\"VIP\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/marketing/tags")
                        .header("Authorization", "Bearer " + WRITE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"1\",\"code\":\"VIP\",\"name\":\"VIP\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void userCampaignEndpointsRequireConsumerLoginAndDoNotAcceptAdminScope() throws Exception {
        mockMvc.perform(get("/voucher-campaigns/shop/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/voucher-campaigns/shop/1")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(post("/voucher-campaigns/7/claim")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRuleVersion\":\"1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/voucher-grants/mine")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void adminGrantIsNotReachableWithConsumerOrReadOnlyCredentials() throws Exception {
        String body = "{\"merchantId\":\"1\",\"userId\":\"900001\",\"expectedRuleVersion\":\"1\"}";
        mockMvc.perform(post("/admin/marketing/campaigns/7/grants")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/marketing/campaigns/7/grants")
                        .header("Authorization", "Bearer " + READ_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    private AdminPrincipal principal(String... permissions) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(7L);
        principal.setScopeType(AdminPrincipal.SCOPE_PLATFORM);
        principal.setPermissions(new java.util.LinkedHashSet<>(java.util.Arrays.asList(permissions)));
        return principal;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            WebConfig.class,
            WebExceptionAdvice.class,
            MarketingAdminController.class,
            VoucherCampaignController.class,
            VoucherGrantController.class
    })
    static class MvcTestConfiguration {
    }
}
