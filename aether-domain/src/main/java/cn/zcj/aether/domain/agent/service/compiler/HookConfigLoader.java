package cn.zcj.aether.domain.agent.service.compiler;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.agent.hook.ShellHook;
import cn.zcj.aether.domain.agent.service.agent.hook.ShellHookSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置驱动 Hook 注册器 — 对齐 hermes shell_hooks.py register_from_config（L204）。
 * <p>解析工作流 YAML 的可选 {@code hooks:} 段，将每个条目注册为 {@link ShellHook}。
 * 未知 point / 畸形条目 → warn 跳过（对齐 hermes 未知 event 跳过 + 拼写建议）；
 * 相同 (point, command) 去重（对齐 hermes _registered 去重）。</p>
 */
@Slf4j
@Component
public class HookConfigLoader {

    private final HookRegistry hookRegistry;

    /** 已注册 spec 去重 key（对齐 hermes _registered 集合）；并发安全，防并发 compile() 竞态 */
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    public HookConfigLoader(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    /**
     * 解析并注册 hooks 配置段。
     *
     * @param hookConfigs Module.hooks 列表（可为 null/空 → 无操作）
     */
    public void load(List<AiAgentConfigTableVO.Module.HookConfigVO> hookConfigs) {
        if (hookConfigs == null || hookConfigs.isEmpty()) {
            return;
        }
        for (AiAgentConfigTableVO.Module.HookConfigVO cfg : hookConfigs) {
            HookPoint point = parsePoint(cfg.getPoint());
            if (point == null) {
                log.warn("跳过未知 HookPoint: {}（可用: {}）", cfg.getPoint(), Arrays.toString(HookPoint.values()));
                continue;
            }
            String command = cfg.getCommand();
            if (command == null || command.isBlank()) {
                log.warn("跳过空命令的 ShellHook 配置: point={}", point);
                continue;
            }
            int timeoutMs = cfg.getTimeoutMs() != null
                    ? cfg.getTimeoutMs() : ShellHookSpec.DEFAULT_TIMEOUT_MS;
            ShellHookSpec spec = new ShellHookSpec(point, command, timeoutMs);
            String dedupKey = point + "|" + command;
            if (!registered.add(dedupKey)) {
                log.debug("ShellHook 已注册，跳过重复: {}", dedupKey);
                continue;
            }
            hookRegistry.registerLifecycle(new ShellHook(spec));
            log.info("配置驱动 ShellHook 已注册: point={} command={} timeoutMs={}",
                    point, command, spec.timeoutMs());
        }
    }

    /** 宽松解析 HookPoint 名；未知返回 null（对齐 register_hook 宽进语义） */
    private static HookPoint parsePoint(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return HookPoint.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
