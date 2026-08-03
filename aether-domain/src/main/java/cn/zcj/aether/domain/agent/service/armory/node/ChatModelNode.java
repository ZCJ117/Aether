package cn.zcj.aether.domain.agent.service.armory.node;

import cn.zcj.aether.domain.agent.model.entity.ArmoryCommandEntity;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.zcj.aether.domain.agent.service.armory.AbstractArmorySupport;
import cn.zcj.aether.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import cn.zcj.aether.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import cn.zcj.aether.domain.agent.service.model.failover.ModelErrorClassifier;
import cn.zcj.aether.domain.agent.service.model.failover.ModelRoute;
import cn.zcj.aether.domain.agent.service.model.failover.ResilientChatModelExecutor;
import cn.zcj.aether.domain.agent.service.tool.McpToolAdapter;
import cn.zcj.aether.domain.agent.service.tool.SkillsToolAdapter;
import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// NOTE 13，路由到ChatModelNode节点，这个节点的作用是根据配置构建ChatModel实例，
//  并放入上下文对象中，供后续节点使用，最后路由到AgentNode节点

@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource
    private AgentNode agentNode;

    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    private McpToolAdapter mcpToolAdapter;

    @Resource
    private SkillsToolAdapter skillsToolAdapter;

    @Resource
    private ModelProviderRegistry modelProviderRegistry;  // P0-2 新增

    @Resource
    private ModelErrorClassifier modelErrorClassifier;  // P1 容错：错误分类器

    @Resource
    private cn.zcj.aether.domain.agent.service.agent.permission.PermissionEngine permissionEngine;  // P1-#9

    // P1 工具注册修复：注入内置 Tool Bean 并在 registerToolsToRegistry 中注册
    @Resource
    private cn.zcj.aether.domain.agent.service.retrieval.CodeExplorer codeExplorer;
    @Resource
    private cn.zcj.aether.domain.agent.service.retrieval.DocRetriever docRetriever;
    @Resource
    private cn.zcj.aether.domain.agent.service.tool.SessionSearchTool sessionSearchTool;
    @Resource
    private cn.zcj.aether.domain.agent.service.notes.NotesTools.TodoWriteTool todoWriteTool;
    @Resource
    private cn.zcj.aether.domain.agent.service.notes.NotesTools.NoteWriteTool noteWriteTool;

    /** M2: 子Agent委派工具 —— SubAgentOrchestrator 建模为 Tool，LLM 可通过 tool_use 发起委派 */
    @Resource
    private cn.zcj.aether.domain.agent.service.subagent.SubAgentOrchestrator subAgentOrchestrator;

    /** M4: MCP 连接缓存 —— 按 name@baseUri 去重，避免重复创建 SSE/Stdio 连接 */
    private final Map<String, ToolCallback[]> mcpCallbackCache = new ConcurrentHashMap<>();

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        // 获取配置对象
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();

        // 构建mcp工具回调
        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if (null != toolMcpList && !toolMcpList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                String key = dedupKey(toolMcp);
                ToolCallback[] toolCallbacks = mcpCallbackCache.computeIfAbsent(key, k -> {
                    try {
                        TooMcpCreateService tooMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
                        ToolCallback[] built = tooMcpCreateService.buildToolCallback(toolMcp);
                        log.info("MCP 连接已创建: key={}", key);
                        return built;
                    } catch (Exception e) {
                        throw new RuntimeException("MCP 连接创建失败: key=" + key, e);
                    }
                });
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // 构建skills服务
        if (null != toolSkillsList && !toolSkillsList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
                ToolCallback[] toolCallbacks = toolSkillsCreateService.buildToolCallback(toolSkills);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // P0-2 改造：通过 ModelProvider 创建 ChatModel（自动处理 toolCallbacks）
        ModelProvider provider = dynamicContext.getModelProvider();
        ModelConfig modelConfig = dynamicContext.getModelConfig();
        ChatModel rawChatModel = provider.createChatModelWithTools(modelConfig, toolCallbackList);

        // P1 容错：包装为 ResilientChatModelExecutor（实现 ChatModel 接口，透明装饰）
        ChatModel chatModel = wrapWithFailover(rawChatModel, modelConfig, provider,
                chatModelConfig, aiAgentConfigTableVO.getModule().getAiApi());

        dynamicContext.setChatModel(chatModel);

        // 注册工具到 ToolRegistry（AgentRuntime → ToolExecutor 调用链路）
        registerToolsToRegistry(toolMcpList, toolSkillsList);

        // 校验工具定义就绪
        validateToolDefinitions(toolCallbackList);

        // 注册全局默认 ChatModel Bean（现在是容错包装后的实例）
        registerBean("chatModel", ChatModel.class, chatModel);

        // C1: 为每个 Agent 创建独立 ChatModel Bean（按 toolNames 过滤工具）
        // 借鉴 AgentScope Java 的 per-agent Toolkit 深拷贝 + cc-haha 的 allowlist 模式
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();
        if (agents != null) {
            for (var agent : agents) {
                registerPerAgentChatModel(agent, dynamicContext, toolCallbackList,
                        chatModelConfig, aiAgentConfigTableVO.getModule().getAiApi());
            }
        }

        // P1-#9: 注入 YAML 配置的工具安全策略到 PermissionEngine
        configureToolSecurity(aiAgentConfigTableVO.getModule());

        return router(requestParameter, dynamicContext);
    }

    /**
     * C1 改造：为每个 Agent 创建独立的 ChatModel Bean。
     * 根据 Agent 的 toolNames 配置过滤 ToolCallback，实现 Agent 级工具作用域。
     *
     * 借鉴 AgentScope Java 的 per-agent Toolkit 深拷贝模式 + cc-haha 的 allowlist 设计。
     */
    private void registerPerAgentChatModel(
            AiAgentConfigTableVO.Module.Agent agent,
            DefaultArmoryFactory.DynamicContext dynamicContext,
            List<ToolCallback> allToolCallbacks,
            AiAgentConfigTableVO.Module.ChatModel chatModelConfig,
            AiAgentConfigTableVO.Module.AiApi aiApiConfig) {

        // 计算该 Agent 允许的工具名集合
        Set<String> allowedToolNames = resolveToolNames(agent);

        // 优化：无工具定制 + 无模型定制 → 复用全局 ChatModel，无需创建 per-agent Bean
        if (allowedToolNames == null && (agent.getModel() == null || agent.getModel().getModelId() == null)) {
            log.debug("Agent [{}] 使用全局工具和模型，跳过 per-agent ChatModel", agent.getName());
            return;
        }

        // 按 allowlist 过滤 ToolCallback
        List<ToolCallback> filteredCallbacks;
        if (allowedToolNames == null) {
            // null = 全部工具（通配符 "*" 或未配置）
            filteredCallbacks = allToolCallbacks;
        } else {
            filteredCallbacks = allToolCallbacks.stream()
                .filter(tc -> {
                    try {
                        String name = tc.getToolDefinition().name();
                        return allowedToolNames.contains(name);
                    } catch (Exception e) {
                        log.warn("获取工具定义失败，保守保留该工具: {}", e.getMessage());
                        return true; // 无法获取定义时保守保留
                    }
                })
                .toList();
            log.info("Agent [{}] 工具过滤: {} → {} 个工具",
                    agent.getName(), allToolCallbacks.size(), filteredCallbacks.size());
        }

        // 处理 ModelConfig：优先 agent 级，回退全局
        ModelConfig globalConfig = dynamicContext.getModelConfig();
        String modelId, baseUrl, apiKey;
        if (agent.getModel() != null && agent.getModel().getModelId() != null) {
            modelId = agent.getModel().getModelId();
            baseUrl = agent.getModel().getBaseUrl();
            apiKey = agent.getModel().getApiKey();
        } else {
            modelId = globalConfig.getModelId();
            baseUrl = null;
            apiKey = null;
        }
        if (baseUrl == null) baseUrl = globalConfig.getBaseUrl();
        if (apiKey == null) apiKey = globalConfig.getApiKey();

        ModelConfig agentModelConfig = ModelConfig.builder()
                .modelId(modelId)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath(globalConfig.getCompletionsPath())
                .embeddingsPath(globalConfig.getEmbeddingsPath())
                .build();

        ModelProvider agentProvider = modelProviderRegistry.resolve(modelId);
        ChatModel rawAgentModel = agentProvider.createChatModelWithTools(agentModelConfig, filteredCallbacks);

        // P1 容错：per-agent ChatModel 也包装容错执行器
        ChatModel agentChatModel = wrapWithFailover(rawAgentModel, agentModelConfig, agentProvider,
                chatModelConfig, aiApiConfig);

        String beanName = "chatModel-" + agent.getName();
        registerBean(beanName, ChatModel.class, agentChatModel);

        log.info("Agent [{}] ChatModel 已注册(含容错包装): beanName={}, modelId={}, tools={}",
                agent.getName(), beanName, modelId, filteredCallbacks.size());
    }

    /**
     * 解析 Agent 允许的工具名集合。
     * 返回 null 表示全部工具（通配符或未配置），返回空 Set 表示无工具。
     */
    private Set<String> resolveToolNames(AiAgentConfigTableVO.Module.Agent agent) {
        List<String> rawNames = agent.getToolNames();
        if (rawNames == null || rawNames.isEmpty()) {
            return null; // 全部工具（向后兼容）
        }
        if (rawNames.size() == 1 && "*".equals(rawNames.get(0))) {
            return null; // 通配符 = 全部工具
        }
        return new HashSet<>(rawNames);
    }

    /**
     * 将 MCP 和 Skills 工具适配为 Tool 接口并注册到 ToolRegistry
     *
     * 此步骤是 AgentRuntime → ToolExecutor → ToolRegistry 调用链路的关键：
     * 没有注册，LLM 返回的 tool_call 将因 "Tool not found" 而失败。
     */
    private void registerToolsToRegistry(
            List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList,
            List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList) {

        if (toolMcpList != null) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                try {
                    Tool tool = mcpToolAdapter.adapt(toolMcp);
                    toolRegistry.register(tool);
                    log.info("MCP 工具已注册: {}", tool.name());
                } catch (Exception e) {
                    log.error("MCP 工具注册失败: {}", extractMcpName(toolMcp), e);
                }
            }
        }

        if (toolSkillsList != null) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
                try {
                    Tool tool = skillsToolAdapter.adapt(toolSkills);
                    toolRegistry.register(tool);
                    log.info("Skills 工具已注册: {}", tool.name());
                } catch (Exception e) {
                    log.error("Skills 工具注册失败: type={} path={}", toolSkills.getType(), toolSkills.getPath(), e);
                }
            }
        }

        // P1 修复：注册内置 Tool Bean（CodeExplorer, DocRetriever, SessionSearchTool, NotesTools）
        registerBuiltinTool(codeExplorer, "CodeExplorer");
        registerBuiltinTool(docRetriever, "DocRetriever");
        registerBuiltinTool(sessionSearchTool, "SessionSearchTool");
        registerBuiltinTool(todoWriteTool, "TodoWriteTool");
        registerBuiltinTool(noteWriteTool, "NoteWriteTool");

        // M2: 注册子Agent委派工具 —— 将 SubAgentOrchestrator 建模为 Tool，
        // LLM 可通过 tool_use 自然发起子Agent派遣，自动继承 P0 校验/重试/审批链路
        try {
            var delegationTool = new cn.zcj.aether.domain.agent.service.subagent
                    .SubAgentDelegationTool(subAgentOrchestrator);
            toolRegistry.register(delegationTool);
            log.info("委派工具已注册: {} (delegate_to_subagent)", delegationTool.name());
        } catch (Exception e) {
            log.warn("委派工具注册失败（不阻断启动）: {}", e.getMessage());
        }
    }

    /**
     * P1 修复：注册单个内置工具到 ToolRegistry，失败时仅日志告警不阻断启动。
     */
    private void registerBuiltinTool(Tool tool, String label) {
        if (tool == null) {
            log.info("内置工具 Bean 未就绪，跳过: {}", label);
            return;
        }
        try {
            toolRegistry.register(tool);
            log.info("内置工具已注册: {} (name={})", label, tool.name());
        } catch (Exception e) {
            log.error("内置工具注册失败: {}", label, e);
        }
    }

    private String extractMcpName(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        if (toolMcp.getSse() != null) return toolMcp.getSse().getName();
        if (toolMcp.getStdio() != null) return toolMcp.getStdio().getName();
        if (toolMcp.getLocal() != null) return toolMcp.getLocal().getName();
        return "unknown";
    }

    /**
     * 校验工具定义是否已就绪
     * MCP SSE 连接初始化期间，ToolCallback.getToolDefinition() 可能尚未返回完整 schema。
     * 等待最多 3 次 × 500ms，确保工具定义完整后再注册 ChatModel Bean。
     */
    private void validateToolDefinitions(List<ToolCallback> toolCallbacks) {
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            log.info("无工具回调需校验，跳过");
            return;
        }

        int totalTools = toolCallbacks.size();
        for (int retry = 0; retry < 3; retry++) {
            long nullCount = toolCallbacks.stream()
                    .filter(tc -> {
                        try {
                            return tc.getToolDefinition() == null;
                        } catch (Exception e) {
                            log.warn("获取工具定义异常: {}", e.getMessage());
                            return true;
                        }
                    })
                    .count();

            if (nullCount == 0) {
                log.info("工具定义校验通过: {}/{} 个工具已就绪", totalTools, totalTools);
                return;
            }

            if (retry < 2) {
                log.warn("工具定义未就绪 ({}/{} 为空)，等待 500ms 后重试 ({}/{})",
                        nullCount, totalTools, retry + 1, 3);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                log.warn("工具定义校验未完全通过: {} 个工具中仍有 {} 个未就绪，继续注册",
                        totalTools, nullCount);
            }
        }
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

    /**
     * M4: 构建 MCP 连接去重 key。
     * SSE: name@baseUri | Stdio: name@command | Local: name
     */
    private String dedupKey(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        if (toolMcp.getSse() != null) {
            return "sse:" + toolMcp.getSse().getName() + "@" + toolMcp.getSse().getBaseUri();
        }
        if (toolMcp.getStdio() != null) {
            var params = toolMcp.getStdio().getServerParameters();
            String cmd = params != null ? params.getCommand() : "unknown";
            return "stdio:" + toolMcp.getStdio().getName() + "@" + cmd;
        }
        if (toolMcp.getLocal() != null) {
            return "local:" + toolMcp.getLocal().getName();
        }
        return "unknown:" + System.identityHashCode(toolMcp);
    }

    /**
     * P1-#9: 从 YAML 配置注入工具安全策略到 PermissionEngine。
     * 读取 module.toolSecurity 的 allowlist/denylist 并设置到 ToolAllowlistRule。
     */
    private void configureToolSecurity(AiAgentConfigTableVO.Module module) {
        var toolSecurity = module.getToolSecurity();
        if (toolSecurity == null) return;

        var rule = permissionEngine.getToolAllowlistRule();
        if (rule == null) return;

        if (toolSecurity.getAllowlist() != null && !toolSecurity.getAllowlist().isEmpty()) {
            rule.setAllowlist(new java.util.HashSet<>(toolSecurity.getAllowlist()));
            log.info("工具白名单已配置: {}", toolSecurity.getAllowlist());
        }
        if (toolSecurity.getDenylist() != null && !toolSecurity.getDenylist().isEmpty()) {
            rule.setDenylist(new java.util.HashSet<>(toolSecurity.getDenylist()));
            log.info("工具黑名单已配置: {}", toolSecurity.getDenylist());
        }
    }

    /**
     * P1 容错：将原始 ChatModel 包装为 ResilientChatModelExecutor。
     *
     * <p>解析 YAML 中的 fallback 模型链（chat-model.fallbackModels 或 chat-model.fallback），
     * 每个 fallback 模型通过 ModelProviderRegistry 解析对应的 Provider，
     * 构建 ModelRoute 链注入执行器。</p>
     *
     * <p>执行器实现 {@link ChatModel} 接口，对上层（ModelInvoker/ReActAgent）完全透明。</p>
     *
     * @param rawModel     原始 ChatModel
     * @param modelConfig  模型配置
     * @param provider     已解析的 Provider
     * @param chatModelCfg YAML 中的 chat-model 节点（含 fallback 配置）
     * @param aiApiCfg     YAML 中的 ai-api 节点（提供全局 baseUrl/apiKey 回退）
     * @return 包装后的容错 ChatModel
     */
    private ChatModel wrapWithFailover(
            ChatModel rawModel,
            ModelConfig modelConfig,
            ModelProvider provider,
            AiAgentConfigTableVO.Module.ChatModel chatModelCfg,
            AiAgentConfigTableVO.Module.AiApi aiApiCfg) {

        // 解析 fallback 模型链
        List<ModelRoute> fallbackChain = resolveFallbackChain(chatModelCfg, aiApiCfg, modelConfig);

        ResilientChatModelExecutor executor = new ResilientChatModelExecutor(
                rawModel, modelConfig, provider,
                modelProviderRegistry, modelErrorClassifier, fallbackChain);

        if (!fallbackChain.isEmpty()) {
            log.info("ChatModel 已包装为容错执行器: model={}, fallbackChain={}",
                    modelConfig.getModelId(),
                    fallbackChain.stream().map(ModelRoute::getModelId).toList());
        } else {
            log.info("ChatModel 已包装为容错执行器: model={}, 无 fallback 链（仅重试+抖动退避）",
                    modelConfig.getModelId());
        }

        return executor;
    }

    /**
     * 从 YAML 配置解析 fallback 模型链。
     *
     * <p>支持两种配置格式：
     * <ol>
     *   <li>chat-model.fallbackModels（简单模型 ID 列表，复用全局 baseUrl/apiKey）</li>
     *   <li>chat-model.fallback（完整路由列表，每个可指定 provider/baseUrl/apiKey）</li>
     * </ol>
     *
     * <p>每个路由通过 ModelProviderRegistry 验证 Provider 可用性，不可用的路由自动跳过。
     */
    private List<ModelRoute> resolveFallbackChain(
            AiAgentConfigTableVO.Module.ChatModel chatModelCfg,
            AiAgentConfigTableVO.Module.AiApi aiApiCfg,
            ModelConfig globalConfig) {

        List<ModelRoute> chain = new ArrayList<>();

        // 方式 1：简单模型 ID 列表
        List<String> fbModels = chatModelCfg.getFallbackModels();
        if (fbModels != null && !fbModels.isEmpty()) {
            for (String modelId : fbModels) {
                try {
                    ModelProvider fbProvider = modelProviderRegistry.resolve(modelId);
                    chain.add(ModelRoute.builder()
                            .modelId(modelId)
                            .provider(fbProvider.providerName())
                            .baseUrl(aiApiCfg != null ? aiApiCfg.getBaseUrl() : globalConfig.getBaseUrl())
                            .apiKey(aiApiCfg != null ? aiApiCfg.getApiKey() : globalConfig.getApiKey())
                            .completionsPath(aiApiCfg != null
                                    ? aiApiCfg.getCompletionsPath() : fbProvider.defaultCompletionsPath())
                            .build());
                    log.info("Fallback 路由已解析: model={}, provider={}", modelId, fbProvider.providerName());
                } catch (Exception e) {
                    log.warn("Fallback 模型 Provider 不可用，跳过: model={}, reason={}", modelId, e.getMessage());
                }
            }
        }

        // 方式 2：完整路由列表（YAML 中的 fallback 列表，每个含 provider/model/baseUrl/apiKey）
        List<AiAgentConfigTableVO.Module.ChatModel.FallbackRoute> fbRoutes = chatModelCfg.getFallback();
        if (fbRoutes != null && !fbRoutes.isEmpty()) {
            for (var fb : fbRoutes) {
                if (fb.getModel() == null || fb.getModel().isBlank()) continue;
                String providerName = fb.getProvider() != null ? fb.getProvider()
                        : modelProviderRegistry.resolve(fb.getModel()).providerName();
                chain.add(ModelRoute.builder()
                        .modelId(fb.getModel())
                        .provider(providerName)
                        .baseUrl(fb.getBaseUrl() != null ? fb.getBaseUrl()
                                : aiApiCfg != null ? aiApiCfg.getBaseUrl() : globalConfig.getBaseUrl())
                        .apiKey(fb.getApiKey() != null ? fb.getApiKey()
                                : aiApiCfg != null ? aiApiCfg.getApiKey() : globalConfig.getApiKey())
                        .completionsPath(fb.getCompletionsPath())
                        .build());
                log.info("Fallback 路由已解析(完整): model={}, provider={}", fb.getModel(), providerName);
            }
        }

        return chain;
    }

}
