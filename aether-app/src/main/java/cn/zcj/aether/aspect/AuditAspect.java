package cn.zcj.aether.aspect;

import cn.zcj.aether.domain.agent.service.security.annotation.Auditable;
import cn.zcj.aether.infrastructure.persistence.AuditLogRepository;
import cn.zcj.aether.messaging.AuditEventProducer;
import cn.zcj.aether.types.messaging.AuditEventMessage;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * P1: 审计 AOP 切面。拦截 @Auditable 方法，异步写入审计日志。
 *
 * <p>O18（对应 D18）：此前 {@code @Async} 方法体整体跑在审计池线程上，在复用线程中读取
 * {@code SecurityContextHolder}/{@code RequestContextHolder}（ThreadLocal 在池化线程上失效）
 * 导致审计身份漂移为 anonymous。现改为：切面方法在调用方线程<b>同步</b>捕获身份
 * （userId/username/ip/correlationId），再显式提交 {@code auditExecutor} 异步落库——
 * 身份不漂移，且 correlationId 可与请求日志串联。</p>
 */
@Slf4j
@Aspect
@Component
public class AuditAspect {

    /** 审计身份快照（调用方线程同步捕获）。 */
    private record AuditIdentity(Long userId, String username, String ip, String correlationId) {}

    /**
     * 审计仓储（可选注入）：无数据源/未装配仓储时（如测试排除数据源自动配置）
     * 审计降级为 no-op，不阻断主流程。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditLogRepository auditLogRepository;

    /** O17/O18: 统一审计池（原 @Async 隐式调度改为显式提交）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("auditExecutor")
    private ExecutorService auditExecutor;

    /**
     * P1(1.3): Kafka 优先通道。send 返回 true → 事件入队（削峰/解耦）；
     * false 或未装配 → 降级既有异步直写路径，审计不丢。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditEventProducer auditEventProducer;

    @AfterReturning("@annotation(cn.zcj.aether.domain.agent.service.security.annotation.Auditable)")
    public void logSuccess(JoinPoint joinPoint) {
        Auditable auditable = getAnnotation(joinPoint);
        if (auditable == null) return;
        AuditIdentity identity = captureIdentity();
        submitLog(joinPoint, auditable, true, null, identity);
    }

    @AfterThrowing(
        pointcut = "@annotation(cn.zcj.aether.domain.agent.service.security.annotation.Auditable)",
        throwing = "ex")
    public void logFailure(JoinPoint joinPoint, Exception ex) {
        Auditable auditable = getAnnotation(joinPoint);
        if (auditable == null) return;
        AuditIdentity identity = captureIdentity();
        submitLog(joinPoint, auditable, false, ex.getMessage(), identity);
    }

    /** 调用方线程同步提交审计写入（仓储/执行器未装配时 no-op）。 */
    private void submitLog(JoinPoint joinPoint, Auditable auditable,
            boolean success, String errorMessage, AuditIdentity identity) {
        if (auditLogRepository == null) {
            log.debug("审计仓储未装配，跳过审计写入");
            return;
        }
        if (auditExecutor == null) {
            // 执行器未装配：同步降级写入（不丢审计）
            writeLog(joinPoint, auditable, success, errorMessage, identity);
            return;
        }
        auditExecutor.execute(() -> writeLog(joinPoint, auditable, success, errorMessage, identity));
    }

    /** O18: 在调用方线程同步捕获身份（SecurityContext/RequestContext 仅此处读取）。 */
    private AuditIdentity captureIdentity() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null && auth.isAuthenticated())
                ? auth.getName() : "anonymous";
        Long userId = null;
        if (auth != null && auth.getPrincipal() instanceof Long) {
            userId = (Long) auth.getPrincipal();
        }
        return new AuditIdentity(userId, username, getClientIp(), currentCorrelationId());
    }

    private void writeLog(JoinPoint joinPoint, Auditable auditable,
            boolean success, String errorMessage, AuditIdentity identity) {
        try {
            String detail = buildDetailString(joinPoint);
            // O18: 实体无 correlationId 列，拼入 detail 供审计与请求日志串联
            if (identity.correlationId() != null) {
                detail = detail + " correlationId=" + identity.correlationId();
            }

            // P1(1.3): Kafka 优先 —— 失败（超时/未启用）降级直写，两条路径产出等价审计行
            if (auditEventProducer != null) {
                var message = new AuditEventMessage(
                        UUID.randomUUID().toString(),
                        auditable.value().getCode(),
                        auditable.resource(),
                        detail,
                        identity.userId(),
                        identity.username(),
                        identity.ip(),
                        success,
                        errorMessage,
                        identity.correlationId(),
                        Instant.now());
                if (auditEventProducer.send(message)) {
                    return;
                }
            }

            AuditLogRepository.AuditLogEntity entity =
                    new AuditLogRepository.AuditLogEntity();
            entity.setUserId(identity.userId());
            entity.setUsername(identity.username());
            entity.setAction(auditable.value().getCode());
            entity.setResource(auditable.resource());
            entity.setDetail(detail);
            entity.setIpAddress(identity.ip());
            entity.setSuccess(success);
            entity.setErrorMessage(errorMessage);

            auditLogRepository.save(entity);
        } catch (Exception e) {
            log.error("审计日志写入失败", e);
        }
    }

    /** O18: 读取 correlationId（MdcFilter 注入），供审计与请求日志串联。 */
    private String currentCorrelationId() {
        try {
            Map<String, String> mdc = org.slf4j.MDC.getCopyOfContextMap();
            return mdc != null ? mdc.get("correlationId") : null;
        } catch (Exception ignored) {
            return null;
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
