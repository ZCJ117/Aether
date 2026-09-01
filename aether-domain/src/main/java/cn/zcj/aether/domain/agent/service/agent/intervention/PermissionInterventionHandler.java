package cn.zcj.aether.domain.agent.service.agent.intervention;

import cn.zcj.aether.domain.agent.service.agent.permission.InjectionGuardRule;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionEngine;
import cn.zcj.aether.domain.agent.service.agent.permission.PermissionMode;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * H4-步骤7: 权限感知的拦截处理器。
 *
 * <p>实现 AutoGen 风格的通道差异化拦截：
 * <ul>
 *   <li><b>DIRECT 通道</b>（SEQUENTIAL / LOOP / SUBAGENT）：
 *       拦截到的异常（注入检测、权限拒绝）以 BLOCK 回传调用方，
 *       确保等待方感知故障并终止当前链路。</li>
 *   <li><b>BROADCAST 通道</b>（PARALLEL / GRAPHFLOW 并发批次）：
 *       拦截到的异常降级为 DROP + 日志记录，不波及其他并发节点。
 *       对齐 AutoGen _single_threaded_agent_runtime.py L748-750：
 *       "Publish 通道只记日志静默，避免一个订阅者的拦截故障波及其他订阅者。"</li>
 * </ul>
 */
@Slf4j
public class PermissionInterventionHandler extends DefaultInterventionHandler {

    private final InjectionGuardRule injectionGuard;
    private final PermissionMode permissionMode;

    public PermissionInterventionHandler(InjectionGuardRule injectionGuard, PermissionMode permissionMode) {
        this.injectionGuard = injectionGuard;
        this.permissionMode = permissionMode != null ? permissionMode : PermissionMode.DEFAULT;
    }

    @Override
    public InterventionResult onSend(String message, InterventionContext ctx) {
        // DIRECT 通道：注入检测 → BLOCK 回传调用方
        return checkInjection(message, ctx, true);
    }

    @Override
    public InterventionResult onPublish(String message, InterventionContext ctx) {
        // BROADCAST 通道：注入检测 → DROP + 日志（不波及其他节点）
        InterventionResult result = checkInjection(message, ctx, false);
        if (!result.isPass() && result.isBlock()) {
            // BROADCAST 通道中 BLOCK 降级为 DROP
            log.warn("BROADCAST 通道拦截降级为 DROP: agent={}, reason={}",
                    ctx.agentId(), result.reason());
            return InterventionResult.drop(
                    (result.reason() != null ? result.reason() : "广播通道阻断降级")
                            + " [广播通道降级：已静默丢弃，不影响其他节点]");
        }
        return result;
    }

    @Override
    public InterventionResult onResponse(String message, InterventionContext ctx) {
        // 响应通道：检查输出中是否包含敏感信息泄露
        return checkInjection(message, ctx, ctx.isDirect());
    }

    /**
     * 注入检测核心逻辑。
     *
     * @param message     待检测的消息文本
     * @param ctx         拦截上下文
     * @param canBlock    是否可以返回 BLOCK（false 时 BLOCK 降级为 DROP）
     * @return 拦截结果
     */
    private InterventionResult checkInjection(String message, InterventionContext ctx, boolean canBlock) {
        if (message == null || message.isEmpty()) {
            return InterventionResult.pass();
        }

        // 构建简化的权限上下文用于注入检测
        cn.zcj.aether.domain.agent.service.agent.permission.PermissionContext permCtx =
                cn.zcj.aether.domain.agent.service.agent.permission.PermissionContext.builder()
                        .toolName(ctx.edgeType() != null ? ctx.edgeType() : "message")
                        .toolCallId(ctx.agentId())
                        .toolInput(Map.of("message", message))
                        .agentId(ctx.agentId())
                        .userId(ctx.userId())
                        .sessionId(ctx.sessionId())
                        .isReadOnly(false)
                        .mode(permissionMode)
                        .build();

        // 使用 InjectionGuardRule 检测注入
        cn.zcj.aether.domain.agent.service.agent.permission.PermissionDecision decision =
                injectionGuard.evaluate(permCtx);

        if (decision == cn.zcj.aether.domain.agent.service.agent.permission.PermissionDecision.DENY) {
            String reason = "注入防护规则拒绝: agent=" + ctx.agentId()
                    + ", channel=" + ctx.channelType()
                    + ", edge=" + ctx.edgeType();
            if (canBlock) {
                log.warn("DIRECT 通道拦截阻断: {}", reason);
                return InterventionResult.block(reason);
            } else {
                log.warn("BROADCAST 通道拦截丢弃: {}", reason);
                return InterventionResult.drop(reason);
            }
        }

        if (decision == cn.zcj.aether.domain.agent.service.agent.permission.PermissionDecision.ASK_USER) {
            String reason = "注入防护规则请求确认: agent=" + ctx.agentId()
                    + ", channel=" + ctx.channelType();
            if (canBlock) {
                log.info("DIRECT 通道注入疑似，转人工确认: {}", reason);
                return InterventionResult.block(reason);
            } else {
                log.info("BROADCAST 通道注入疑似，静默丢弃: {}", reason);
                return InterventionResult.drop(reason);
            }
        }

        return InterventionResult.pass();
    }
}
