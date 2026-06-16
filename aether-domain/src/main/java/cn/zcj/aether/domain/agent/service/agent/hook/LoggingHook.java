package cn.zcj.aether.domain.agent.service.agent.hook;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentResult;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.event.AgentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 基于 Agent 生命周期的结构化日志钩子 — P0-6 新增。
 * 通过 @Component 注册为 Spring Bean，自动注入到所有 Agent。
 */
@Slf4j
@Component
public class LoggingHook implements AgentHook {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public int priority() { return 50; } // 最先执行

    @Override
    public void onBeforeExecute(Agent agent, RuntimeContext ctx) {
        MDC.put("agentId", agent.getId());
        MDC.put("sessionId", ctx.sessionId());
        MDC.put("correlationId", ctx.correlationId());
        MDC.put("userId", ctx.userId());

        AgentEvent.AgentStarted event = new AgentEvent.AgentStarted(
            agent.getId(), ctx.sessionId(), ctx.correlationId(),
            agent.getConfig().getAgentType(), agent.getConfig().getModelRef());

        log.info("agent_started: {}", toJson(event));
    }

    @Override
    public void onAfterExecute(Agent agent, RuntimeContext ctx, AgentResult result) {
        AgentEvent.AgentCompleted event = new AgentEvent.AgentCompleted(
            UUID.randomUUID().toString(), Instant.now(),
            agent.getId(), ctx.sessionId(), ctx.correlationId(),
            agent.getState().getCurrentTurn(),
            result.durationMs(),
            result.status());

        log.info("agent_completed: {}", toJson(event));
        MDC.clear();
    }

    @Override
    public void onError(Agent agent, RuntimeContext ctx, Throwable error) {
        AgentEvent.ErrorOccurred event = new AgentEvent.ErrorOccurred(
            agent.getId(), ctx.sessionId(), ctx.correlationId(),
            error.getClass().getSimpleName(),
            error.getMessage() != null ? error.getMessage() : "unknown",
            agent.getState().getCurrentTurn());

        log.error("agent_error: {}", toJson(event), error);
        MDC.clear();
    }

    private String toJson(Object obj) {
        try { return MAPPER.writeValueAsString(obj); } catch (Exception e) { return obj.toString(); }
    }
}
