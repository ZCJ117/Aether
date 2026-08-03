package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 子Agent编排器。
 * 通过信号量（最大并发5）控制子Agent的派遣，避免资源耗尽。
 */
@Slf4j
@Component
public class SubAgentOrchestrator {
    private final DefaultAgentFactory agentFactory;
    private final SubAgentBoundary boundary;
    private final ResultRefiner refiner;
    private final Semaphore semaphore = new Semaphore(5);

    @Resource
    private ToolRegistry toolRegistry;

    public SubAgentOrchestrator(DefaultAgentFactory agentFactory,
            SubAgentBoundary boundary, ResultRefiner refiner) {
        this.agentFactory = agentFactory;
        this.boundary = boundary;
        this.refiner = refiner;
    }

    /**
     * 派遣一个子Agent执行子任务。
     *
     * @param task             子任务描述
     * @param toolNames        可用工具名称列表（最多取前5个）
     * @param parentBudget     父Agent的Token预算（可为null，null时跳过预算检查）
     * @param modelRef         模型引用
     * @param userId           用户标识
     * @param parentSessionId  父会话标识
     * @return 结构化子Agent结果
     */
    public ResultRefiner.SubAgentResult dispatch(String task, List<String> toolNames,
            TokenBudget parentBudget, String modelRef, String userId, String parentSessionId) {
        try {
            if (!semaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                return new ResultRefiner.SubAgentResult("失败",
                        "[并发子Agent数已达上限]", Map.of());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ResultRefiner.SubAgentResult("失败", "[子任务被中断]", Map.of());
        }
        try {
            // M3: 生成任务ID（基于任务哈希的前8位作为命名空间标识）
            String taskId = "t" + Integer.toHexString(Math.abs(task.hashCode())).substring(0, 6);

            AgentConfig config = boundary.createIsolatedConfig(parentSessionId,
                    task, toolNames, modelRef, taskId, null);
            Agent subAgent = agentFactory.create(config);

            // M3: 子Agent上下文标记 — 设置 metadata.subAgentContext=true
            // 此标记由 PermissionMiddleware 读取，触发 SubAgentDenyApprovalRule 的 auto-deny
            Map<String, Object> subMetadata = new java.util.HashMap<>();
            subMetadata.put("subAgentContext", Boolean.TRUE);
            subMetadata.put("taskId", taskId);

            RuntimeContext ctx = new RuntimeContext(userId, config.getName(),
                    null, null, task, subMetadata, null);
            List<TurnMessage> collected = new ArrayList<>();
            long start = System.currentTimeMillis();
            subAgent.execute(ctx)
                    .takeUntil((io.reactivex.rxjava3.functions.Predicate<RuntimeEvent>) event ->
                            config.getCancelToken().isCancelled())
                    .blockingForEach(event -> {
                if (event.getType() == RuntimeEvent.EventType.textDelta
                        && event.getText() != null) {
                    collected.add(TurnMessage.assistant(event.getText()));
                }
                if (event.getType() == RuntimeEvent.EventType.toolResult) {
                    collected.add(TurnMessage.toolResult(event.getToolCallId(),
                            event.getToolName(), event.getToolOutput()));
                }
            });
            long duration = System.currentTimeMillis() - start;
            ResultRefiner.SubAgentResult result = refiner.refine(task, collected);
            log.info("SubAgentOrchestrator: task={} status={} durationMs={}",
                    task, result.status(), duration);
            return result;
        } catch (Exception e) {
            log.error("SubAgentOrchestrator 派遣失败: task={}", task, e);
            return new ResultRefiner.SubAgentResult("失败",
                    "[子任务异常: " + e.getMessage() + "]", Map.of());
        } finally {
            semaphore.release();
        }
    }
}
