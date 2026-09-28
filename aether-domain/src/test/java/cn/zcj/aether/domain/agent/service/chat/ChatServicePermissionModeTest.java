package cn.zcj.aether.domain.agent.service.chat;

import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.middleware.impl.PermissionMiddleware;
import cn.zcj.aether.domain.agent.service.agent.permission.ConfirmResult;
import cn.zcj.aether.domain.agent.service.agent.permission.DangerousToolRule;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionEngine;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionMode;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionModes;
import cn.zcj.aether.domain.agent.service.armory.AgentRegistry;
import cn.zcj.aether.domain.agent.service.executor.GraphExecutor;
import cn.zcj.aether.domain.agent.service.memory.MemoryInjectionService;
import cn.zcj.aether.domain.agent.service.memory.core.MemoryLifecycleHooks;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.session.SessionService;
import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D2 / T1-11、T1-12：权限模式从 HTTP 入参贯通到执行层的端到端验证。
 *
 * <p>链路：{@code ChatService(permissionMode)} → {@code RuntimeContext.metadata} →
 * {@code PermissionModes.resolve} → {@code PermissionEngine} → 写入工具被拒。
 * 缺省时 metadata **不得**出现该键（保持既有 null/空 map 语义）。
 */
class ChatServicePermissionModeTest {

    private AgentRegistry agentRegistry;
    private DefaultAgentFactory agentFactory;
    private SessionService sessionService;
    private MemoryInjectionService memoryInjectionService;
    private Agent agent;
    private ChatService service;

    @BeforeEach
    void setUp() {
        agentRegistry = mock(AgentRegistry.class);
        agentFactory = mock(DefaultAgentFactory.class);
        sessionService = mock(SessionService.class);
        memoryInjectionService = mock(MemoryInjectionService.class);
        agent = mock(Agent.class);

        when(agentFactory.create(any(AgentConfig.class))).thenReturn(agent);
        when(agent.getState()).thenReturn(mock(AgentState.class));
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.just(RuntimeEvent.text("ok")));
        when(memoryInjectionService.injectMemory(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        service = new ChatService();
        ReflectionTestUtils.setField(service, "agentRegistry", agentRegistry);
        ReflectionTestUtils.setField(service, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "memoryInjectionService", memoryInjectionService);
        ReflectionTestUtils.setField(service, "memoryLifecycleHooks", mock(MemoryLifecycleHooks.class));
        ReflectionTestUtils.setField(service, "graphExecutor", mock(GraphExecutor.class));
        ReflectionTestUtils.setField(service, "aiAgentAutoConfigProperties", mock(AiAgentAutoConfigProperties.class));

        AgentNodeDef entry = AgentNodeDef.builder()
                .name("n1").instruction("原始指令").outputKey("n1").build();
        when(agentRegistry.get("a1")).thenReturn(
                AgentGraph.builder().agentDefs(Map.of("n1", entry)).entryPoint("n1").build());
    }

    /** 捕获同步对话路径传给 Agent 的运行时上下文。 */
    private RuntimeContext captureSyncContext(String permissionMode) {
        service.handleMessage("a1", "u1", "s1", "写文件", permissionMode);
        ArgumentCaptor<RuntimeContext> captor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).execute(captor.capture());
        return captor.getValue();
    }

    @Test
    void t1_11PlanModeReachesMetadataAndDeniesWriteTool() {
        RuntimeContext ctx = captureSyncContext("plan");

        assertEquals("plan", ctx.metadata().get(PermissionModes.METADATA_KEY),
                "HTTP 入参的权限模式必须写入 RuntimeContext.metadata");
        assertEquals(PermissionMode.PLAN, PermissionModes.resolve(ctx));

        // ── 同一上下文继续走生产链路：预检通道（PermissionMiddleware）拦截写入工具 ──
        PermissionEngine engine = new PermissionEngine(new DangerousToolRule());
        ToolRegistry registry = new ToolRegistry();
        registry.register(writeTool("write_file"));

        PermissionMiddleware middleware = new PermissionMiddleware();
        ReflectionTestUtils.setField(middleware, "permissionEngine", engine);
        ReflectionTestUtils.setField(middleware, "toolRegistry", registry);

        List<ToolExecutor.ToolCallRequest> allowed = middleware.onActing(
                List.of(new ToolExecutor.ToolCallRequest("call-1", "write_file", Map.of())), agent, ctx);
        assertTrue(allowed.isEmpty(), "plan 模式下写入工具必须被拒（DENY），不得进入执行");

        // ── 直调通道（未预检）：关卡④ 以 ErrorType.PERMISSION 拒绝并回填配对信息 ──
        ToolExecutor executor = new ToolExecutor();
        ReflectionTestUtils.setField(executor, "toolRegistry", registry);
        ReflectionTestUtils.setField(executor, "permissionEngine", engine);

        List<ToolResult> results = executor.executeBatch(
                List.of(new ToolExecutor.ToolCallRequest("call-1", "write_file", Map.of())),
                new ToolContext("u1", "s1", "", PermissionModes.resolve(ctx), false));

        assertEquals(ToolResult.ErrorType.PERMISSION, results.get(0).getErrorType(),
                "plan 模式下未经预检的写入工具调用必须返回 PERMISSION 错误");
        assertEquals("call-1", results.get(0).getToolCallId());
    }

    @Test
    void t1_12AbsentPermissionModeLeavesMetadataUntouched() {
        RuntimeContext syncCtx = captureSyncContext(null);
        assertNull(syncCtx.metadata().get(PermissionModes.METADATA_KEY),
                "缺省权限模式不得写入 metadata 键");
        assertTrue(syncCtx.metadata().isEmpty(), "同步路径缺省时保持既有 null → 空 map 语义");

        // 流式路径：缺省时保持既有空 HashMap 语义（无该键）
        service.handleMessageStream("a1", "u1", "s1", "hi", null).toList().blockingGet();
        ArgumentCaptor<RuntimeContext> streamCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent, org.mockito.Mockito.atLeastOnce()).execute(streamCaptor.capture());
        RuntimeContext streamCtx = streamCaptor.getValue();
        assertNull(streamCtx.metadata().get(PermissionModes.METADATA_KEY));
        assertTrue(streamCtx.metadata().isEmpty(), "流式路径缺省时保持既有空 map 语义");
    }

    @Test
    void confirmPathCarriesPermissionModeForResume() {
        service.handleConfirm("a1", "u1", "s1", List.of(ConfirmResult.approve("call-1")), "accept_edits")
                .toList().blockingGet();

        ArgumentCaptor<RuntimeContext> captor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).execute(captor.capture());
        RuntimeContext ctx = captor.getValue();

        assertEquals("accept_edits", ctx.metadata().get(PermissionModes.METADATA_KEY),
                "恢复执行时权限模式必须与挂起时一致");
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionModes.resolve(ctx));
        assertTrue(ctx.metadata().containsKey("confirmResults"), "既有确认回执语义不受影响");
    }

    /** 非只读的写入工具桩（plan 模式下应被拒绝）。 */
    private static Tool writeTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public boolean isReadOnly() { return false; }

            @Override
            public ToolResult call(Map<String, Object> input, ToolContext ctx) {
                return ToolResult.success(ctx.toolCallId(), name, "written");
            }
        };
    }
}
