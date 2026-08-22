package com.localdeals.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * User-facing grant projection.  Merchant scope, user identity, source and
 * operator details remain server-side grant ledger fields.
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class VoucherGrantUserView {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long campaignId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long voucherId;
    private String campaignName;
    private String voucherTitle;
    private Long payValue;
    private Long actualValue;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ruleVersion;
    private LocalDateTime grantedAt;
}
