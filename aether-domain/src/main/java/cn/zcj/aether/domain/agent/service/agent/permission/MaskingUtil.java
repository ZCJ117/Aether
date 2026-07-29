package cn.zcj.aether.domain.agent.service.agent.permission;

import java.util.*;

/**
 * 敏感参数脱敏工具。
 * 灵感来源：AgentScope 规则表快照语义 —— 权限规则永不修改真实输入。
 *
 * <p>脱敏动作统一上移到展示/日志层：
 * <ul>
 *   <li>AgentEventPublisher 发出工具调用事件时对事件副本做脱敏</li>
 *   <li>log.debug 输出时对参数副本做脱敏</li>
 * </ul>
 *
 * <p>原则：{@link #forDisplay(Map)} 总是返回一个新 Map，绝不修改入参。
 */
public final class MaskingUtil {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "apikey", "api_key", "apikey", "password", "passwd",
            "secret", "token", "access_token", "credential", "private_key",
            "authorization", "bearer", "auth", "key"
    );

    private MaskingUtil() { /* 工具类禁止实例化 */ }

    /**
     * 返回脱敏后的深拷贝 Map，原 Map 不受影响。
     *
     * @param input 原始工具参数（可为 null）
     * @return 脱敏后的副本（敏感值替换为 "***"）
     */
    public static Map<String, Object> forDisplay(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return input == null ? Map.of() : new LinkedHashMap<>(input);
        }
        Map<String, Object> copy = new LinkedHashMap<>(input);
        for (String key : copy.keySet()) {
            if (isSensitive(key)) {
                Object val = copy.get(key);
                if (val instanceof String && !((String) val).isEmpty()) {
                    copy.put(key, "***");
                }
            }
        }
        return copy;
    }

    /**
     * 判断 key 是否为敏感键（忽略大小写和分隔符）。
     */
    public static boolean isSensitive(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "");
        return SENSITIVE_KEYS.contains(normalized);
    }

    /**
     * 获取被脱敏的 key 列表（用于日志记录）。
     */
    public static List<String> collectMaskedKeys(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return List.of();
        List<String> masked = new ArrayList<>();
        for (String key : input.keySet()) {
            if (isSensitive(key) && input.get(key) instanceof String s && !s.isEmpty()) {
                masked.add(key);
            }
        }
        return Collections.unmodifiableList(masked);
    }
}
