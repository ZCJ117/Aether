package cn.zcj.aether.domain.agent.service.session;

import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointData;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.armory.AgentRegistry;
import cn.zcj.aether.domain.agent.service.memory.core.MemoryLifecycleHooks;
import cn.zcj.aether.types.enums.ResponseCode;
import cn.zcj.aether.types.exception.AppException;
import cn.zcj.aether.types.exception.StateRestoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionService 单测 — 会话创建/列表/删除/恢复/检查点（覆盖率盲区补测 P0 2.1）。
 */
class SessionServiceTest {

    private AgentRegistry agentRegistry;
    private HookRegistry hookRegistry;
    private SessionRepository repository;
    private MemoryLifecycleHooks memoryHooks;
    private CheckpointCollector checkpointCollector;
    private SessionService service;

    @BeforeEach
    void setUp() {
        agentRegistry = mock(AgentRegistry.class);
        hookRegistry = mock(HookRegistry.class);
        repository = mock(SessionRepository.class);
        memoryHooks = mock(MemoryLifecycleHooks.class);
        checkpointCollector = mock(CheckpointCollector.class);
        service = new SessionService();
        ReflectionTestUtils.setField(service, "agentRegistry", agentRegistry);
        ReflectionTestUtils.setField(service, "hookRegistry", hookRegistry);
        ReflectionTestUtils.setField(service, "sessionRepository", repository);
        ReflectionTestUtils.setField(service, "memoryLifecycleHooks", memoryHooks);
        ReflectionTestUtils.setField(service, "checkpointCollector", checkpointCollector);
        when(repository.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(repository.deleteBySessionId(anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void createSessionRejectsUnknownAgent() {
        when(agentRegistry.get("ghost")).thenReturn(null);
        assertThrows(AppException.class, () -> service.createSession("ghost", "u1"));
    }

    @Test
    void createSessionPersistsAndFiresSessionStartHook() {
        when(agentRegistry.get("a1")).thenReturn(AgentGraph.builder().entryPoint("n1").build());

        String sessionId = service.createSession("a1", "u1");

        assertEquals(32, sessionId.length(), "UUID 去连字符后 32 位");
        verify(repository).save(any(SessionEntity.class));
        verify(hookRegistry).invokeAll(eq(HookPoint.ON_SESSION_START), any(HookContext.class));
    }

    @Test
    void listSessionsDelegatesOrReturnsEmptyWithoutRepository() {
        when(repository.listByUserIdAndAgentId("u1", "a1")).thenReturn(List.of());
        assertTrue(service.listSessions("a1", "u1").isEmpty());

        ReflectionTestUtils.setField(service, "sessionRepository", null);
        assertTrue(service.listSessions("a1", "u1").isEmpty());
    }

    @Test
    void deleteSessionFlushesMemoryWithTurnCountThenDeletes() throws Exception {
        SessionEntity entity = SessionEntity.builder()
                .sessionId("s1").stateJson("{\"currentTurn\": 7}").build();
        when(repository.findBySessionId("s1")).thenReturn(Optional.of(entity));

        service.deleteSession("s1");

        verify(memoryHooks).onSessionEnd("s1", 7);
        verify(hookRegistry).invokeAll(eq(HookPoint.ON_SESSION_END), any(HookContext.class));
        verify(repository).deleteBySessionId("s1");
    }

    @Test
    void deleteSessionWithoutRepositoryIsNoOp() {
        ReflectionTestUtils.setField(service, "sessionRepository", null);
        service.deleteSession("s1");
        verify(repository, never()).deleteBySessionId(anyString());
    }

    @Test
    void restoreSessionLoadsStateOrToleratesMissing() throws Exception {
        Agent agent = mock(Agent.class);

        // 无仓储 / 无会话 / 空状态 → 均不调用 loadState
        service.restoreSession(agent, "s1");
        when(repository.findBySessionId("s1")).thenReturn(Optional.empty());
        service.restoreSession(agent, "s1");
        when(repository.findBySessionId("s1")).thenReturn(Optional.of(
                SessionEntity.builder().sessionId("s1").stateJson(null).build()));
        service.restoreSession(agent, "s1");
        verify(agent, never()).loadState(any());

        // 正常状态 JSON → loadState 收到解析后的 Map
        when(repository.findBySessionId("s1")).thenReturn(Optional.of(SessionEntity.builder()
                .sessionId("s1").stateJson("{\"currentTurn\": 3, \"status\": \"IDLE\"}").build()));
        service.restoreSession(agent, "s1");
        verify(agent).loadState(any());
    }

    @Test
    void restoreSessionPropagatesStateRestoreExceptionLoudly() throws Exception {
        Agent agent = mock(Agent.class);
        when(repository.findBySessionId("s1")).thenReturn(Optional.of(SessionEntity.builder()
                .sessionId("s1").stateJson("{\"status\": \"IDLE\"}").build()));
        doThrow(new StateRestoreException("缺少必需字段")).when(agent).loadState(any());

        assertThrows(StateRestoreException.class, () -> service.restoreSession(agent, "s1"));
    }

    @Test
    void restoreSessionToleratesCorruptJson() throws Exception {
        Agent agent = mock(Agent.class);
        when(repository.findBySessionId("s1")).thenReturn(Optional.of(SessionEntity.builder()
                .sessionId("s1").stateJson("{invalid json").build()));

        service.restoreSession(agent, "s1");
        verify(agent, never()).loadState(any());
    }

    @Test
    void resumeFromCheckpointValidatesCollectorAndData() {
        ReflectionTestUtils.setField(service, "checkpointCollector", null);
        assertThrows(AppException.class, () -> service.resumeFromCheckpoint("a1", "s1"));

        ReflectionTestUtils.setField(service, "checkpointCollector", checkpointCollector);
        when(checkpointCollector.loadLatest("s1")).thenReturn(Optional.empty());
        AppException e = assertThrows(AppException.class,
                () -> service.resumeFromCheckpoint("a1", "s1"));
        assertTrue(e.getInfo().contains("无可用检查点"));

        CheckpointData ckpt = mock(CheckpointData.class);
        when(ckpt.getAgentState()).thenReturn(Map.of("currentTurn", 3));
        when(checkpointCollector.loadLatest("s1")).thenReturn(Optional.of(ckpt));
        assertEquals(3, service.resumeFromCheckpoint("a1", "s1").get("currentTurn"));
    }

    @Test
    void listCheckpointsReturnsEmptyWithoutCollector() {
        assertTrue(service.listCheckpoints("s1").isEmpty());
        List<CheckpointData> data = List.of(mock(CheckpointData.class));
        when(checkpointCollector.listCheckpoints("s1")).thenReturn(data);
        assertEquals(data, service.listCheckpoints("s1"));
    }

    @Test
    void getSessionMessagesFiltersUserAndAssistant() {
        ReflectionTestUtils.setField(service, "sessionRepository", null);
        assertTrue(service.getSessionMessages("s1").isEmpty());
        ReflectionTestUtils.setField(service, "sessionRepository", repository);

        String stateJson = "{\"messages\": ["
                + "{\"role\": \"user\", \"content\": \"你好\"},"
                + "{\"role\": \"tool\", \"content\": \"ignored\"},"
                + "{\"role\": \"assistant\", \"content\": \"答复\"}]}";
        when(repository.findBySessionId("s1")).thenReturn(Optional.of(SessionEntity.builder()
                .sessionId("s1").stateJson(stateJson).build()));

        List<Map<String, String>> msgs = service.getSessionMessages("s1");
        assertEquals(2, msgs.size());
        assertEquals("你好", msgs.get(0).get("content"));
        assertEquals("assistant", msgs.get(1).get("role"));

        // 非法 JSON → 空列表兜底
        when(repository.findBySessionId("s2")).thenReturn(Optional.of(SessionEntity.builder()
                .sessionId("s2").stateJson("not-json").build()));
        assertTrue(service.getSessionMessages("s2").isEmpty());
    }

    @Test
    void warnIfPersistenceMissingDoesNotThrow() {
        // 预期启用但无仓储 → 仅 WARN；显式关闭 → 静默；两种情况都不抛异常
        ReflectionTestUtils.setField(service, "sessionRepository", null);
        service.warnIfSessionPersistenceMissing();

        ReflectionTestUtils.setField(service, "sessionStore", "none");
        service.warnIfSessionPersistenceMissing();
    }
}
