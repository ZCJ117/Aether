package cn.zcj.aether.domain.agent.service.agent.hook;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.BaseAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;

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
        log.info("注册 Hook: {} (priority={})", hook.getClass().getSimpleName(), hook.priority());
    }
}
