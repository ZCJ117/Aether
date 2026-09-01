package cn.zcj.aether.domain.agent.service.agent.hook;

import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentResult;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.observability.AgentMetrics;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 基于 Agent 钩子的自动 Metric 采集。
 * 作为 AgentHook 自动注入，零侵入采集 Agent 指标。
 */
@Component
public class MetricsHook implements AgentHook {

    @Resource
    private AgentMetrics metrics;

    @Override public int priority() { return 50; }

    @Override
    public void onBeforeExecute(Agent agent, RuntimeContext ctx) {
        // 不在此处采集，由 ReActAgent 内部的 AgentTracer Span 负责
    }

    @Override
    public void onAfterExecute(Agent agent, RuntimeContext ctx, AgentResult result) {
        if (result.isSuccess()) {
            metrics.recordTurn(result.durationMs(), agent.getId());
        }
    }

    @Override
    public void onError(Agent agent, RuntimeContext ctx, Throwable error) {
        metrics.recordError(agent.getId());
    }
}
