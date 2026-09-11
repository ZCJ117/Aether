package cn.zcj.aether.domain.agent.service.agent.core;

import cn.zcj.aether.domain.agent.service.agent.hook.AgentHook;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Agent 抽象基类，提供钩子管理和状态管理的通用实现。
 * <p><b>【架构亮点 · 状态机与持久化】</b><br>
 * 面试举证点：Agent 状态以"带版本号的快照"语义持久化——STATE_SCHEMA_VERSION=2（L86）置顶
 * 于 saveState；loadState 对不支持的版本（version > 当前）响亮抛出 StateRestoreException（L113-117），
 * 旧版经 migrate 迁移（L118-120），并用 requireKeys 强校验必需字段缺失即失败（L123），
 * 对齐 autogen "恢复失败要响亮地失败"，杜绝带病恢复。</p>
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

    /**
     * O5: 外部中断入口——置位 {@link InterruptControl}，主循环在下一轮检查点退出。
     *
     * <p>中断信号存于 AgentState 的 transient 字段，永不序列化落盘。
     */
    public void interrupt() {
        state.interruptControl().interrupt();
    }

    // 【持久化】状态 schema 版本：v1=无版本号存量 JSON，v2=引入 schemaVersion 的带版本格式，迁移路径据此选择
    /** O4: 当前状态 schema 版本。v1 = 无版本号的存量 JSON；v2 = 引入 schemaVersion 后的带版本格式。 */
    public static final int STATE_SCHEMA_VERSION = 2;

    @Override
    public Map<String, Object> saveState() {
        Map<String, Object> map = new LinkedHashMap<>();
        // O4: 版本号置顶——loadState 据此选择迁移路径，字段演进不再依赖"旧版兼容"逐字段 if
        map.put("schemaVersion", STATE_SCHEMA_VERSION);
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
        // O4: 按版本迁移——旧 JSON（无 schemaVersion）视为 v1；不支持的版本响亮失败
        int version = resolveSchemaVersion(stateMap);
        if (version > STATE_SCHEMA_VERSION) {
            // 【持久化】不支持的版本响亮失败：拒绝恢复高于当前 schema 的状态，避免静默错乱
            throw new cn.zcj.aether.types.exception.StateRestoreException(
                    "State restore failed: unsupported schemaVersion " + version
                            + " (supported up to " + STATE_SCHEMA_VERSION + ")");
        }
        if (version < STATE_SCHEMA_VERSION) {
            // 【持久化】旧版迁移：v1→v2 字段同构无需变换，此处保留显式迁移点供后续版本演进
            migrate(stateMap, version, STATE_SCHEMA_VERSION);
        }

        // H5-步骤1: 全字段必需校验——缺字段响亮报错（对齐 autogen "恢复失败要响亮地失败"）
        // 【持久化】恢复强校验：必需字段缺失即抛 StateRestoreException，拒绝带病恢复
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
     * O4: 解析状态 schema 版本。
     * 缺失 / 非法 schemaVersion 视为 v1（存量无版本 JSON 兼容语义，回滚安全）。
     */
    private int resolveSchemaVersion(Map<String, Object> stateMap) {
        Object v = stateMap.get("schemaVersion");
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through → v1
            }
        }
        return 1;
    }

    /**
     * O4: 版本迁移钩子——fromVersion 升级到当前版本的字段归一化。
     *
     * <p>v1 → v2：v2 仅新增版本号，槽位字段与 v1 同构，无需字段变换；
     * 此处保留显式迁移点，后续新增槽位（如 toolContext 扩展键）在此按版本补默认值，
     * 避免 loadState 主体随字段演进膨胀。
     */
    protected void migrate(Map<String, Object> stateMap, int fromVersion, int toVersion) {
        log.info("状态迁移: schemaVersion {} → {}（当前版本无字段变换）", fromVersion, toVersion);
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
