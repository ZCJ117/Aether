package cn.zcj.aether.domain.agent.service.agent.hook;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;

import java.util.List;
import java.util.Set;

/**
 * AgentHook → LifecycleHook 桥接适配 — O15（对应 D15）。
 *
 * <p>把 {@link AgentHook} 的 LLM/工具拦截点（onBeforeModelCall/onAfterModelCall/
 * onBeforeToolCall/onAfterToolCall）包装为 {@link HookPoint} 挂点，注册进
 * {@link HookRegistry} 的唯一生命周期钩子表，消除双套 Hook 体系"互不可见"的问题。
 *
 * <p>执行级拦截点（onBeforeExecute/onAfterExecute/onError）无 HookPoint 对应挂点，
 * 仍走 Agent 实例内的直接回调（{@code BaseAgent.onBeforeExecute} 等）。
 */
public class AgentHookBridge implements LifecycleHook {

    private final AgentHook delegate;

    public AgentHookBridge(AgentHook delegate) {
        this.delegate = delegate;
    }

    @Override
    public Set<HookPoint> points() {
        return Set.of(HookPoint.PRE_LLM_CALL, HookPoint.POST_LLM_CALL,
                HookPoint.PRE_TOOL_CALL, HookPoint.POST_TOOL_CALL);
    }

    @Override
    public int order() {
        return delegate.priority();
    }

    @Override
    public void onHook(HookPoint point, HookContext ctx) {
        Agent agent = ctx.agent();
        switch (point) {
            case PRE_LLM_CALL -> delegate.onBeforeModelCall(agent, ctx.runtimeCtx(),
                    ctx.turnNumber() != null ? ctx.turnNumber() : 0);
            case POST_LLM_CALL -> delegate.onAfterModelCall(agent, ctx.runtimeCtx(),
                    ctx.modelCallResult(),
                    ctx.durationMs() != null ? ctx.durationMs() : 0);
            case PRE_TOOL_CALL -> delegate.onBeforeToolCall(agent, ctx.runtimeCtx(),
                    ctx.toolRequests() != null ? ctx.toolRequests() : List.of());
            case POST_TOOL_CALL -> delegate.onAfterToolCall(agent, ctx.runtimeCtx(),
                    ctx.toolResults() != null ? ctx.toolResults() : List.of(),
                    ctx.durationMs() != null ? ctx.durationMs() : 0);
            default -> { }
        }
    }
}
