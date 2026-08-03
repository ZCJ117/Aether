package cn.zcj.aether.domain.agent.service.agent.middleware.impl;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentState;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.middleware.AgentMiddleware;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionContext;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionDecision;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionEngine;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionMode;
import cn.zcj.aether.domain.agent.service.agent.permission.SuspendedToolCall;
import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 权限中间件 —— 在 onActing 拦截点检查每个工具调用的权限。
 * H4 重构：ASK_USER 决策不再"暂允许"，改为"挂起-确认-恢复"的一等公民状态。
 *
 * <p>流程：
 * <ol>
 *   <li>DENY → 拒绝执行（记录日志）</li>
 *   <li>ALLOW → 加入本次允许执行列表</li>
 *   <li>ASK_USER → 登记为 SuspendedToolCall(ASKING) 写入 AgentState.permissionContext，
 *       本批次中其余 ALLOW 请求仍正常放行</li>
 * </ol>
 *
 * <p>挂起后由 ReActAgent 检测 asking 非空 → 发 RequireUserConfirmEvent → PAUSED → 终止执行流。
 */
@Slf4j
@Component
public class PermissionMiddleware implements AgentMiddleware {

    @Resource
    private PermissionEngine permissionEngine;

    @Resource
    private ToolRegistry toolRegistry;

    @Override public String name() { return "permission"; }
    @Override public int priority() { return 20; }

    @Override
    public List<ToolExecutor.ToolCallRequest> onActing(
            List<ToolExecutor.ToolCallRequest> requests,
            Agent agent, RuntimeContext ctx) {

        PermissionMode mode = getPermissionMode(ctx);
        AgentState state = agent.getState();

        List<ToolExecutor.ToolCallRequest> allowed = new ArrayList<>();

        for (var request : requests) {
            Tool tool = toolRegistry.get(request.toolName());
            boolean isReadOnly = tool != null && tool.isReadOnly();

            // M3: 检测子Agent上下文标记
            boolean isSubAgent = ctx.metadata() != null
                    && Boolean.TRUE.equals(ctx.metadata().get("subAgentContext"));

            PermissionContext permCtx = PermissionContext.builder()
                .toolName(request.toolName())
                .toolCallId(request.toolCallId())
                .toolInput(request.input())
                .agentId(agent.getId())
                .userId(ctx.userId())
                .sessionId(ctx.sessionId())
                .isReadOnly(isReadOnly)
                .mode(mode)
                .isSubAgentContext(isSubAgent)
                .build();

            PermissionDecision decision = permissionEngine.check(permCtx, mode);

            switch (decision) {
                case ALLOW -> allowed.add(request);
                case DENY -> log.warn("权限拒绝: tool={}, userId={}, mode={}",
                    request.toolName(), ctx.userId(), mode);
                case ASK_USER -> {
                    // 【H4-步骤4】ASK_USER 不再"暂允许"——
                    // 登记为 SuspendedToolCall，写入 AgentState.permissionContext
                    SuspendedToolCall suspended = new SuspendedToolCall(
                            request.toolCallId(),
                            request.toolName(),
                            request.input(),
                            "工具 [" + request.toolName() + "] 需要用户确认",
                            SuspendedToolCall.SuspendedState.ASKING);
                    state.askingMutable().add(suspended);

                    log.info("工具 [{}] 挂起等待用户确认: toolCallId={}, userId={}",
                            request.toolName(), request.toolCallId(), ctx.userId());
                }
            }
        }

        return allowed;
    }

    private PermissionMode getPermissionMode(RuntimeContext ctx) {
        if (ctx.metadata() == null) return PermissionMode.DEFAULT;
        String modeStr = (String) ctx.metadata().get("permissionMode");
        if (modeStr != null) {
            try { return PermissionMode.valueOf(modeStr.toUpperCase()); }
            catch (IllegalArgumentException e) { /* fall through */ }
        }
        return PermissionMode.DEFAULT;
    }
}
