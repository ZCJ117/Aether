package cn.zcj.aether.domain.agent.service.chat;

import cn.zcj.aether.domain.agent.model.entity.ChatCommandEntity;
import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.permission.ConfirmResult;
import cn.zcj.aether.domain.agent.service.armory.AgentRegistry;
import cn.zcj.aether.domain.agent.service.executor.GraphExecutor;
import cn.zcj.aether.domain.agent.service.memory.MemoryInjectionService;
import cn.zcj.aether.domain.agent.service.memory.core.MemoryLifecycleHooks;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.session.SessionService;
import cn.zcj.aether.types.exception.AppException;
import cn.zcj.aether.types.exception.StateRestoreException;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatService 对话编排单测 — 同步/流式/确认三入口的路由与记忆持久化（覆盖率盲区补测 P0 2.1）。
 * 与既有 {@link ChatServiceTest}（图构建静态断言）互补。
 */
class ChatServiceOrchestrationTest {

    private AgentRegistry agentRegistry;
    private GraphExecutor graphExecutor;
    private AiAgentAutoConfigProperties autoConfig;
    private DefaultAgentFactory agentFactory;
    private SessionService sessionService;
    private MemoryInjectionService memoryInjectionService;
    private MemoryLifecycleHooks memoryHooks;
    private Agent agent;
    private AgentState agentState;
    private ChatService service;

    @BeforeEach
    void setUp() {
        agentRegistry = mock(AgentRegistry.class);
        graphExecutor = mock(GraphExecutor.class);
        autoConfig = mock(AiAgentAutoConfigProperties.class);
        agentFactory = mock(DefaultAgentFactory.class);
        sessionService = mock(SessionService.class);
        memoryInjectionService = mock(MemoryInjectionService.class);
        memoryHooks = mock(MemoryLifecycleHooks.class);
        agent = mock(Agent.class);
        agentState = mock(AgentState.class);
        when(agentFactory.create(any(AgentConfig.class))).thenReturn(agent);
        when(agent.getState()).thenReturn(agentState);
        when(memoryInjectionService.injectMemory(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        service = new ChatService();
        ReflectionTestUtils.setField(service, "agentRegistry", agentRegistry);
        ReflectionTestUtils.setField(service, "graphExecutor", graphExecutor);
        ReflectionTestUtils.setField(service, "aiAgentAutoConfigProperties", autoConfig);
        ReflectionTestUtils.setField(service, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "memoryInjectionService", memoryInjectionService);
        ReflectionTestUtils.setField(service, "memoryLifecycleHooks", memoryHooks);
    }

    private AgentGraph singleAgentGraph() {
        AgentNodeDef entry = AgentNodeDef.builder()
                .name("n1").instruction("原始指令").outputKey("n1").build();
        return AgentGraph.builder().agentDefs(Map.of("n1", entry)).entryPoint("n1").build();
    }

    @Test
    void queryAiAgentConfigListSkipsEntriesWithoutAgent() {
        // getAgent() 未设置（null）的表项被过滤；tables 为 null 时返回空列表
        Map<String, AiAgentConfigTableVO> tables = new java.util.HashMap<>();
        tables.put("b", new AiAgentConfigTableVO());
        when(autoConfig.getTables()).thenReturn(tables);

        List<AiAgentConfigTableVO> list = service.queryAiAgentConfigList();
        assertEquals(0, list.size(), "getAgent() 为 null 的表项被过滤");

        when(autoConfig.getTables()).thenReturn(null);
        assertTrue(service.queryAiAgentConfigList().isEmpty());
    }

    @Test
    void sessionLifecycleDelegatesToSessionService() {
        when(sessionService.createSession("a1", "u1")).thenReturn("s1");
        assertEquals("s1", service.createSession("a1", "u1"));
        service.deleteSession("s1");
        verify(sessionService).deleteSession("s1");
    }

    @Test
    void handleMessageRejectsUnknownAgent() {
        when(agentRegistry.get("ghost")).thenReturn(null);
        assertThrows(AppException.class,
                () -> service.handleMessage("ghost", "u1", "s1", "hi"));
    }

    @Test
    void handleMessageRoutesMultiAgentGraphToGraphExecutor() {
        AgentGraph graph = AgentGraph.builder().agentDefs(Map.of()).entryPoint("n1")
                .edges(List.of(AgentEdge.builder().workflowName("wf").build())).build();
        when(agentRegistry.get("a1")).thenReturn(graph);
        when(graphExecutor.execute(graph, "u1", "s1", "hi")).thenReturn(
                Flowable.just(RuntimeEvent.text("段1"), RuntimeEvent.text("段2"), RuntimeEvent.done()));

        List<String> outputs = service.handleMessage("a1", "u1", "s1", "hi");

        assertEquals(List.of("段1", "段2"), outputs);
        verify(agentFactory, never()).create(any());
    }

    @Test
    void handleMessageSingleAgentCollectsTextAndPersistsMemory() {
        when(agentRegistry.get("a1")).thenReturn(singleAgentGraph());
        when(agent.execute(any(RuntimeContext.class))).thenReturn(
                Flowable.just(RuntimeEvent.text("回答A"), RuntimeEvent.text("回答B")));

        List<String> outputs = service.handleMessage("a1", "u1", "s1", "问题");

        assertEquals(List.of("回答A", "回答B"), outputs);
        verify(memoryHooks).syncTurn("问题", "回答A回答B", "s1", null);
        verify(sessionService).restoreSession(agent, "s1");
    }

    @Test
    void handleMessageRejectsPausedAgentAwaitingConfirm() {
        when(agentRegistry.get("a1")).thenReturn(singleAgentGraph());
        when(agentState.getStatus()).thenReturn(AgentState.AgentStatus.PAUSED);
        when(agentState.hasPendingAsking()).thenReturn(true);

        AppException e = assertThrows(AppException.class,
                () -> service.handleMessage("a1", "u1", "s1", "hi"));
        assertTrue(e.getInfo().contains("Agent 已暂停"));
    }

    @Test
    void handleMessageWithoutEntryNodeThrows() {
        AgentGraph graph = AgentGraph.builder().agentDefs(Map.of()).entryPoint("ghost").build();
        when(agentRegistry.get("a1")).thenReturn(graph);

        AppException e = assertThrows(AppException.class,
                () -> service.handleMessage("a1", "u1", "s1", "hi"));
        assertTrue(e.getInfo().contains("入口Agent未配置"));
    }

    @Test
    void handleMessageStreamPropagatesEventsAndPersistsMemoryOnComplete() {
        when(agentRegistry.get("a1")).thenReturn(singleAgentGraph());
        when(agent.execute(any(RuntimeContext.class))).thenReturn(
                Flowable.just(RuntimeEvent.text("流式"), RuntimeEvent.done()));

        List<RuntimeEvent> events = service.handleMessageStream("a1", "u1", "s1", "问题")
                .toList().blockingGet();

        assertEquals(2, events.size());
        verify(memoryHooks).syncTurn("问题", "流式", "s1", null);
    }

    @Test
    void handleMessageStreamSkipsMemoryPersistWhenHooksAbsent() {
        ReflectionTestUtils.setField(service, "memoryLifecycleHooks", null);
        when(agentRegistry.get("a1")).thenReturn(singleAgentGraph());
        when(agent.execute(any(RuntimeContext.class))).thenReturn(
                Flowable.just(RuntimeEvent.text("x")));

        service.handleMessageStream("a1", "u1", "s1", "问题").toList().blockingGet();
        verify(memoryHooks, never()).syncTurn(any(), any(), any(), any());
    }

    @Test
    void handleMessageStreamUnknownAgentErrors() {
        when(agentRegistry.get("ghost")).thenReturn(null);
        assertThrows(AppException.class,
                () -> service.handleMessageStream("ghost", "u1", "s1", "hi").blockingLast());
    }

    @Test
    void handleMessageStreamFailsLoudlyOnStateRestoreError() {
        when(agentRegistry.get("a1")).thenReturn(singleAgentGraph());
        org.mockito.Mockito.doThrow(new StateRestoreException("缺少字段"))
                .when(sessionService).restoreSession(agent, "s1");

        AppException e = assertThrows(AppException.class,
                () -> service.handleMessageStream("a1", "u1", "s1", "hi").blockingLast());
        assertTrue(e.getInfo().contains("会话状态恢复失败"));
    }

    @Test
    void handleConfirmExecutesWithConfirmMetadata() {
        when(agentRegistry.get("a1")).thenReturn(singleAgentGraph());
        when(agent.execute(any(RuntimeContext.class))).thenReturn(
                Flowable.just(RuntimeEvent.done()));

        List<RuntimeEvent> events = service.handleConfirm("a1", "u1", "s1",
                        List.of(ConfirmResult.approve("call-1")))
                .toList().blockingGet();

        assertEquals(1, events.size());
        verify(agentFactory).create(any(AgentConfig.class));
    }

    @Test
    void handleConfirmRejectsUnknownAgent() {
        when(agentRegistry.get("ghost")).thenReturn(null);
        assertThrows(AppException.class,
                () -> service.handleConfirm("ghost", "u1", "s1", List.of()).blockingLast());
    }

    @Test
    void chatCommandEntityOverloadExtractsFirstText() {
        when(agentRegistry.get("a1")).thenReturn(singleAgentGraph());
        when(agent.execute(any(RuntimeContext.class))).thenReturn(Flowable.empty());

        ChatCommandEntity cmd = new ChatCommandEntity();
        cmd.setAgentId("a1");
        cmd.setUserId("u1");
        cmd.setSessionId("s1");
        cn.zcj.aether.domain.agent.model.entity.ChatCommandEntity.Content.Text text =
                new cn.zcj.aether.domain.agent.model.entity.ChatCommandEntity.Content.Text("来自实体的消息");
        cmd.setTexts(List.of(text));

        service.handleMessage(cmd);

        verify(sessionService).restoreSession(eq(agent), eq("s1"));
    }
}
