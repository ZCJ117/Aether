package cn.zcj.aether.config;

import cn.zcj.aether.domain.agent.service.agent.intervention.InterventionHandler;
import cn.zcj.aether.domain.agent.service.agent.intervention.PermissionInterventionHandler;
import cn.zcj.aether.domain.agent.service.agent.permission.InjectionGuardRule;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionEngine;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Resource;

/**
 * H4-步骤7: 干预处理器配置 —— 将 PermissionInterventionHandler 注册为 Spring Bean。
 *
 * <p>通过 {@code aether.intervention.enabled} 属性控制是否启用（默认 true）。
 * 生产环境建议启用；开发/测试环境可关闭以减少日志噪音。
 */
@Slf4j
@Configuration
public class InterventionConfig {

    @Resource
    private PermissionEngine permissionEngine;

    /** 是否启用干预处理器（默认 true） */
    @Value("${aether.intervention.enabled:true}")
    private boolean interventionEnabled;

    /** 干预处理器的权限模式（默认 DEFAULT） */
    @Value("${aether.intervention.permission-mode:DEFAULT}")
    private String permissionModeStr;

    @Bean
    public InterventionHandler interventionHandler() {
        if (!interventionEnabled) {
            log.info("干预处理器已禁用 (aether.intervention.enabled=false)");
            return null;
        }

        InjectionGuardRule injectionGuard = permissionEngine.getInjectionGuardRule();
        PermissionMode mode;
        try {
            mode = PermissionMode.valueOf(permissionModeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("无效的权限模式配置 [{}]，使用默认值 DEFAULT", permissionModeStr);
            mode = PermissionMode.DEFAULT;
        }

        PermissionInterventionHandler handler = new PermissionInterventionHandler(injectionGuard, mode);
        log.info("干预处理器已注册: mode={}", mode);
        return handler;
    }
}
