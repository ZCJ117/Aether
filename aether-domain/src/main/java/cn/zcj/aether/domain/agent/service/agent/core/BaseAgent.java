package cn.zcj.aether.domain.agent.service.agent.core;

import cn.zcj.aether.domain.agent.service.agent.hook.AgentHook;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import java.util.*;

/**
 * Agent 抽象基类，提供钩子管理和状态管理的通用实现。
 */
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
        return map;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void loadState(Map<String, Object> stateMap) {
        if (stateMap.containsKey("currentTurn")) {
            state.messagesMutable().clear();
            List<TurnMessage> msgs = (List<TurnMessage>) stateMap.get("messages");
            if (msgs != null) state.messagesMutable().addAll(msgs);
            state.setStatus(AgentState.AgentStatus.IDLE);
        }
    }

    // ============ 能力声明 ============

    @Override
    public List<String> getCapabilities() {
        return List.of(config.getName()); // 默认：只能处理同名 Action
    }
}
