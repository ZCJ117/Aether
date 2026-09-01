package cn.zcj.aether.domain.agent.service.agent.core;

import cn.zcj.aether.domain.agent.service.agent.permission.SuspendedToolCall;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import cn.zcj.aether.types.exception.StateRestoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O4 状态序列化版本化 — round-trip 单元测试。
 *
 * 验证：
 * 1. saveState → JSON → loadState 全槽位无损恢复（round-trip）；
 * 2. 存量无版本号 JSON（v1）按迁移路径正常加载；
 * 3. 必需字段缺失 → StateRestoreException（响亮失败）；
 * 4. 不支持的 schemaVersion → StateRestoreException。
 */
class BaseAgentStateRoundTripTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 最小具体 Agent（仅承载 BaseAgent 状态逻辑）。 */
    static class StubAgent extends BaseAgent {
        StubAgent(AgentConfig config) { super(config); }

        @Override
        public Flowable<RuntimeEvent> execute(RuntimeContext context) {
            return Flowable.empty();
        }
    }

    private static AgentConfig config() {
        return AgentConfig.builder()
                .name("round-trip-agent")
                .instruction("test instruction")
                .modelRef("test-model")
                .build();
    }

    @Test
    void savedStateCarriesSchemaVersion() {
        StubAgent agent = new StubAgent(config());
        Map<String, Object> state = agent.saveState();

        assertEquals(BaseAgent.STATE_SCHEMA_VERSION, state.get("schemaVersion"),
                "saveState 必须携带 schemaVersion（O4 核心要求）");
    }

    @Test
    void roundTripViaJsonPreservesAllSlots() throws Exception {
        StubAgent source = new StubAgent(config());
        source.getState().incrementTurn();
        source.getState().incrementTurn();
        source.getState().incrementTurn();
        source.getState().setRollingSummary("滚动摘要内容");
        source.getState().setStatus(AgentState.AgentStatus.PAUSED);
        source.getState().setCompactFailureCount(2);
        source.getState().getToolContext().setActiveToolGroup("file-tools");
        source.getState().messagesMutable().add(TurnMessage.user("用户问题"));
        source.getState().messagesMutable().add(TurnMessage.assistantWithToolCalls(
                "调用工具", List.of(Map.of("id", "tc-1", "name", "read_file", "input", Map.of("path", "a.txt")))));
        source.getState().messagesMutable().add(TurnMessage.toolResult("tc-1", "read_file", "文件内容"));
        source.getState().askingMutable().add(new SuspendedToolCall(
                "tc-1", "shell", Map.of("command", "rm x"), "危险操作", SuspendedToolCall.SuspendedState.ASKING));

        // 序列化 → 反序列化（模拟持久化层 JSON 往返）
        String json = objectMapper.writeValueAsString(source.saveState());
        @SuppressWarnings("unchecked")
        Map<String, Object> restored = objectMapper.readValue(json, Map.class);

        StubAgent target = new StubAgent(config());
        target.loadState(restored);

        assertEquals(3, target.getState().getCurrentTurn());
        assertEquals("滚动摘要内容", target.getState().getRollingSummary());
        assertEquals(AgentState.AgentStatus.PAUSED, target.getState().getStatus());
        assertEquals(2, target.getState().getCompactFailureCount());
        assertEquals("file-tools", target.getState().getToolContext().getActiveToolGroup());

        List<TurnMessage> messages = target.getState().messagesMutable();
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("用户问题", messages.get(0).content());
        assertTrue(messages.get(1).hasToolCalls());
        assertEquals("read_file", messages.get(1).toolCalls().get(0).get("name"));
        assertEquals("tool_result", messages.get(2).role());
        assertEquals("tc-1", messages.get(2).toolCallId());

        List<SuspendedToolCall> asking = target.getState().getAsking();
        assertEquals(1, asking.size());
        assertEquals("tc-1", asking.get(0).toolCallId());
        assertEquals(SuspendedToolCall.SuspendedState.ASKING, asking.get(0).state());
    }

    @Test
    void legacyStateWithoutVersionLoadsAsV1() {
        // 存量 JSON：无 schemaVersion 字段（v1 兼容语义）
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("agentId", "round-trip-agent");
        legacy.put("currentTurn", 5);
        legacy.put("rollingSummary", "legacy summary");
        legacy.put("status", "IDLE");
        legacy.put("messages", List.of(Map.of("role", "user", "content", "旧消息")));

        StubAgent agent = new StubAgent(config());
        assertDoesNotThrow(() -> agent.loadState(legacy));

        assertEquals(5, agent.getState().getCurrentTurn());
        assertEquals("legacy summary", agent.getState().getRollingSummary());
        assertEquals(AgentState.AgentStatus.IDLE, agent.getState().getStatus());
        assertEquals(1, agent.getState().messagesMutable().size());
    }

    @Test
    void missingRequiredFieldFailsLoudly() {
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("schemaVersion", 2);
        broken.put("currentTurn", 1);
        // 缺 messages / status / rollingSummary
        StubAgent agent = new StubAgent(config());
        StateRestoreException ex = assertThrows(StateRestoreException.class,
                () -> agent.loadState(broken));
        assertTrue(ex.getMessage().contains("required field"));
    }

    @Test
    void unsupportedSchemaVersionFailsLoudly() {
        Map<String, Object> future = new LinkedHashMap<>();
        future.put("schemaVersion", BaseAgent.STATE_SCHEMA_VERSION + 99);
        future.put("currentTurn", 1);
        future.put("rollingSummary", "");
        future.put("status", "IDLE");
        future.put("messages", List.of());

        StubAgent agent = new StubAgent(config());
        StateRestoreException ex = assertThrows(StateRestoreException.class,
                () -> agent.loadState(future));
        assertTrue(ex.getMessage().contains("unsupported schemaVersion"));
    }
}
