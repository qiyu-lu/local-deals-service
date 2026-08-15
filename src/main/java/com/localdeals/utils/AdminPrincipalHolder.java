package com.localdeals.utils;

import com.localdeals.dto.AdminPrincipal;

public final class AdminPrincipalHolder {
    private static final ThreadLocal<AdminPrincipal> HOLDER = new ThreadLocal<>();

    private AdminPrincipalHolder() {
    }

    public static void save(AdminPrincipal principal) {
        HOLDER.set(principal);
    }

    public static AdminPrincipal get() {
        return HOLDER.get();
    }

    public static void remove() {
        HOLDER.remove();
    }
}
