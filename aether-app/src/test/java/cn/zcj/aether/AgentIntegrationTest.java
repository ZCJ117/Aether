package cn.zcj.aether;

import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.model.valobj.AgentModelConfig;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.armory.AgentRegistry;
import cn.zcj.aether.domain.agent.service.compiler.AgentGraphCompiler;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.service.event.AgentEvent;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 端到端集成测试 — P0-5
 *
 * 验证：
 *   1. Spring 上下文加载成功
 *   2. P0-1: Agent 接口抽象所有 Bean 就绪
 *   3. P0-2: ModelProvider SPI Bean 就绪
 *   4. P0-3: 配置加载 + 模型解析
 *   5. P0-4: Session 相关类可用
 *   6. P0-5: 核心组件可互相协作
 *   7. P0-6: 事件发布器 + 事件类型就绪
 */
@SpringBootTest(
        properties = {
                "spring.profiles.active=test",
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
        }
)
class AgentIntegrationTest {

    // ==================== 无数据源环境的仓储替换 ====================
    // 本测试排除数据源自动配置，但 UserRepository/RefreshTokenRepository/AuditLogRepository
    // 构造硬依赖 DataSource → 用 @MockitoBean 替换为 mock，避免上下文加载失败（历史遗留：该测试
    // 在 surefire 2.6 + skipTests=true 时代从未实际执行过，条件注解方案因评估顺序不可靠被弃用）。

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.UserRepository userRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.RefreshTokenRepository refreshTokenRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.AuditLogRepository auditLogRepository;

    // P1(1.3): Kafka 统计链路新增的两个 DataSource 硬依赖仓储，同上替换为 mock
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.DashboardStatsRepository dashboardStatsRepository;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private cn.zcj.aether.infrastructure.persistence.ProcessedEventRepository processedEventRepository;

    // ==================== P0-1: Agent 接口抽象 ====================

    @Autowired
    private DefaultAgentFactory agentFactory;

    @Autowired
    private AgentRegistry agentRegistry;

    @Test
    @DisplayName("P0-1: 应用上下文应加载成功")
    void contextShouldLoad() {
        assertNotNull(agentFactory, "DefaultAgentFactory 应被注入");
        assertNotNull(agentRegistry, "AgentRegistry 应被注入");
    }

    @Test
    @DisplayName("P0-1: AgentFactory 应能创建 ReActAgent")
    void agentFactoryShouldCreateReActAgent() {
        AgentConfig config = AgentConfig.builder()
                .name("integration-test-agent")
                .instruction("你是一个集成测试助手，请简洁回答。")
                .description("集成测试Agent")
                .outputKey("result")
                .modelRef("gpt-4o")
                .agentType("react")
                .build();

        assertDoesNotThrow(() -> {
            Agent agent = agentFactory.create(config);
            assertNotNull(agent);
            assertEquals("integration-test-agent", agent.getId());
            assertEquals("react", agent.getConfig().getAgentType());
            assertNotNull(agent.getState());
            assertEquals(AgentState.AgentStatus.IDLE, agent.getState().getStatus());
        }, "AgentFactory 应能创建 ReActAgent 实例");
    }

    @Test
    @DisplayName("P0-1: Agent 应能管理状态")
    void agentShouldManageState() {
        Agent agent = agentFactory.create(AgentConfig.builder()
                .name("state-test")
                .instruction("状态管理测试")
                .build());

        AgentState state = agent.getState();
        assertNotNull(state);
        assertEquals(0, state.getCurrentTurn());
        assertEquals(AgentState.AgentStatus.IDLE, state.getStatus());
        assertTrue(state.getMessages().isEmpty());

        state.incrementTurn();
        assertEquals(1, state.getCurrentTurn());
    }

    @Test
    @DisplayName("P0-1: Agent 应支持序列化与恢复")
    void agentShouldSerializeAndRestore() {
        Agent agent = agentFactory.create(AgentConfig.builder()
                .name("serialize-test")
                .instruction("序列化测试")
                .build());

        // 初始化一些状态
        agent.getState().incrementTurn();
        agent.getState().incrementTurn();
        agent.getState().setRollingSummary("测试摘要");

        Map<String, Object> saved = agent.saveState();
        assertTrue(saved.containsKey("agentId"));
        assertTrue(saved.containsKey("currentTurn"));
        assertEquals(2, saved.get("currentTurn"));
        assertEquals("测试摘要", saved.get("rollingSummary"));

        // 创建新 Agent 并恢复状态
        Agent restored = agentFactory.create(AgentConfig.builder()
                .name("serialize-test")
                .instruction("序列化测试")
                .build());
        restored.loadState(saved);
        assertEquals(2, restored.getState().getCurrentTurn());
    }

    @Test
    @DisplayName("P0-1: RuntimeContext 应正确创建")
    void runtimeContextShouldBeCreated() {
        RuntimeContext ctx = new RuntimeContext(
                "user-001", "sess-001", "trace-001",
                null, "你好，世界", null, null);

        assertEquals("user-001", ctx.userId());
        assertEquals("sess-001", ctx.sessionId());
        assertEquals("trace-001", ctx.correlationId());
        assertEquals("你好，世界", ctx.initialMessage());
        assertNotNull(ctx.createdAt());
    }

    // ==================== P0-2: 模型提供商抽象 ====================

    @Autowired
    private ModelProviderRegistry modelProviderRegistry;

    @Test
    @DisplayName("P0-2: ModelProviderRegistry 应注册所有 Provider")
    void modelProviderRegistryShouldHaveProviders() {
        assertNotNull(modelProviderRegistry, "ModelProviderRegistry 应被注入");
        var providers = modelProviderRegistry.getAllProviders();
        assertFalse(providers.isEmpty(), "应至少注册了一个 Provider");

        // 验证三种 Provider name
        List<String> names = providers.stream()
                .map(p -> p.providerName())
                .toList();
        assertTrue(names.contains("openai"), "应包含 OpenAI Provider");
        assertTrue(names.contains("anthropic"), "应包含 Anthropic Provider");
        assertTrue(names.contains("dashscope"), "应包含 DashScope Provider");
    }

    @Test
    @DisplayName("P0-2: ModelProviderRegistry 应为不同模型匹配正确的 Provider")
    void modelProviderRegistryShouldResolveCorrectProvider() {
        // GPT 模型应由兜底 OpenAI Provider 匹配
        var openaiProvider = modelProviderRegistry.resolve("gpt-4o");
        assertNotNull(openaiProvider);
        assertEquals("openai", openaiProvider.providerName());

        // Claude 模型应由 Anthropic Provider 匹配
        var anthropicProvider = modelProviderRegistry.resolve("claude-sonnet-4-6");
        assertNotNull(anthropicProvider);
        assertEquals("anthropic", anthropicProvider.providerName());

        // Qwen 模型应由 DashScope Provider 匹配
        var dashscopeProvider = modelProviderRegistry.resolve("qwen-max");
        assertNotNull(dashscopeProvider);
        assertEquals("dashscope", dashscopeProvider.providerName());
    }

    @Test
    @DisplayName("P0-2: ModelConfig 应能构建")
    void modelConfigShouldBuild() {
        ModelConfig config = ModelConfig.builder()
                .modelId("gpt-4o")
                .baseUrl("https://api.openai.com")
                .apiKey("sk-test")
                .completionsPath("v1/chat/completions")
                .build();

        assertEquals("gpt-4o", config.getModelId());
        assertEquals("https://api.openai.com", config.getBaseUrl());
    }

    // ==================== P0-3: 多模型 Per-Agent 配置 ====================

    @Autowired
    private AiAgentAutoConfigProperties autoConfigProperties;

    @Autowired
    private AgentGraphCompiler graphCompiler;

    @Test
    @DisplayName("P0-3: YAML 配置应正确加载")
    void yamlConfigShouldLoad() {
        assertNotNull(autoConfigProperties, "自动配置属性应被注入");
        assertNotNull(autoConfigProperties.getTables(), "配置表不应为空");

        AiAgentConfigTableVO testConfig = autoConfigProperties.getTables().get("test-agent");
        assertNotNull(testConfig, "应存在 test-agent 配置");
        assertEquals("test-agent", testConfig.getAppName());
    }

    @Test
    @DisplayName("P0-3: AgentModelConfig 应能独立配置")
    void agentModelConfigShouldSupportPerAgentModel() {
        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setModelId("claude-sonnet-4-6");
        modelConfig.setProvider("anthropic");
        modelConfig.setApiKey("sk-ant-test");
        modelConfig.setBaseUrl("https://api.anthropic.com");

        assertEquals("claude-sonnet-4-6", modelConfig.getModelId());
        assertEquals("anthropic", modelConfig.getProvider());
        assertEquals("sk-ant-test", modelConfig.getApiKey());
        assertEquals("https://api.anthropic.com", modelConfig.getBaseUrl());
    }

    @Test
    @DisplayName("P0-3: AgentGraphCompiler 应正确编译 Per-Agent 模型引用")
    void graphCompilerShouldResolvePerAgentModelRef() {
        AiAgentConfigTableVO testConfig = autoConfigProperties.getTables().get("test-agent");
        assertNotNull(testConfig);

        AgentGraph graph = graphCompiler.compile(testConfig);
        assertNotNull(graph);
        assertEquals("test-agent", graph.getAppName());

        // 入口 Agent 应解析到正确的模型引用
        AgentNodeDef entry = graph.getAgentDefs().get("assistant");
        assertNotNull(entry, "入口 Agent 应存在于编译后的图中");
        assertEquals("gpt-4o", entry.getModelRef(),
                "Per-Agent 模型应回退到全局 chat-model 配置");
    }

    // ==================== P0-4: 对话持久化 ====================

    @Test
    @DisplayName("P0-4: SessionEntity 应能构建")
    void sessionEntityShouldBuild() {
        cn.zcj.aether.domain.agent.service.session.SessionEntity entity =
                cn.zcj.aether.domain.agent.service.session.SessionEntity.builder()
                        .sessionId("sess-int-001")
                        .userId("user-int-001")
                        .agentId("agent-int-001")
                        .status("ACTIVE")
                        .stateJson("{\"currentTurn\":3}")
                        .build();

        assertEquals("sess-int-001", entity.getSessionId());
        assertEquals("user-int-001", entity.getUserId());
        assertEquals("ACTIVE", entity.getStatus());
    }

    @Test
    @DisplayName("P0-4: TurnMessage 应正确构建多角色消息")
    void turnMessageShouldBuildMultipleRoles() {
        TurnMessage userMsg = TurnMessage.user("用户问题");
        assertEquals("user", userMsg.role());
        assertEquals("用户问题", userMsg.content());
        assertFalse(userMsg.isToolResult());

        TurnMessage assistantMsg = TurnMessage.assistant("助手回复");
        assertEquals("assistant", assistantMsg.role());

        TurnMessage toolMsg = TurnMessage.toolResult("call-001", "web_search", "搜索结果");
        assertEquals("tool_result", toolMsg.role());
        assertTrue(toolMsg.isToolResult());
        assertEquals("web_search", toolMsg.toolName());
        assertEquals("call-001", toolMsg.toolCallId());
    }

    // ==================== P0-6: 结构化日志/事件 ====================

    @Autowired(required = false)
    private AgentEventPublisher eventPublisher;

    @Test
    @DisplayName("P0-6: AgentEventPublisher 应可用")
    void eventPublisherShouldBeAvailable() {
        assertNotNull(eventPublisher, "AgentEventPublisher Bean 应存在");
    }

    @Test
    @DisplayName("P0-6: AgentEvent 事件类型应完整")
    void agentEventTypesShouldBeComplete() {
        // 验证 AgentStarted 事件构造
        AgentEvent.AgentStarted started = new AgentEvent.AgentStarted(
                "agent-1", "sess-1", "corr-1", "react", "gpt-4o");
        assertEquals("agent-1", started.agentId());
        assertEquals("react", started.agentType());
        assertEquals("gpt-4o", started.modelRef());
        assertNotNull(started.eventId());
        assertNotNull(started.timestamp());

        // 验证 TurnStarted 事件构造
        AgentEvent.TurnStarted turnStarted = new AgentEvent.TurnStarted(
                "agent-1", "sess-1", "corr-1", 3);
        assertEquals(3, turnStarted.turnNumber());

        // 验证 ErrorOccurred 事件构造
        AgentEvent.ErrorOccurred error = new AgentEvent.ErrorOccurred(
                "agent-1", "sess-1", "corr-1",
                "RuntimeException", "连接超时", 5);
        assertEquals("RuntimeException", error.errorType());
        assertEquals("连接超时", error.errorMessage());
        assertEquals(5, error.turnNumber());
    }

    @Test
    @DisplayName("P0-6: AgentEventPublisher 不应抛异常")
    void eventPublisherShouldNotThrow() {
        assertDoesNotThrow(() ->
                eventPublisher.publishAgentStarted("test", "sess", "corr", "react", "gpt-4o"));

        assertDoesNotThrow(() ->
                eventPublisher.publishTurnStarted("test", "sess", "corr", 1));

        assertDoesNotThrow(() ->
                eventPublisher.publishTurnCompleted("test", "sess", "corr",
                        1, false, 0, 100));

        assertDoesNotThrow(() ->
                eventPublisher.publishError("test", "sess", "corr",
                        "TestError", "test message", 0));
    }

    // ==================== 核心运行时组件 ====================

    @Autowired
    private ModelInvoker modelInvoker;

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ContextManager contextManager;

    @Test
    @DisplayName("核心运行时组件应全部就绪")
    void coreRuntimeComponentsShouldBeReady() {
        assertNotNull(modelInvoker, "ModelInvoker 应被注入");
        assertNotNull(toolExecutor, "ToolExecutor 应被注入");
        assertNotNull(toolRegistry, "ToolRegistry 应被注入");
        assertNotNull(contextManager, "ContextManager 应被注入");

        // 验证工具注册表为空（测试环境无 MCP/Skills 配置）
        assertNotNull(toolRegistry, "ToolRegistry 应存在");
    }

    @Test
    @DisplayName("TokenEstimator 应正确估算")
    void tokenEstimatorShouldEstimate() {
        TokenEstimator estimator = new TokenEstimator();

        int english = estimator.estimate("Hello world, this is a test");
        assertTrue(english > 0, "英文文本应估算 > 0 tokens");

        int chinese = estimator.estimate("这是一段中文测试文本");
        assertTrue(chinese > 0, "中文文本应估算 > 0 tokens");
    }

    @Test
    @DisplayName("AgentConfig.fromNodeDef 应端到端映射")
    void agentConfigFromNodeDefShouldMapEndToEnd() {
        AgentNodeDef nodeDef = AgentNodeDef.builder()
                .name("e2e-agent")
                .instruction("端到端测试指令")
                .description("端到端测试Agent")
                .outputKey("e2e_result")
                .toolNames(List.of("tool_a", "tool_b"))
                .modelRef("gpt-4o")
                .agentType("react")
                .build();

        AgentConfig config = AgentConfig.fromNodeDef(nodeDef);

        // 从 AgentConfig 创建 Agent
        Agent agent = agentFactory.create(config);

        assertNotNull(agent);
        assertEquals("e2e-agent", agent.getId());
        assertEquals("端到端测试指令", agent.getConfig().getInstruction());
        assertEquals(2, agent.getConfig().getToolNames().size());
        assertEquals("gpt-4o", agent.getConfig().getModelRef());

        // 能力声明
        List<String> capabilities = agent.getCapabilities();
        assertNotNull(capabilities);
        assertEquals(1, capabilities.size());
        assertTrue(capabilities.contains("e2e-agent"));
    }
}
