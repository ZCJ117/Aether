package cn.zcj.aether.domain.agent.service.agent.core;

import cn.zcj.aether.domain.agent.service.agent.hook.AgentHook;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Agent 抽象基类，提供钩子管理和状态管理的通用实现。
 */
@Slf4j
public abstract class BaseAgent implements Agent {

    protected final AgentConfig config;
    protected final AgentState state;
    protected final List<AgentHook> hooks = new ArrayList<>();

    protected BaseAgent(AgentConfig config) {
        this.config = Objects.requireNonNull(config);
        this.state = new AgentState();
        this.state.setConfig(config);
    }

    // ============ 身份 ============

    @Override
    public String getId() { return config.getName(); }

    @Override
    public String getName() { return config.getName(); }

    @Override
    public String getDescription() { return config.getDescription(); }

    @Override
    public AgentConfig getConfig() { return config; }

    // ============ 钩子管理 ============

    public void addHook(AgentHook hook) {
        hooks.add(hook);
        hooks.sort(Comparator.comparingInt(AgentHook::priority));
    }

    /** 批量注册 Hook（P1-6 新增） */
    public void addHooks(List<AgentHook> hookList) {
        hooks.addAll(hookList);
        hooks.sort(Comparator.comparingInt(AgentHook::priority));
    }

    public void removeHook(AgentHook hook) { hooks.remove(hook); }

    // ============ 钩子触发（模板方法） ============

    @Override
    public void onBeforeExecute(RuntimeContext ctx) {
        for (AgentHook hook : hooks) hook.onBeforeExecute(this, ctx);
    }

    @Override
    public void onAfterExecute(RuntimeContext ctx, AgentResult result) {
        for (AgentHook hook : hooks) hook.onAfterExecute(this, ctx, result);
    }

    @Override
    public void onError(RuntimeContext ctx, Throwable error) {
        for (AgentHook hook : hooks) hook.onError(this, ctx, error);
    }

    // ============ 状态管理 ============

    @Override
    public AgentState getState() { return state; }

    @Override
    public Map<String, Object> saveState() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("agentId", getId());
        map.put("currentTurn", state.getCurrentTurn());
        map.put("rollingSummary", state.getRollingSummary());
        map.put("status", state.getStatus().name());
        map.put("messages", List.copyOf(state.messagesMutable()));
        // H4: 序列化权限挂起上下文
        map.put("permissionContext", List.copyOf(state.askingMutable()));
        // H5: 序列化槽位子上下文（每个槽位一个 key，对齐 AgentScope L61-79）
        map.put("compactFailureCount", state.getCompactFailureCount());
        map.put("toolContext", Map.of(
                "activeToolGroup", state.getToolContext().getActiveToolGroup()
        ));
        return map;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void loadState(Map<String, Object> stateMap) {
        // H5-步骤1: 全字段必需校验——缺字段响亮报错（对齐 autogen "恢复失败要响亮地失败"）
        requireKeys(stateMap, "currentTurn", "rollingSummary", "status", "messages");

        // 恢复轮次
        state.setCurrentTurn(((Number) stateMap.get("currentTurn")).intValue());

        // 恢复滚动摘要
        state.setRollingSummary((String) stateMap.get("rollingSummary"));

        // 恢复真实运行状态（不再硬置 IDLE——PAUSED 态对 H4 审批挂起至关重要）
        state.setStatus(AgentState.AgentStatus.valueOf((String) stateMap.get("status")));

        // 恢复消息历史（兼容 JSON 反序列化后的 LinkedHashMap）
        state.messagesMutable().clear();
        @SuppressWarnings("unchecked")
        List<Object> rawMessages = (List<Object>) stateMap.get("messages");
        if (rawMessages != null) {
            List<String> restoredRoles = new ArrayList<>();
            for (Object raw : rawMessages) {
                if (raw instanceof TurnMessage tm) {
                    state.messagesMutable().add(tm);
                    restoredRoles.add(tm.role());
                } else if (raw instanceof Map<?,?> m) {
                    // JSON 反序列化后 TurnMessage record 变为 LinkedHashMap，手动重建
                    @SuppressWarnings("unchecked")
                    TurnMessage tm = new TurnMessage(
                        (String) m.get("role"),
                        (String) m.get("content"),
                        (String) m.get("toolCallId"),
                        (String) m.get("toolName"),
                        (List<Map<String, Object>>) m.get("toolCalls")
                    );
                    state.messagesMutable().add(tm);
                    restoredRoles.add(tm.role());
                }
            }
            log.info("loadState 恢复消息: count={}, roles={}", restoredRoles.size(), restoredRoles);
        } else {
            log.info("loadState 未找到 messages 字段");
        }

        // H4: 恢复权限挂起上下文（兼容 JSON 反序列化后的 LinkedHashMap）
        if (stateMap.containsKey("permissionContext")) {
            state.askingMutable().clear();
            @SuppressWarnings("unchecked")
            List<Object> rawAsking = (List<Object>) stateMap.get("permissionContext");
            if (rawAsking != null) {
                for (Object raw : rawAsking) {
                    if (raw instanceof cn.zcj.aether.domain.agent.service.agent.permission.SuspendedToolCall stc) {
                        state.askingMutable().add(stc);
                    } else if (raw instanceof Map<?,?> m) {
                        @SuppressWarnings("unchecked")
                        cn.zcj.aether.domain.agent.service.agent.permission.SuspendedToolCall stc =
                            new cn.zcj.aether.domain.agent.service.agent.permission.SuspendedToolCall(
                                (String) m.get("toolCallId"),
                                (String) m.get("toolName"),
                                (Map<String, Object>) m.get("input"),
                                (String) m.get("reason"),
                                cn.zcj.aether.domain.agent.service.agent.permission.SuspendedToolCall.SuspendedState
                                    .valueOf((String) m.get("state"))
                            );
                        state.askingMutable().add(stc);
                    }
                }
            }
        }

        // H5: 恢复槽位字段（可选——旧版检查点可能不含这些字段，兼容处理）
        if (stateMap.containsKey("compactFailureCount")) {
            state.setCompactFailureCount(
                    ((Number) stateMap.get("compactFailureCount")).intValue());
        }
        if (stateMap.containsKey("toolContext")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> tc = (Map<String, Object>) stateMap.get("toolContext");
            if (tc != null && tc.get("activeToolGroup") instanceof String ag) {
                state.getToolContext().setActiveToolGroup(ag);
            }
        }
    }

    /**
     * H5-步骤1: 校验 stateMap 包含所有必需字段。
     * 缺失任一字段抛出 {@link cn.zcj.aether.types.exception.StateRestoreException}，
     * 对齐 autogen 的"恢复失败要响亮地失败"语义。
     */
    private void requireKeys(Map<String, Object> stateMap, String... keys) {
        for (String key : keys) {
            if (!stateMap.containsKey(key)) {
                throw new cn.zcj.aether.types.exception.StateRestoreException(
                        "State restore failed: missing required field '" + key + "'", key);
            }
        }
    }

    // ============ 能力声明 ============

    @Override
    public List<String> getCapabilities() {
        return List.of(config.getName()); // 默认：只能处理同名 Action
    }
}
