package com.localdeals.marketing;

import com.localdeals.dto.AdminPrincipal;
import com.localdeals.dto.MarketingTagMemberRequest;
import com.localdeals.dto.MarketingTagRequest;
import com.localdeals.dto.VoucherCampaignRequest;
import com.localdeals.dto.VoucherCampaignStatusRequest;
import com.localdeals.entity.AdminAccount;
import com.localdeals.entity.MarketingTag;
import com.localdeals.entity.Merchant;
import com.localdeals.entity.Shop;
import com.localdeals.entity.Voucher;
import com.localdeals.entity.VoucherCampaign;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.mapper.AdminAccountMapper;
import com.localdeals.mapper.MarketingTagMapper;
import com.localdeals.mapper.MerchantMapper;
import com.localdeals.mapper.ShopMapper;
import com.localdeals.mapper.VoucherMapper;
import com.localdeals.service.MarketingAdminService;
import com.localdeals.utils.AdminPrincipalHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = M6aPersistenceTestConfiguration.class)
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "M6A_ISOLATED", matches = "true")
class MarketingAdminIsolationIT {
    @Autowired private ApplicationContext applicationContext;
    @Autowired private MarketingAdminService service;
    @Autowired private MarketingTagMapper tagMapper;
    @Autowired private MerchantMapper merchantMapper;
    @Autowired private ShopMapper shopMapper;
    @Autowired private VoucherMapper voucherMapper;
    @Autowired private AdminAccountMapper accountMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerM6aDatasource(DynamicPropertyRegistry registry) {
        M6aDatasourceGuard.register(registry);
    }

    @AfterEach
    void clearPrincipal() {
        AdminPrincipalHolder.remove();
    }

    @BeforeEach
    void assertNoSharedRuntimeClients() {
        assertThat(applicationContext.getBeansOfType(DefaultMQProducer.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DefaultLitePullConsumer.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DefaultMQPushConsumer.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DefaultRocketMQListenerContainer.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(RocketMQTemplate.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(RestHighLevelClient.class)).isEmpty();
    }

    @Test
    void merchantScopeAndBusinessRelationshipAreEnforcedInRealMysql() {
        Merchant merchantA = merchant("M6A_ISO_A");
        Merchant merchantB = merchant("M6A_ISO_B");
        AdminAccount ownerA = account(merchantA.getId(), "m6a.owner.a");
        AdminAccount ownerB = account(merchantB.getId(), "m6a.owner.b");
        Shop shopA = shop(merchantA.getId(), "M6A shop A");
        Shop shopB = shop(merchantB.getId(), "M6A shop B");
        Voucher voucherA = voucher(shopA.getId(), "M6A voucher A");
        Voucher voucherB = voucher(shopB.getId(), "M6A voucher B");
        jdbcTemplate.update("INSERT INTO tb_voucher_order" +
                        "(id,user_id,voucher_id,pay_type,status) VALUES(?,?,?,?,?)",
                9_610_001L, 1L, voucherA.getId(), 1, 2);

        AdminPrincipalHolder.save(merchantPrincipal(ownerA, merchantA.getId()));
        MarketingTag tagA = service.createTag(tagRequest(null, "VIP_A", "A VIP"));
        assertThat(tagMapper.selectScoped(tagA.getId(), merchantB.getId())).isNull();
        assertThatThrownBy(() -> service.listTags(merchantB.getId()))
                .isInstanceOf(ApiStatusException.class).hasMessage("禁止跨商户操作");

        MarketingTagMemberRequest member = new MarketingTagMemberRequest();
        member.setExpireTime(LocalDateTime.now().plusDays(1));
        assertThat(service.addMember(tagA.getId(), 1L, member).getStatus()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> service.addMember(tagA.getId(), 2L, member))
                .isInstanceOf(ApiStatusException.class)
                .hasMessage("用户与当前商户尚无业务关系");

        VoucherCampaign campaign = service.createCampaign(campaignRequest(null, voucherA.getId(), tagA.getId()));
        assertThat(campaign.getMerchantId()).isEqualTo(merchantA.getId());
        VoucherCampaignRequest update = campaignRequest(null, voucherA.getId(), tagA.getId());
        update.setExpectedStatus("DRAFT");
        update.setExpectedRuleVersion(campaign.getRuleVersion());
        update.setName("M6A targeted campaign v2");
        VoucherCampaign updated = service.updateCampaign(campaign.getId(), update);
        assertThat(updated.getRuleVersion()).isEqualTo(2L);
        VoucherCampaignRequest staleUpdate = campaignRequest(null, voucherA.getId(), tagA.getId());
        staleUpdate.setExpectedStatus("DRAFT");
        staleUpdate.setExpectedRuleVersion(campaign.getRuleVersion());
        assertThatThrownBy(() -> service.updateCampaign(campaign.getId(), staleUpdate))
                .isInstanceOf(ApiStatusException.class)
                .hasMessage("活动已变化或新额度小于已发放数量");
        VoucherCampaignStatusRequest activate = new VoucherCampaignStatusRequest();
        activate.setMerchantId(null);
        activate.setExpectedStatus("DRAFT");
        activate.setExpectedRuleVersion(updated.getRuleVersion());
        activate.setStatus("ACTIVE");
        VoucherCampaign active = service.changeStatus(campaign.getId(), activate);
        assertThat(active.getStatus()).isEqualTo("ACTIVE");
        VoucherCampaignRequest activeUpdate = campaignRequest(null, voucherA.getId(), tagA.getId());
        activeUpdate.setExpectedStatus("ACTIVE");
        activeUpdate.setExpectedRuleVersion(active.getRuleVersion());
        assertThatThrownBy(() -> service.updateCampaign(campaign.getId(), activeUpdate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expected status 只允许 DRAFT 或 PAUSED");
        VoucherCampaignStatusRequest pause = new VoucherCampaignStatusRequest();
        pause.setExpectedStatus("ACTIVE");
        pause.setExpectedRuleVersion(active.getRuleVersion());
        pause.setStatus("PAUSED");
        assertThat(service.changeStatus(campaign.getId(), pause).getStatus()).isEqualTo("PAUSED");
        assertThatThrownBy(() -> service.createCampaign(
                campaignRequest(null, voucherB.getId(), tagA.getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("活动只能绑定当前商户已上架的普通券");

        AdminPrincipal platform = new AdminPrincipal();
        platform.setAccountId(ownerB.getId());
        platform.setScopeType(AdminPrincipal.SCOPE_PLATFORM);
        AdminPrincipalHolder.save(platform);
        assertThatThrownBy(() -> service.listTags(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("平台操作必须显式指定 merchantId");
        MarketingTag tagB = service.createTag(tagRequest(merchantB.getId(), "VIP_B", "B VIP"));
        assertThat(tagB.getMerchantId()).isEqualTo(merchantB.getId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_marketing_tag WHERE merchant_id=?", Integer.class,
                merchantA.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_marketing_tag_member WHERE merchant_id=?", Integer.class,
                merchantB.getId())).isZero();
    }

    private Merchant merchant(String code) {
        Merchant merchant = new Merchant();
        merchant.setCode(code);
        merchant.setName(code);
        merchant.setStatus(1);
        assertThat(merchantMapper.insert(merchant)).isEqualTo(1);
        return merchant;
    }

    private AdminAccount account(Long merchantId, String username) {
        AdminAccount account = new AdminAccount();
        account.setMerchantId(merchantId);
        account.setUsername(username);
        account.setPasswordHash("$2a$10$012345678901234567890u012345678901234567890123456789012");
        account.setDisplayName(username);
        account.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        account.setStatus(1);
        assertThat(accountMapper.insert(account)).isEqualTo(1);
        return account;
    }

    private Shop shop(Long merchantId, String name) {
        Shop shop = new Shop();
        shop.setMerchantId(merchantId);
        shop.setName(name);
        shop.setTypeId(1L);
        shop.setImages("");
        shop.setAddress("isolated");
        shop.setX(120.0);
        shop.setY(30.0);
        shop.setSold(0);
        shop.setComments(0);
        shop.setScore(0);
        assertThat(shopMapper.insert(shop)).isEqualTo(1);
        return shop;
    }

    private Voucher voucher(Long shopId, String title) {
        Voucher voucher = new Voucher();
        voucher.setShopId(shopId);
        voucher.setTitle(title);
        voucher.setPayValue(800L);
        voucher.setActualValue(1000L);
        voucher.setType(0);
        voucher.setStatus(1);
        assertThat(voucherMapper.insert(voucher)).isEqualTo(1);
        return voucher;
    }

    private AdminPrincipal merchantPrincipal(AdminAccount account, Long merchantId) {
        AdminPrincipal principal = new AdminPrincipal();
        principal.setAccountId(account.getId());
        principal.setMerchantId(merchantId);
        principal.setScopeType(AdminPrincipal.SCOPE_MERCHANT);
        return principal;
    }

    private MarketingTagRequest tagRequest(Long merchantId, String code, String name) {
        MarketingTagRequest request = new MarketingTagRequest();
        request.setMerchantId(merchantId);
        request.setCode(code);
        request.setName(name);
        return request;
    }

    private VoucherCampaignRequest campaignRequest(Long merchantId, Long voucherId, Long tagId) {
        VoucherCampaignRequest request = new VoucherCampaignRequest();
        request.setMerchantId(merchantId);
        request.setVoucherId(voucherId);
        request.setName("M6A targeted campaign");
        request.setGrantMode("BOTH");
        request.setEligibilityType("MANUAL_TAG");
        request.setRequiredTagId(tagId);
        request.setBeginTime(LocalDateTime.now().minusMinutes(1));
        request.setEndTime(LocalDateTime.now().plusDays(1));
        request.setQuotaTotal(10);
        return request;
    }
}
