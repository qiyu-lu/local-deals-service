package com.localdeals.merchant.audit;

import com.localdeals.merchant.auth.RequireAdminPermission;
import com.localdeals.merchant.dto.AdminPrincipal;
import com.localdeals.merchant.utils.AdminPrincipalHolder;
import com.localdeals.platform.dto.Result;
import com.localdeals.platform.exception.ApiStatusException;
import com.localdeals.platform.service.TrustedClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Audits admin write operations: every non-GET handler guarded by {@link RequireAdminPermission}
 * gets a row, so a new endpoint cannot be forgotten. Runs inside the handler, after the
 * authorization interceptor, so the principal is known; the row is written in its own
 * transaction after the business call returned or threw.
 */
@Slf4j
@Aspect
@Component
public class AdminAuditAspect {

    private static final int MAX_TARGET_LENGTH = 255;
    private static final ExpressionParser SPEL = new SpelExpressionParser();

    private final AdminAuditService auditService;
    private final TrustedClientIpResolver clientIpResolver;

    public AdminAuditAspect(AdminAuditService auditService, TrustedClientIpResolver clientIpResolver) {
        this.auditService = auditService;
        this.clientIpResolver = clientIpResolver;
    }

    @Around("@annotation(permission)")
    public Object audit(ProceedingJoinPoint joinPoint, RequireAdminPermission permission) throws Throwable {
        HttpServletRequest request = currentRequest();
        AdminAudit override = ((MethodSignature) joinPoint.getSignature()).getMethod().getAnnotation(AdminAudit.class);
        if (request == null || ("GET".equalsIgnoreCase(request.getMethod()) && override == null)) {
            return joinPoint.proceed();
        }
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            record(request, override, AdminAuditLog.FAILURE, errorCode(failure), null);
            throw failure;
        }
        if (result instanceof Result body && Boolean.FALSE.equals(body.getSuccess())) {
            record(request, override, AdminAuditLog.FAILURE, body.getCode(), null);
        } else {
            record(request, override, AdminAuditLog.SUCCESS, null, result);
        }
        return result;
    }

    private void record(HttpServletRequest request, AdminAudit override, String outcome, String errorCode,
                        Object result) {
        try {
            AdminPrincipal principal = AdminPrincipalHolder.get();
            AdminAuditLog entry = new AdminAuditLog();
            if (principal != null) {
                entry.setAccountId(principal.getAccountId());
                entry.setMerchantId(principal.getMerchantId());
                entry.setUsername(principal.getUsername());
            }
            entry.setAction(override != null && !override.action().isEmpty() ? override.action()
                    : request.getMethod() + " " + routePattern(request));
            entry.setTargetType(override == null || override.targetType().isEmpty() ? null : override.targetType());
            entry.setTargetId(truncate(target(request, override, result)));
            entry.setResult(outcome);
            entry.setErrorCode(errorCode);
            entry.setClientIp(clientIpResolver.resolve(request));
            auditService.record(entry);
        } catch (RuntimeException e) {
            // The operation already happened; losing its audit row is logged loudly but must
            // not turn a completed operation into an error for the operator.
            log.error("Admin audit record could not be stored. action={} {}", request.getMethod(),
                    request.getRequestURI(), e);
        }
    }

    private static String target(HttpServletRequest request, AdminAudit override, Object result) {
        if (result != null && override != null && !override.target().isEmpty()) {
            try {
                StandardEvaluationContext context = new StandardEvaluationContext();
                context.addPropertyAccessor(new org.springframework.context.expression.MapAccessor());
                context.setVariable("result", result);
                Object value = SPEL.parseExpression(override.target()).getValue(context);
                if (value != null) {
                    return value.toString();
                }
            } catch (RuntimeException e) {
                log.warn("Audit target expression failed: {}", override.target(), e);
            }
        }
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> map && !map.isEmpty()) {
            return new TreeMap<>(map).entrySet().stream()
                    .map(variable -> variable.getKey() + "=" + variable.getValue())
                    .collect(Collectors.joining(","));
        }
        return null;
    }

    private static String routePattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? request.getRequestURI() : pattern.toString();
    }

    private static String errorCode(Throwable failure) {
        if (failure instanceof ApiStatusException apiError && apiError.getCode() != null) {
            return apiError.getCode();
        }
        return failure.getClass().getSimpleName();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_TARGET_LENGTH ? value : value.substring(0, MAX_TARGET_LENGTH);
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
