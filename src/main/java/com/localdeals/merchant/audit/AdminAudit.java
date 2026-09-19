package com.localdeals.merchant.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Refines the automatic audit record of an admin endpoint. Every non-GET endpoint guarded by
 * {@code @RequireAdminPermission} is audited without it; this only names the action and says
 * which business object it touched.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAudit {

    /** Stable action name, e.g. COUPON_VERIFY. Defaults to "<HTTP method> <route pattern>". */
    String action() default "";

    String targetType() default "";

    /**
     * SpEL evaluated against {@code #result} (the handler's return value) after success, e.g.
     * {@code #result.data.couponNo}. Defaults to the route's path variables.
     */
    String target() default "";
}
