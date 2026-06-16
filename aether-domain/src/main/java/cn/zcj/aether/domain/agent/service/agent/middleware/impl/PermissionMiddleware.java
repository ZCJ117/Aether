package cn.zcj.aether.domain.agent.service.agent.middleware.impl;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.middleware.AgentMiddleware;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionContext;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionDecision;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionEngine;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionMode;
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
 * 实现 P1-2 的 AgentMiddleware 接口。
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

        // 从 metadata 获取当前权限模式（默认 DEFAULT）
        PermissionMode mode = getPermissionMode(ctx);

        List<ToolExecutor.ToolCallRequest> allowed = new ArrayList<>();

        for (var request : requests) {
            Tool tool = toolRegistry.get(request.toolName());
            boolean isReadOnly = tool != null && tool.isReadOnly();

            PermissionContext permCtx = PermissionContext.builder()
                .toolName(request.toolName())
                .toolCallId(request.toolCallId())
                .toolInput(request.input())
                .agentId(agent.getId())
                .userId(ctx.userId())
                .sessionId(ctx.sessionId())
                .isReadOnly(isReadOnly)
                .mode(mode)
                .build();

            PermissionDecision decision = permissionEngine.check(permCtx, mode);

            switch (decision) {
                case ALLOW -> allowed.add(request);
                case DENY -> log.warn("权限拒绝: tool={}, userId={}, mode={}",
                    request.toolName(), ctx.userId(), mode);
                case ASK_USER -> {
                    // 需要用户确认——当前实现记录日志并暂允许
                    // 完整实现需要暂停执行流，等待用户响应
                    log.info("工具 [{}] 需要用户确认，当前会话暂允许", request.toolName());
                    allowed.add(request);
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
