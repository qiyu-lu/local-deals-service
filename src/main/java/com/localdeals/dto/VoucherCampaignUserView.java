package com.localdeals.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Deliberately small user-facing campaign projection.  Eligibility and
 * merchant scope are evaluated by the mapper but never serialized here.
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class VoucherCampaignUserView {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long voucherId;
    private String campaignName;
    private String voucherTitle;
    private Long payValue;
    private Long actualValue;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ruleVersion;
    private String claimState;
    private String claimReason;
    private Boolean alreadyGranted;
}
