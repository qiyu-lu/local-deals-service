package com.localdeals.marketing.service;

import com.localdeals.marketing.dto.VoucherGrantCommand;
import com.localdeals.marketing.entity.VoucherCampaign;
import com.localdeals.marketing.entity.VoucherGrant;
import com.localdeals.marketing.mapper.MarketingTagMapper;
import com.localdeals.marketing.mapper.MarketingTagMemberMapper;
import com.localdeals.marketing.mapper.VoucherCampaignMapper;
import com.localdeals.marketing.mapper.VoucherGrantMapper;
import com.localdeals.marketing.mapper.VoucherGrantNotificationOutboxMapper;
import com.localdeals.platform.mapper.SignMapper;
import com.localdeals.trade.service.CouponIssuer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One change covers claim, admin grant, task reward and batch grant: they all go through here. */
class VoucherGrantCouponTest {

    private final VoucherCampaignMapper campaignMapper = mock(VoucherCampaignMapper.class);
    private final VoucherGrantMapper grantMapper = mock(VoucherGrantMapper.class);
    private final VoucherGrantNotificationOutboxMapper outboxMapper = mock(VoucherGrantNotificationOutboxMapper.class);
    private final CouponIssuer couponIssuer = mock(CouponIssuer.class);
    private final VoucherGrantTransactionService service = new VoucherGrantTransactionService(campaignMapper,
            grantMapper, mock(MarketingTagMapper.class), mock(MarketingTagMemberMapper.class),
            mock(SignMapper.class), outboxMapper, couponIssuer);

    @Test
    void aNewGrantIssuesItsCouponInTheSameTransaction() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 12, 0);
        when(campaignMapper.selectForUserClaimForUpdate(3L)).thenReturn(activeClaimCampaign(now));
        when(campaignMapper.currentDatabaseTime()).thenReturn(now);
        when(campaignMapper.incrementGrantCount(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(1);
        when(grantMapper.insert(any(VoucherGrant.class))).thenAnswer(invocation -> {
            invocation.<VoucherGrant>getArgument(0).setId(77L);
            return 1;
        });
        when(outboxMapper.insertPending(any())).thenReturn(1);

        VoucherGrant grant = service.grant(claim());

        verify(couponIssuer).issueForGrant(grant);
    }

    @Test
    void anIdempotentReplayDoesNotIssueASecondCoupon() {
        VoucherGrant existing = new VoucherGrant();
        existing.setId(77L);
        when(grantMapper.selectByCampaignAndUser(3L, 7L)).thenReturn(existing);

        service.grant(claim());

        verify(couponIssuer, never()).issueForGrant(any());
    }

    private static VoucherGrantCommand claim() {
        VoucherGrantCommand command = new VoucherGrantCommand();
        command.setCampaignId(3L);
        command.setUserId(7L);
        command.setExpectedRuleVersion(1L);
        command.setSource(VoucherGrantCommand.USER_CLAIM);
        return command;
    }

    private static VoucherCampaign activeClaimCampaign(LocalDateTime now) {
        VoucherCampaign campaign = new VoucherCampaign();
        campaign.setId(3L);
        campaign.setMerchantId(9L);
        campaign.setVoucherId(5L);
        campaign.setGrantMode("CLAIM");
        campaign.setEligibilityType("ALL");
        campaign.setStatus("ACTIVE");
        campaign.setBeginTime(now.minusDays(1));
        campaign.setEndTime(now.plusDays(1));
        campaign.setQuotaTotal(10);
        campaign.setGrantedCount(0);
        campaign.setRuleVersion(1L);
        campaign.setVoucherType(0);
        campaign.setVoucherStatus(1);
        campaign.setVoucherMerchantId(9L);
        return campaign;
    }
}
