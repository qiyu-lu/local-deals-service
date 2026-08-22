package com.localdeals.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDate;

@Data
public class VoucherGrantCommand {
    public static final String USER_CLAIM = "USER_CLAIM";
    public static final String ADMIN_GRANT = "ADMIN_GRANT";
    public static final String TASK_REWARD = "TASK_REWARD";
    public static final String BATCH_GRANT = "BATCH_GRANT";
    public static final String DAILY_SIGN_IN = "DAILY_SIGN_IN";
    public static final String ONCE = "ONCE";

    private Long campaignId;
    private Long merchantId;
    private Long userId;
    private Long expectedRuleVersion;
    private Long operatorId;
    private String source;

    /** Internal server-owned values; no controller binds this command directly. */
    @JsonIgnore
    private String idempotencyKey;
    @JsonIgnore
    private LocalDate taskDate;
}
