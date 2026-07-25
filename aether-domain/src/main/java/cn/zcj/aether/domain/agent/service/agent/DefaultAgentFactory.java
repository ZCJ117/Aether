package cn.zcj.aether.domain.agent.service.agent;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.hook.AgentHook;
import cn.zcj.aether.domain.agent.service.agent.impl.ReActAgent;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 Agent 工厂，通过 Spring 上下文动态获取依赖。
 * 支持通过 Spring Bean 自动注册 AgentHook。
 */
@Slf4j
@Component
public class DefaultAgentFactory {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private ModelInvoker modelInvoker;

    @Resource
    private ToolExecutor toolExecutor;

    @Resource
    private ContextManager contextManager;

    /** 工厂注册表：agentType -> AgentFactory */
    private final Map<String, AgentFactory> factoryMap = new ConcurrentHashMap<>();

    public DefaultAgentFactory() {
        // 内置工厂：ReActAgent
        factoryMap.put("react", new AgentFactory() {
            @Override
            public String supportedType() { return "react"; }
            @Override
            public Agent create(AgentConfig config) {
                ChatModel chatModel = resolveChatModel(config);
                // P0-6: 注入 AgentEventPublisher（可选）
                AgentEventPublisher publisher = null;
                try {
                    publisher = applicationContext.getBean(AgentEventPublisher.class);
                } catch (Exception ignored) {
                    // 无 EventPublisher 时不影响 Agent 创建
                }
                // P0-#8: 注入 CheckpointCollector（可选）
                CheckpointCollector checkpointCollector = null;
                try {
                    checkpointCollector = applicationContext.getBean(CheckpointCollector.class);
                } catch (Exception ignored) {
                    // 无 CheckpointCollector 时不影响 Agent 创建
                }
                ReActAgent agent = new ReActAgent(config, chatModel, modelInvoker,
                        toolExecutor, contextManager, publisher, checkpointCollector);
                injectHooks(agent);
                return agent;
            }
        });
    }

    /**
     * 注册自定义 AgentFactory（供 SPI 或插件使用）
     */
    public void registerFactory(String type, AgentFactory factory) {
        factoryMap.put(type, factory);
        log.info("注册 Agent 工厂: type={}", type);
    }

    /**
     * 从 AgentConfig 创建 Agent 实例。
     */
    public Agent create(AgentConfig config) {
        String type = config.getAgentType() != null ? config.getAgentType() : "react";
        AgentFactory factory = factoryMap.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("未知的 Agent 类型: " + type + "，可用: " + factoryMap.keySet());
        }
        return factory.create(config);
    }

    /** 从 AgentConfig 的 modelRef 解析对应的 ChatModel Bean */
    private ChatModel resolveChatModel(AgentConfig config) {
        String beanName = "chatModel-" + config.getName();
        if (applicationContext.containsBean(beanName)) {
            return applicationContext.getBean(beanName, ChatModel.class);
        }
        // 回退到默认 chatModel
        if (applicationContext.containsBean("chatModel")) {
            return applicationContext.getBean("chatModel", ChatModel.class);
        }
        return applicationContext.getBean(ChatModel.class);
    }

    /** 注入所有注册的 AgentHook */
    private void injectHooks(ReActAgent agent) {
        Map<String, AgentHook> hookBeans = applicationContext.getBeansOfType(AgentHook.class);
        hookBeans.values().forEach(agent::addHook);
    }
}
