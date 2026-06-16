package cn.zcj.aether.domain.agent.service.agent.hook;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentResult;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 组合 Hook —— 将多个 Hook 聚合为一个。
 * 用于将一组相关 Hook 作为整体注册。
 */
public class CompositeHook implements AgentHook {

    private final String name;
    private final int priority;
    private final List<AgentHook> children = new CopyOnWriteArrayList<>();

    public CompositeHook(String name, int priority, AgentHook... hooks) {
        this.name = name;
        this.priority = priority;
        this.children.addAll(List.of(hooks));
    }

    @Override
    public int priority() { return priority; }

    @Override
    public void onBeforeExecute(Agent agent, RuntimeContext ctx) {
        children.forEach(h -> h.onBeforeExecute(agent, ctx));
    }

    @Override
    public void onAfterExecute(Agent agent, RuntimeContext ctx, AgentResult result) {
        children.forEach(h -> h.onAfterExecute(agent, ctx, result));
    }

    @Override
    public void onError(Agent agent, RuntimeContext ctx, Throwable error) {
        children.forEach(h -> h.onError(agent, ctx, error));
    }

    @Override
    public void onBeforeModelCall(Agent agent, RuntimeContext ctx, int turnNumber) {
        children.forEach(h -> h.onBeforeModelCall(agent, ctx, turnNumber));
    }

    @Override
    public void onAfterModelCall(Agent agent, RuntimeContext ctx,
            ModelInvoker.ModelCallResult result, long durationMs) {
        children.forEach(h -> h.onAfterModelCall(agent, ctx, result, durationMs));
    }

    @Override
    public void onBeforeToolCall(Agent agent, RuntimeContext ctx,
            List<ToolExecutor.ToolCallRequest> requests) {
        children.forEach(h -> h.onBeforeToolCall(agent, ctx, requests));
    }

    @Override
    public void onAfterToolCall(Agent agent, RuntimeContext ctx,
            List<ToolResult> results, long durationMs) {
        children.forEach(h -> h.onAfterToolCall(agent, ctx, results, durationMs));
    }
}
