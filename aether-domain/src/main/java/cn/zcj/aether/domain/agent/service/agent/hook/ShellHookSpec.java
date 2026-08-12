package cn.zcj.aether.domain.agent.service.agent.hook;

/**
 * Shell 命令式钩子规格 — 对齐 hermes shell_hooks.py ShellHookSpec（L162）。
 * 字段：point（事件）/ command（命令）/ timeoutMs（超时）。
 */
public record ShellHookSpec(HookPoint point, String command, int timeoutMs) {

    /** 默认超时 60s（对齐 shell_hooks.py DEFAULT_TIMEOUT_SECONDS=60） */
    public static final int DEFAULT_TIMEOUT_MS = 60_000;
    /** 最大超时 300s（对齐 shell_hooks.py MAX_TIMEOUT_SECONDS=300） */
    public static final int MAX_TIMEOUT_MS = 300_000;

    public ShellHookSpec {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("ShellHookSpec.command 不能为空");
        }
        if (point == null) {
            throw new IllegalArgumentException("ShellHookSpec.point 不能为空");
        }
        // 非法超时回退默认，超上限钳制（对齐 register_from_config 校验规则）
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        } else if (timeoutMs > MAX_TIMEOUT_MS) {
            timeoutMs = MAX_TIMEOUT_MS;
        }
    }
}
