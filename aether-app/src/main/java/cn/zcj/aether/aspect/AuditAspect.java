package cn.zcj.aether.aspect;

import cn.zcj.aether.domain.agent.service.security.annotation.Auditable;
import cn.zcj.aether.infrastructure.persistence.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * P1: 审计 AOP 切面。拦截 @Auditable 方法，异步写入审计日志。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditLogRepository auditLogRepository;

    @Async
    @AfterReturning("@annotation(cn.zcj.aether.domain.agent.service.security.annotation.Auditable)")
    public void logSuccess(JoinPoint joinPoint) {
        Auditable auditable = getAnnotation(joinPoint);
        if (auditable == null) return;
        writeLog(joinPoint, auditable, true, null);
    }

    @Async
    @AfterThrowing(
        pointcut = "@annotation(cn.zcj.aether.domain.agent.service.security.annotation.Auditable)",
        throwing = "ex")
    public void logFailure(JoinPoint joinPoint, Exception ex) {
        Auditable auditable = getAnnotation(joinPoint);
        if (auditable == null) return;
        writeLog(joinPoint, auditable, false, ex.getMessage());
    }

    private void writeLog(JoinPoint joinPoint, Auditable auditable,
            boolean success, String errorMessage) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String username = (auth != null && auth.isAuthenticated())
                    ? auth.getName() : "anonymous";
            Long userId = null;
            if (auth != null && auth.getPrincipal() instanceof Long) {
                userId = (Long) auth.getPrincipal();
            }

            String detail = buildDetailString(joinPoint);

            AuditLogRepository.AuditLogEntity entity =
                    new AuditLogRepository.AuditLogEntity();
            entity.setUserId(userId);
            entity.setUsername(username);
            entity.setAction(auditable.value().getCode());
            entity.setResource(auditable.resource());
            entity.setDetail(detail);
            entity.setIpAddress(getClientIp());
            entity.setSuccess(success);
            entity.setErrorMessage(errorMessage);

            auditLogRepository.save(entity);
        } catch (Exception e) {
            log.error("审计日志写入失败", e);
        }
    }

    private Auditable getAnnotation(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getAnnotation(Auditable.class);
    }

    private String buildDetailString(JoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) return "{}";
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < args.length && i < 5; i++) {
                if (i > 0) sb.append(", ");
                Object arg = args[i];
                if (arg == null) sb.append("null");
                else sb.append(arg.getClass().getSimpleName());
            }
            if (args.length > 5) sb.append(", ...");
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String xForwardedFor = attrs.getRequest().getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                    return xForwardedFor.split(",")[0].trim();
                }
                return attrs.getRequest().getRemoteAddr();
            }
        } catch (Exception ignored) { }
        return "unknown";
    }
}
