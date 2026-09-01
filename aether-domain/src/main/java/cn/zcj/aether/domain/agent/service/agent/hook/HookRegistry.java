package cn.zcj.aether.domain.agent.service.agent.hook;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.BaseAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook 注册与发现中心。
 * Spring Bean 自动发现 + 优先级排序 + 批量注入。
 *
 * 灵感来源：AgentScope Hook 系统 + Spring @EventListener 模式。
 */
@Slf4j
@Component
public class HookRegistry {

    @Resource
    private ApplicationContext applicationContext;

    /** 全局 Hook（注入到所有 Agent） */
    private final List<AgentHook> globalHooks = new ArrayList<>();

    @PostConstruct
    public void init() {
        // 自动发现所有 AgentHook Bean
        Map<String, AgentHook> hookBeans = applicationContext.getBeansOfType(AgentHook.class);
        globalHooks.addAll(hookBeans.values());
        globalHooks.sort(Comparator.comparingInt(AgentHook::priority));
        // O15: 将 AgentHook 的 LLM/工具拦截点桥接进唯一生命周期钩子表（单一命名空间）
        for (AgentHook hook : globalHooks) {
            registerBridge(hook);
        }

        log.info("HookRegistry 初始化完成，发现 {} 个全局 Hook: {}",
            globalHooks.size(),
            globalHooks.stream().map(h -> h.getClass().getSimpleName() + "(p=" + h.priority() + ")").toList());
    }

    /** 获取所有全局 Hook */
    public List<AgentHook> getGlobalHooks() {
        return Collections.unmodifiableList(globalHooks);
    }

    /** 将全局 Hook 注入到指定 Agent */
    public void injectTo(Agent agent) {
        if (agent instanceof BaseAgent ba) {
            for (AgentHook hook : globalHooks) {
                ba.addHook(hook);
            }
            log.debug("已将 {} 个全局 Hook 注入 Agent [{}]", globalHooks.size(), agent.getId());
        }
    }

    /** 注册运行时 Hook */
    public void register(AgentHook hook) {
        globalHooks.add(hook);
        globalHooks.sort(Comparator.comparingInt(AgentHook::priority));
        registerBridge(hook);
        log.info("注册 Hook: {} (priority={})", hook.getClass().getSimpleName(), hook.priority());
    }

    /**
     * O15: 把 AgentHook 桥接为 {@link AgentHookBridge} 注册进 lifecycleHooks 唯一表，
     * 使 PRE/POST_LLM_CALL、PRE/POST_TOOL_CALL 挂点的触发对两类 Hook 可见。
     */
    private void registerBridge(AgentHook hook) {
        AgentHookBridge bridge = new AgentHookBridge(hook);
        for (HookPoint point : bridge.points()) {
            List<LifecycleHook> list = lifecycleHooks.computeIfAbsent(point, k -> new CopyOnWriteArrayList<>());
            list.add(bridge);
            list.sort(Comparator.comparingInt(LifecycleHook::order));
        }
    }

    // ── D3 全局生命周期钩子（对齐 hermes plugins.py _hooks: Dict[str, List[Callable]]）──
    /** 按 HookPoint 分组的生命周期钩子 */
    private final Map<HookPoint, List<LifecycleHook>> lifecycleHooks = new EnumMap<>(HookPoint.class);

    /** 注册生命周期钩子：按 hook.points() 分发到各挂点列表，并按 order 排序。 */
    public void registerLifecycle(LifecycleHook hook) {
        for (HookPoint point : hook.points()) {
            List<LifecycleHook> list = lifecycleHooks.computeIfAbsent(point, k -> new CopyOnWriteArrayList<>());
            list.add(hook);
            list.sort(Comparator.comparingInt(LifecycleHook::order));
        }
        log.info("注册生命周期 Hook: {} → {}", hook.getClass().getSimpleName(), hook.points());
    }

    /** 获取某挂点的全部生命周期钩子（不可变、已按 order 排序） */
    public List<LifecycleHook> hooksFor(HookPoint point) {
        return Collections.unmodifiableList(lifecycleHooks.getOrDefault(point, List.of()));
    }

    /**
     * 触发某挂点的全部生命周期钩子。
     * 空列表短路；单钩子异常仅记日志不阻断后续（对齐 hermes invoke_hook L1911-1946 异常隔离）。
     */
    public void invokeAll(HookPoint point, HookContext ctx) {
        List<LifecycleHook> hooks = lifecycleHooks.get(point);
        if (hooks == null || hooks.isEmpty()) {
            return;
        }
        for (LifecycleHook hook : hooks) {
            try {
                hook.onHook(point, ctx);
            } catch (Exception e) {
                log.warn("生命周期 Hook 执行异常（已隔离，不阻断主流程）: point={} hook={} err={}",
                        point, hook.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
