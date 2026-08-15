package com.localdeals.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
public class AdminPrincipal {
    public static final String SCOPE_PLATFORM = "PLATFORM";
    public static final String SCOPE_MERCHANT = "MERCHANT";

    private Long accountId;
    private Long merchantId;
    private String username;
    private String displayName;
    private String scopeType;
    @JsonIgnore
    private Integer authVersion;
    private Set<String> permissions = new LinkedHashSet<>();

    public boolean isPlatform() {
        return SCOPE_PLATFORM.equals(scopeType);
    }

    public boolean hasPermission(String permission) {
        return permission != null && permissions != null && permissions.contains(permission);
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
    }
}
