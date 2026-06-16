package cn.zcj.aether.domain.agent.service.agent.hook;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentResult;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;

import java.util.List;

/**
 * Agent 生命周期钩子。
 * 灵感来源：AgentScope Hook 系统 + cc-haha hooks/ 模块。
 * 通过 SPI 或 Spring Bean 自动发现并注入到 Agent 中。
 *
 * P1-6 扩展：从 3 个拦截点扩展到 7 个（增加 onBeforeModelCall/onAfterModelCall/
 * onBeforeToolCall/onAfterToolCall）
 */
public interface AgentHook {

    /** 优先级：数值越小越先执行，默认 100 */
    default int priority() { return 100; }

    // ====== 原有（P0-1） ======

    /** 执行前 */
    default void onBeforeExecute(Agent agent, RuntimeContext ctx) {}

    /** 执行后 */
    default void onAfterExecute(Agent agent, RuntimeContext ctx, AgentResult result) {}

    /** 执行出错 */
    default void onError(Agent agent, RuntimeContext ctx, Throwable error) {}

    // ====== P1-6 新增 ======

    /**
     * 模型调用前触发。
     * @param turnNumber 当前 turn 编号
     */
    default void onBeforeModelCall(Agent agent, RuntimeContext ctx, int turnNumber) {}

    /**
     * 模型调用后触发。
     * @param result     模型调用结果
     * @param durationMs 调用耗时（毫秒）
     */
    default void onAfterModelCall(Agent agent, RuntimeContext ctx,
            ModelInvoker.ModelCallResult result, long durationMs) {}

    /**
     * 工具调用前触发（批量）。
     * @param requests 待执行的工具调用请求列表
     */
    default void onBeforeToolCall(Agent agent, RuntimeContext ctx,
            List<ToolExecutor.ToolCallRequest> requests) {}

    /**
     * 工具调用后触发（批量）。
     * @param results    工具执行结果列表
     * @param durationMs 总耗时（毫秒）
     */
    default void onAfterToolCall(Agent agent, RuntimeContext ctx,
            List<ToolResult> results, long durationMs) {}
}
