package cn.zcj.aether.domain.agent.service.agent.permission;

import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 权限模式解析工具 —— 统一 metadata → PermissionMode 的转换，消除读取点分散。
 *
 * <p>写入侧（{@code ChatService}）与读取侧（{@code PermissionMiddleware}、
 * {@code ToolExecutor}）必须共同引用 {@link #METADATA_KEY}，不得各自硬编码字面量。
 */
@Slf4j
public final class PermissionModes {

    /** RuntimeContext.metadata 中承载权限模式的键。 */
    public static final String METADATA_KEY = "permissionMode";

    private PermissionModes() {}

    /**
     * 从运行时上下文解析权限模式。非法值/缺失一律回落 DEFAULT 并告警，不抛异常。
     *
     * @param ctx 运行时上下文（可为 null）
     * @return 解析结果，恒非 null
     */
    public static PermissionMode resolve(RuntimeContext ctx) {
        if (ctx == null || ctx.metadata() == null) {
            return PermissionMode.DEFAULT;
        }

        Object raw = ctx.metadata().get(METADATA_KEY);
        if (raw == null) {
            return PermissionMode.DEFAULT;
        }

        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            return PermissionMode.DEFAULT;
        }

        try {
            return PermissionMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("非法权限模式值 [{}]，回落 {}", raw, PermissionMode.DEFAULT);
            return PermissionMode.DEFAULT;
        }
    }
}
