package cn.zcj.aether.domain.agent.service.agent.permission;

import lombok.extern.slf4j.Slf4j;

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

        boolean masked = false;
        for (String key : toolInput.keySet()) {
            String normalized = key.toLowerCase().replace("-", "").replace("_", "");
            if (SENSITIVE.contains(normalized) && toolInput.get(key) instanceof String val && !val.isEmpty()) {
                toolInput.put(key, "***");
                masked = true;
            }
        }
        if (masked) {
            log.debug("工具参数已脱敏: toolName={}", ctx.getToolName());
        }
        return null;
    }
}
