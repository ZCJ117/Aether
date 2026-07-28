package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P1-#9: 敏感参数脱敏规则。
 * 检测 apiKey/password/secret/token → 替换为 "***"
 */
@Slf4j
public class SensitiveArgMaskRule implements PermissionRule {

    private static final Set<String> SENSITIVE = Set.of(
            "apikey", "api_key", "password", "passwd",
            "secret", "token", "access_token", "credential", "private_key");

    @Override
    public String name() {
        return "sensitive-arg-mask";
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public PermissionDecision evaluate(PermissionContext ctx) {
        Map<String, Object> toolInput = ctx.getToolInput();
        if (toolInput == null || toolInput.isEmpty()) {
            return null;
        }

        // 【H4-步骤1】不再原地修改 toolInput。
        // 仅记录被脱敏的 key 列表到上下文属性中，供展示/日志层使用。
        // 实际脱敏动作上移到 MaskingUtil.forDisplay() ——
        // 在 AgentEventPublisher 发出事件和 log.debug 输出时对事件副本做脱敏。
        List<String> maskedKeys = MaskingUtil.collectMaskedKeys(toolInput);
        if (!maskedKeys.isEmpty()) {
            ctx.getAttributes().put("maskedKeys", maskedKeys);
            log.debug("工具参数含敏感键（将于展示层脱敏）: toolName={}, keys={}",
                    ctx.getToolName(), maskedKeys);
        }
        return null; // 永不放行/拒绝，由其他规则决定
    }
}
