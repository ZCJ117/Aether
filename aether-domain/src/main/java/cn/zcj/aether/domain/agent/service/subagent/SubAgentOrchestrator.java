package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.agent.DefaultAgentFactory;
import cn.zcj.aether.domain.agent.service.agent.core.Agent;
import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookContext;
import cn.zcj.aether.domain.agent.service.agent.hook.HookPoint;
import cn.zcj.aether.domain.agent.service.agent.hook.HookRegistry;
import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 子Agent编排器（同步路径）。
 * 通过信号量（最大并发5）控制子Agent的派遣，避免资源耗尽。
 *
 * <p>O1（对应 D1）：同步路径复用 {@link SubagentLifecycleService} 状态机为唯一状态源——
 * dispatch 登记 SubagentRuntime（对 stale 扫描 / activeIds / cancel 可见），
 * 事件循环内刷新心跳；超时/终态语义与异步路径一致。</p>
 */
@Slf4j
@Component
public class SubAgentOrchestrator {
    private final DefaultAgentFactory agentFactory;
    private final SubAgentBoundary boundary;
    private final ResultRefiner refiner;
    /** O1: 状态机唯一状态源（可空：测试直连时未装配）。 */
    private final SubagentLifecycleService lifecycle;
    private final Semaphore semaphore = new Semaphore(5);

    @Resource
    private ToolRegistry toolRegistry;

    // ── D3 子代理生命周期钩子（对齐 hermes subagent_start / subagent_stop）──
    @Resource
    private HookRegistry hookRegistry;

    /** 测试注入点：绕过 Spring 上下文直接设置 HookRegistry */
    void setHookRegistryForTest(HookRegistry registry) {
        this.hookRegistry = registry;
    }

    /** 触发子代理生命周期钩子（空安全，对齐 GraphExecutor.notifyGraphHook 防御模式） */
    private void notifyHook(HookPoint point, HookContext ctx) {
        if (hookRegistry != null) {
            hookRegistry.invokeAll(point, ctx);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SubAgentOrchestrator(DefaultAgentFactory agentFactory,
            SubAgentBoundary boundary, ResultRefiner refiner,
            org.springframework.beans.factory.ObjectProvider<SubagentLifecycleService> lifecycleProvider) {
        this(agentFactory, boundary, refiner, lifecycleProvider.getIfAvailable());
    }

    /** 测试/直连构造：lifecycle 可空（空时跳过状态机登记，行为退回纯本地）。 */
    SubAgentOrchestrator(DefaultAgentFactory agentFactory,
            SubAgentBoundary boundary, ResultRefiner refiner,
            SubagentLifecycleService lifecycle) {
        this.agentFactory = agentFactory;
        this.boundary = boundary;
        this.refiner = refiner;
        this.lifecycle = lifecycle;
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
        AgentConfig config = null;
        ResultRefiner.SubAgentResult result = null;
        try {
                // M3: 生成任务ID（基于任务哈希的前8位作为命名空间标识）
                final String taskId = "t" + Integer.toHexString(Math.abs(task.hashCode())).substring(0, 6);

                config = boundary.createIsolatedConfig(parentSessionId,
                        task, toolNames, modelRef, taskId, null);
                Agent subAgent = agentFactory.create(config);

                // O1: 同步路径登记进状态机（唯一状态源）+ 置 RUNNING
                final SubagentRuntime rt;
                if (lifecycle != null) {
                    rt = lifecycle.registerSync("sync-" + taskId,
                            new DelegationTask(task, toolNames, modelRef, userId, parentSessionId, null),
                            config, subAgent);
                    rt.toRunning();
                } else {
                    rt = null;
                }

            // M3: 子Agent上下文标记 — 设置 metadata.subAgentContext=true
            // 此标记由 PermissionMiddleware 读取，触发 SubAgentDenyApprovalRule 的 auto-deny
            Map<String, Object> subMetadata = new java.util.HashMap<>();
            subMetadata.put("subAgentContext", Boolean.TRUE);
            subMetadata.put("taskId", taskId);

            // D3: SUBAGENT_START（对齐 hermes subagent_start）
            notifyHook(HookPoint.SUBAGENT_START, HookContext.builder()
                    .agentId(config.getName()).sessionId(parentSessionId).request(task).build());

            // O3: agentId=子Agent 自身（日志/审计归属正确）
            RuntimeContext ctx = new RuntimeContext(userId, config.getName(),
                    null, null, task, subMetadata, null, config.getName());
            List<TurnMessage> collected = new ArrayList<>();
            long start = System.currentTimeMillis();
            // 局部最终引用：供 lambda 捕获（config 字段为可变，用于 finally 访问）
            final AgentConfig execConfig = config;
            subAgent.execute(ctx)
                    .takeUntil((io.reactivex.rxjava3.functions.Predicate<RuntimeEvent>) event ->
                            execConfig.getCancelToken().isCancelled())
                    .blockingForEach(event -> {
                // O1: 事件循环内刷新心跳（与异步 runAgent 一致的进度信号）
                if (rt != null) {
                    rt.heartbeat();
                }
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
            result = refiner.refine(task, collected);
            // O1: 终态落地——token 绝对到期视为 TIMED_OUT，正常完成 COMPLETED（与异步语义一致）
            if (rt != null) {
                lifecycle.finishSync(rt, result, execConfig.getCancelToken().isCancelled()
                        ? SubagentState.TIMED_OUT : SubagentState.COMPLETED);
            }
            log.info("SubAgentOrchestrator: task={} status={} durationMs={}",
                    task, result.status(), duration);
            return result;
        } catch (Exception e) {
            log.error("SubAgentOrchestrator 派遣失败: task={}", task, e);
            result = new ResultRefiner.SubAgentResult("失败",
                    "[子任务异常: " + e.getMessage() + "]", Map.of());
            return result;
        } finally {
            // D3: SUBAGENT_STOP（对齐 hermes subagent_stop）
            notifyHook(HookPoint.SUBAGENT_STOP, HookContext.builder()
                    .agentId(config != null ? config.getName() : "unknown")
                    .sessionId(parentSessionId)
                    .request(task)
                    .response(result != null ? result.summary() : null)
                    .build());
            semaphore.release();
        }
    }
}
