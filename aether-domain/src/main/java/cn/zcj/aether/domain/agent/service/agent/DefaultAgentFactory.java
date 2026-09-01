package cn.zcj.aether.domain.agent.service.agent;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.hook.AgentHook;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.agent.impl.PlanActAgent;
import cn.zcj.aether.domain.agent.service.agent.impl.ReActAgent;
import cn.zcj.aether.domain.agent.service.agent.observability.AgentMetrics;
import cn.zcj.aether.domain.agent.service.agent.observability.BackgroundReviewer;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.observability.ModelCallObservability;
import cn.zcj.aether.domain.agent.service.curation.CurationPipeline;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
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

    @Resource
    private CurationPipeline curationPipeline;

    @Resource
    private ExternalNotes externalNotes;

    @Resource
    private TokenEstimator tokenEstimator;

    @Resource
    private HookRegistry hookRegistry;

    /** P1(1.1): 模型调用等待可观测（actuator 存在时装配，否则 null → Agent 侧 no-op） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModelCallObservability modelCallObservability;

    /** O15: 配置/Bean 驱动的类型注册表（优先于内置工厂） */
    @Resource
    private AgentTypeRegistry agentTypeRegistry;

    /** 内置工厂注册表：agentType -> AgentFactory */
    private final Map<String, AgentFactory> factoryMap = new ConcurrentHashMap<>();

    public DefaultAgentFactory() {
        // 内置工厂：ReActAgent
        factoryMap.put("react", new AgentFactory() {
            @Override
            public String supportedType() { return "react"; }
            @Override
            public Agent create(AgentConfig config) {
                ChatModel chatModel = resolveChatModel(config);
                AgentEventPublisher publisher = resolveBean(AgentEventPublisher.class);
                CheckpointCollector checkpointCollector = resolveBean(CheckpointCollector.class);
                TokenBudget tokenBudget = createTokenBudget(config);
                var pricingRegistry = resolveBean(
                        cn.zcj.aether.domain.agent.service.context.ModelPricingRegistry.class);
                ReActAgent agent = new ReActAgent(config, chatModel, modelInvoker,
                        toolExecutor, contextManager, publisher, checkpointCollector,
                        tokenBudget, pricingRegistry, curationPipeline, externalNotes);
                injectHooks(agent);
                // D3: 注入生命周期钩子分发器（API 请求挂点）
                if (hookRegistry != null) {
                    agent.setHookRegistry(hookRegistry);
                }
                // P1(1.1): 注入模型等待可观测（waiting-threads gauge + 超时计数）
                agent.setModelCallObservability(modelCallObservability);
                return agent;
            }
        });

        // P1-#4: PlanActAgent 工厂
        factoryMap.put("plan_act", new AgentFactory() {
            @Override
            public String supportedType() { return "plan_act"; }
            @Override
            public Agent create(AgentConfig config) {
                ChatModel chatModel = resolveChatModel(config);
                AgentEventPublisher publisher = resolveBean(AgentEventPublisher.class);
                CheckpointCollector collector = resolveBean(CheckpointCollector.class);
                BackgroundReviewer reviewer = resolveBean(BackgroundReviewer.class);
                AgentMetrics metrics = resolveBean(AgentMetrics.class);
                return new PlanActAgent(config, chatModel, modelInvoker,
                        toolExecutor, contextManager, publisher, collector,
                        createTokenBudget(config), curationPipeline, externalNotes, reviewer, metrics);
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
        // O15: 先查 AgentTypeRegistry（Bean/配置驱动，可覆盖内置），回退内置工厂
        AgentFactory factory = agentTypeRegistry.resolve(type).orElseGet(() -> factoryMap.get(type));
        if (factory == null) {
            throw new IllegalArgumentException("未知的 Agent 类型: " + type
                    + "，可用: " + agentTypeRegistry.registeredTypes());
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

    /** 可选解析 Spring Bean，不存在时返回 null */
    private <T> T resolveBean(Class<T> clazz) {
        try {
            return applicationContext.getBean(clazz);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Phase 9: 创建 per-Agent TokenBudget */
    private TokenBudget createTokenBudget(AgentConfig config) {
        int contextWindow = tokenEstimator.getContextWindow(config.getModelRef());
        int instructionTokens = tokenEstimator.estimate(config.getInstruction() != null ? config.getInstruction() : "");
        int toolSchemaTokens = (config.getToolNames() != null ? config.getToolNames().size() : 0) * 200;
        int estimatedFixedOverhead = instructionTokens + toolSchemaTokens;
        return new TokenBudget(contextWindow, estimatedFixedOverhead);
    }
}
