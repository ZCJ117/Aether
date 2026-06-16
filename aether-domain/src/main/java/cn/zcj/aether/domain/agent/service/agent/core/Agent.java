package cn.zcj.aether.domain.agent.service.agent.core;

import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;

/**
 * Agent 核心接口。
 * 灵感来源：AutoGen ChatAgent + AgentScope Agent 接口 + CrewAI BaseAgent。
 *
 * 定义了所有 Agent 的最小契约：身份标识、执行能力、生命周期管理、状态序列化。
 */
public interface Agent {

    // ============ 身份 ============

    /** Agent 唯一标识（来自 YAML 配置中的 agent name） */
    String getId();

    /** Agent 显示名称 */
    String getName();

    /** Agent 能力描述（供 Speaker Selection 和 Group Chat 使用） */
    String getDescription();

    /** 不可变配置 */
    AgentConfig getConfig();

    // ============ 执行 ============

    /**
     * 执行 Agent 主循环。
     * @param context 不可变的 per-call 执行上下文（userId、sessionId、correlationId 等）
     * @return 运行时事件流（textDelta, toolCall, toolResult, done, error...）
     */
    Flowable<RuntimeEvent> execute(RuntimeContext context);

    // ============ 生命周期钩子 ============

    /** 执行前回调（默认空操作，子类覆盖实现横切关注点） */
    default void onBeforeExecute(RuntimeContext ctx) {}

    /** 执行后回调 */
    default void onAfterExecute(RuntimeContext ctx, AgentResult result) {}

    /** 执行出错回调 */
    default void onError(RuntimeContext ctx, Throwable error) {}

    // ============ 状态管理 ============

    /** 获取当前可变状态 */
    AgentState getState();

    /** 序列化当前状态为 Map（用于检查点/持久化） */
    Map<String, Object> saveState();

    /** 从序列化数据恢复状态（用于会话恢复） */
    void loadState(Map<String, Object> state);

    // ============ 能力声明 ============

    /** 该 Agent 能处理的 Action 类型列表（用于订阅-发布路由） */
    List<String> getCapabilities();
}
