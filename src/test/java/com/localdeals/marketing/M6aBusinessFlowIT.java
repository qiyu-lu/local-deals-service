package com.localdeals.marketing;

import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.MarketingTagMemberRequest;
import com.localdeals.dto.MarketingTagRequest;
import com.localdeals.dto.VoucherCampaignRequest;
import com.localdeals.dto.VoucherCampaignStatusRequest;
import com.localdeals.dto.VoucherCampaignUserView;
import com.localdeals.dto.VoucherGrantClaimRequest;
import com.localdeals.dto.VoucherGrantUserView;
import com.localdeals.entity.MarketingTag;
import com.localdeals.entity.MarketingTagMember;
import com.localdeals.entity.VoucherCampaign;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.service.MarketingAdminService;
import com.localdeals.service.VoucherCampaignUserService;
import com.localdeals.utils.AdminPrincipalHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = M6aPersistenceTestConfiguration.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "M6A_ISOLATED", matches = "true")
class M6aBusinessFlowIT {
    private static final long MERCHANT_ID = 1L;
    private static final long SHOP_ID = 1L;
    private static final long VOUCHER_ID = 1L;
    private static final long BUSINESS_ORDER_ID = 900000000000000001L;
    private static final String ACCOUNT_USERNAME = "m6a.close.flow.account";
    private static final String FIXTURE_PREFIX = "M6A_CLOSE_FLOW_";

    @DynamicPropertySource
    static void registerM6aDatasource(DynamicPropertyRegistry registry) {
        M6aDatasourceGuard.register(registry);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MarketingAdminService marketingAdminService;
    @Autowired private VoucherCampaignUserService campaignUserService;

    @BeforeEach
    void cleanBefore() {
        cleanupFixtures();
        AdminPrincipalHolder.remove();
    }

    @AfterEach
    void cleanAfter() {
        AdminPrincipalHolder.remove();
        cleanupFixtures();
    }

    @Test
    void ownerToMemberToManualTagClaimFlowHasOneGrantAndOneCount() {
        Long memberUserId = selectUserWithoutOrder(-1L);
        Long ineligibleUserId = selectUserWithoutOrder(memberUserId);
        jdbcTemplate.update("INSERT INTO tb_voucher_order " +
                        "(id,user_id,voucher_id,pay_type,status) VALUES(?,?,?,?,?)",
                BUSINESS_ORDER_ID, memberUserId, VOUCHER_ID, 1, 2);

        Long operatorId = createAccount();
        AdminPrincipalHolder.save(merchantPrincipal(operatorId));

        MarketingTag tag = marketingAdminService.createTag(tagRequest());
        MarketingTagMember member = marketingAdminService.addMember(tag.getId(), memberUserId,
                new MarketingTagMemberRequest());
        assertThat(member.getStatus()).isEqualTo("ACTIVE");
        assertThat(member.getUserId()).isEqualTo(memberUserId);
        assertThat(marketingAdminService.listMembers(tag.getId(), null))
                .extracting(MarketingTagMember::getUserId)
                .containsExactly(memberUserId);

        VoucherCampaign campaign = activate(createCampaign(tag.getId(), "eligible"));
        AdminPrincipalHolder.remove();

        VoucherCampaignUserView claimable = viewFor(campaignUserService.listForShop(SHOP_ID, memberUserId),
                campaign.getId());
        assertThat(claimable.getClaimState()).isEqualTo("CLAIMABLE");
        assertThat(claimable.getClaimReason()).isNull();
        assertThat(claimable.getAlreadyGranted()).isFalse();
        assertThat(claimable.getRuleVersion()).isEqualTo(campaign.getRuleVersion());

        VoucherGrantClaimRequest claimRequest = new VoucherGrantClaimRequest();
        claimRequest.setExpectedRuleVersion(campaign.getRuleVersion());
        VoucherGrantUserView first = campaignUserService.claim(campaign.getId(), claimRequest, memberUserId);
        VoucherGrantUserView retry = campaignUserService.claim(campaign.getId(), claimRequest, memberUserId);
        assertThat(retry.getId()).isEqualTo(first.getId());

        VoucherCampaignUserView alreadyGranted = viewFor(
                campaignUserService.listForShop(SHOP_ID, memberUserId), campaign.getId());
        assertThat(alreadyGranted.getClaimState()).isEqualTo("ALREADY_GRANTED");
        assertThat(alreadyGranted.getClaimReason()).isEqualTo("已领取");
        assertThat(alreadyGranted.getAlreadyGranted()).isTrue();
        assertThat(campaignUserService.listMine(memberUserId))
                .filteredOn(grant -> campaign.getId().equals(grant.getCampaignId()))
                .hasSize(1);
        assertCampaignInvariant(campaign.getId(), 1, 1);

        AdminPrincipalHolder.save(merchantPrincipal(operatorId));
        VoucherCampaign ineligibleCampaign = activate(createCampaign(tag.getId(), "ineligible"));
        AdminPrincipalHolder.remove();

        VoucherCampaignUserView ineligible = viewFor(
                campaignUserService.listForShop(SHOP_ID, ineligibleUserId), ineligibleCampaign.getId());
        assertThat(ineligible.getClaimState()).isEqualTo("INELIGIBLE");
        assertThat(ineligible.getAlreadyGranted()).isFalse();
        VoucherGrantClaimRequest ineligibleRequest = new VoucherGrantClaimRequest();
        ineligibleRequest.setExpectedRuleVersion(ineligibleCampaign.getRuleVersion());
        assertThatThrownBy(() -> campaignUserService.claim(ineligibleCampaign.getId(),
                ineligibleRequest, ineligibleUserId))
                .isInstanceOf(ApiStatusException.class)
                .satisfies(error -> assertThat(((ApiStatusException) error).getCode())
                        .isEqualTo("CAMPAIGN_INELIGIBLE"));
        assertCampaignInvariant(ineligibleCampaign.getId(), 0, 0);
    }

    private MarketingTagRequest tagRequest() {
        MarketingTagRequest request = new MarketingTagRequest();
        request.setCode(FIXTURE_PREFIX + "TAG");
        request.setName("M6A Close Tag");
        return request;
    }

    private VoucherCampaign createCampaign(Long tagId, String suffix) {
        VoucherCampaignRequest request = new VoucherCampaignRequest();
        request.setVoucherId(VOUCHER_ID);
        request.setName(FIXTURE_PREFIX + suffix);
        request.setGrantMode("BOTH");
        request.setEligibilityType("MANUAL_TAG");
        request.setRequiredTagId(tagId);
        LocalDateTime databaseNow = jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
        request.setBeginTime(databaseNow.minusMinutes(1));
        request.setEndTime(databaseNow.plusDays(1));
        request.setQuotaTotal(1);
        return marketingAdminService.createCampaign(request);
    }

    private VoucherCampaign activate(VoucherCampaign draft) {
        VoucherCampaignStatusRequest request = new VoucherCampaignStatusRequest();
        request.setExpectedStatus(draft.getStatus());
        request.setExpectedRuleVersion(draft.getRuleVersion());
        request.setStatus("ACTIVE");
        return marketingAdminService.changeStatus(draft.getId(), request);
    }

    private Long createAccount() {
        jdbcTemplate.update("INSERT INTO tb_admin_account " +
                        "(merchant_id,username,password_hash,display_name,scope_type,status) " +
                        "VALUES(?,?,?,?,?,1)", MERCHANT_ID, ACCOUNT_USERNAME,
                "$2a$10$012345678901234567890u012345678901234567890123456789012",
                ACCOUNT_USERNAME, AdminPrincipal.SCOPE_MERCHANT);
        return jdbcTemplate.queryForObject("SELECT id FROM tb_admin_account WHERE username=?",
                Long.class, ACCOUNT_USERNAME);
    }

    private AdminPrincipal merchantPrincipal(Long accountId) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(accountId);
        principal.setMerchantId(MERCHANT_ID);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        return principal;
    }

    private Long selectUserWithoutOrder(Long excludedUserId) {
        return jdbcTemplate.queryForObject("SELECT u.id FROM tb_user u " +
                        "WHERE u.id <> ? AND NOT EXISTS (SELECT 1 FROM tb_voucher_order o " +
                        "WHERE o.user_id=u.id AND o.voucher_id=?) ORDER BY u.id LIMIT 1",
                Long.class, excludedUserId, VOUCHER_ID);
    }

    private VoucherCampaignUserView viewFor(List<VoucherCampaignUserView> views, Long campaignId) {
        return views.stream().filter(view -> campaignId.equals(view.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("campaign view not found: " + campaignId));
    }

    private void assertCampaignInvariant(Long campaignId, int grants, int grantedCount) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_grant WHERE campaign_id=?", Integer.class, campaignId))
                .isEqualTo(grants);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT granted_count FROM tb_voucher_campaign WHERE id=?", Integer.class, campaignId))
                .isEqualTo(grantedCount);
    }

    private void cleanupFixtures() {
        jdbcTemplate.update("DELETE g FROM tb_voucher_grant g " +
                "JOIN tb_voucher_campaign c ON c.id=g.campaign_id " +
                "WHERE c.name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE m FROM tb_marketing_tag_member m " +
                "JOIN tb_marketing_tag t ON t.id=m.tag_id WHERE t.code LIKE ?",
                FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_voucher_campaign WHERE name LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_marketing_tag WHERE code LIKE ?", FIXTURE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE id=?", BUSINESS_ORDER_ID);
        jdbcTemplate.update("DELETE FROM tb_admin_account WHERE username=?", ACCOUNT_USERNAME);
    }
}
