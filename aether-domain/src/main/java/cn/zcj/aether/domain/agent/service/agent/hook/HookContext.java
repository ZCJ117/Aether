package cn.zcj.aether.domain.agent.service.agent.hook;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import lombok.Builder;

import java.util.List;

/**
 * 生命周期钩子统一载荷 — 对齐 hermes invoke_hook 的 kwargs。
 * 各挂点按需填充字段，未用字段为 null。
 *
 * <p>O15：扩展类型化载荷字段（agent/runtimeCtx/modelCallResult/toolRequests/toolResults），
 * 使 {@link AgentHookBridge} 能把 {@link HookPoint} 挂点反向分发到 {@link AgentHook} 的强类型回调。
 */
@Builder
public record HookContext(
        String agentId,
        String sessionId,
        String graphNodeId,
        Integer turnNumber,
        String request,
        String response,
        String error,
        Long durationMs,
        // O15: 类型化载荷（LLM/工具挂点 → AgentHook 桥接）
        Agent agent,
        RuntimeContext runtimeCtx,
        ModelInvoker.ModelCallResult modelCallResult,
        List<ToolExecutor.ToolCallRequest> toolRequests,
        List<ToolResult> toolResults) {
}
