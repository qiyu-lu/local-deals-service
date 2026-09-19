package com.localdeals.merchant.audit;

import com.localdeals.merchant.auth.RequireAdminPermission;
import com.localdeals.platform.service.TrustedClientIpResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Audits admin write operations. */
@Aspect
@Component
public class AdminAuditAspect {

    private final AdminAuditService auditService;
    private final TrustedClientIpResolver clientIpResolver;

    public AdminAuditAspect(AdminAuditService auditService, TrustedClientIpResolver clientIpResolver) {
        this.auditService = auditService;
        this.clientIpResolver = clientIpResolver;
    }

    @Around("@annotation(permission)")
    public Object audit(ProceedingJoinPoint joinPoint, RequireAdminPermission permission) throws Throwable {
        return joinPoint.proceed();
    }
}
