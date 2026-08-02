package cn.zcj.aether.domain.agent.service.security.annotation;

import cn.zcj.aether.types.enums.AuditAction;
import java.lang.annotation.*;

/**
 * P1: 审计注解。标记需要审计日志的方法。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Auditable {
    AuditAction value();
    String resource() default "";
}
