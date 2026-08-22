package com.localdeals.controller;

import com.localdeals.auth.AdminPermissionCodes;
import com.localdeals.config.WebConfig;
import com.localdeals.config.WebExceptionAdvice;
import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.VoucherCampaignUserView;
import com.localdeals.dto.VoucherGrantClaimRequest;
import com.localdeals.dto.VoucherGrantUserView;
import com.localdeals.service.AdminSessionService;
import com.localdeals.service.MarketingAdminService;
import com.localdeals.service.VoucherCampaignUserService;
import com.localdeals.service.VoucherGrantService;
import com.localdeals.service.VoucherBatchJobService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @MockBean private VoucherBatchJobService batchJobService;
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
        VoucherCampaignUserView view = new VoucherCampaignUserView();
        view.setId(7L);
        view.setVoucherId(8L);
        view.setCampaignName("专享活动");
        view.setVoucherTitle("代金券");
        view.setRuleVersion(2L);
        view.setClaimState("CLAIMABLE");
        view.setAlreadyGranted(false);
        when(campaignUserService.listForShop(1L, 900001L))
                .thenReturn(Collections.singletonList(view));
        VoucherGrantUserView grantView = new VoucherGrantUserView();
        grantView.setId(11L);
        grantView.setCampaignId(7L);
        grantView.setVoucherId(8L);
        grantView.setRuleVersion(2L);
        when(campaignUserService.claim(eq(7L), any(VoucherGrantClaimRequest.class), eq(900001L)))
                .thenReturn(grantView);
        when(campaignUserService.taskReward(eq(7L), any(VoucherGrantClaimRequest.class), eq(900001L)))
                .thenReturn(grantView);
        when(campaignUserService.listMine(900001L))
                .thenReturn(Collections.singletonList(grantView));
        mockMvc.perform(get("/voucher-campaigns/shop/1")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(get("/voucher-campaigns/shop/1")
                        .header("Authorization", USER_TOKEN))
                .andExpect(jsonPath("$.data[0].id").value("7"))
                .andExpect(jsonPath("$.data[0].voucherId").value("8"))
                .andExpect(jsonPath("$.data[0].claimState").value("CLAIMABLE"))
                .andExpect(jsonPath("$.data[0].requiredTagId").doesNotExist())
                .andExpect(jsonPath("$.data[0].tagEligible").doesNotExist())
                .andExpect(jsonPath("$.data[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$.data[0].eligibilityType").doesNotExist());
        mockMvc.perform(post("/voucher-campaigns/7/claim")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRuleVersion\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.source").doesNotExist())
                .andExpect(jsonPath("$.data.operatorId").doesNotExist());
        mockMvc.perform(post("/voucher-campaigns/7/task-reward")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRuleVersion\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.operatorId").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());
        mockMvc.perform(get("/voucher-grants/mine")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].campaignId").value("7"))
                .andExpect(jsonPath("$.data[0].merchantId").doesNotExist())
                .andExpect(jsonPath("$.data[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data[0].source").doesNotExist())
                .andExpect(jsonPath("$.data[0].operatorId").doesNotExist());
    }

    @Test
    void tagMembersAreReadableByReadOnlyStaffButWritableOnlyWithWritePermission() throws Exception {
        when(marketingAdminService.listMembers(7L, null)).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/admin/marketing/tags/7/members")
                        .header("Authorization", "Bearer " + READ_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/marketing/tags/7/members/900001")
                        .header("Authorization", "Bearer " + READ_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expireTime\":null}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/admin/marketing/tags/7/members/900001")
                        .header("Authorization", "Bearer " + READ_TOKEN))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/marketing/tags/7/members/900001")
                        .header("Authorization", "Bearer " + WRITE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"1\",\"expireTime\":null}"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/admin/marketing/tags/7/members/900001")
                        .header("Authorization", "Bearer " + WRITE_TOKEN)
                        .param("merchantId", "1"))
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

    @Test
    void batchJobApisKeepMarketingPermissionBoundaryAndDtoRequestShape() throws Exception {
        String body = "{\"requestId\":\"m6c-test-request\",\"expectedRuleVersion\":\"2\"}";
        mockMvc.perform(get("/admin/marketing/batch-jobs")
                        .header("Authorization", "Bearer " + READ_TOKEN))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/marketing/campaigns/7/batch-jobs")
                        .header("Authorization", "Bearer " + READ_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/marketing/campaigns/7/batch-jobs")
                        .header("Authorization", "Bearer " + WRITE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/marketing/batch-jobs/7/items")
                        .header("Authorization", "Bearer " + READ_TOKEN)
                        .param("status", "FAILED")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/marketing/batch-jobs/7/pause")
                        .header("Authorization", "Bearer " + READ_TOKEN))
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
