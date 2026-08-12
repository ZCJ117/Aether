package cn.zcj.aether.domain.agent.service.subagent;

import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolContext;
import cn.zcj.aether.domain.agent.service.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 子Agent委派工具 — 将 SubAgentOrchestrator.dispatch() 建模为 Tool 接口。
 *
 * <p>M2 委派即工具（Delegation-as-Tool）—— 对齐 crewAI 的 DelegateWorkTool 模式：
 * LLM 通过标准 tool_use 调用本工具发起子Agent派遣，天然复用 P0 的 ToolExecutor
 * 两级校验（Schema + 自定义）、权限检查链，以及 P1 的容错重试链路。
 *
 * <h3>工具参数（inputSchema）</h3>
 * <ul>
 *   <li>{@code task} (string, required) — 子任务描述</li>
 *   <li>{@code tool_names} (string[], optional) — 允许子Agent使用的工具名列表，默认无工具</li>
 *   <li>{@code model_ref} (string, optional) — 模型引用，默认复用父Agent模型</li>
 * </ul>
 *
 * <p>isConcurrencySafe() 返回 false：SubAgentOrchestrator 内部有 Semaphore(5) 限流。
 * isReadOnly() 返回 false：子Agent可能执行写操作。
 */
@Slf4j
public class SubAgentDelegationTool implements Tool {

    private static final String TOOL_NAME = "delegate_to_subagent";
    private static final String TOOL_DESCRIPTION = """
            将子任务委派给隔离的临时Agent执行。适用场景：
            - 需要独立上下文执行的分析任务
            - 可并行的独立子问题
            - 需要隔离工具集的敏感操作
            返回结构化摘要（含状态、结论、涉及文件和工具调用统计）。
            注意：子Agent有60秒超时限制，不适合超长任务。""";

    private final SubAgentOrchestrator orchestrator;
    private final SpawnGate spawnGate;                 // 可空（未接线时仅同步路径）
    private final AsyncDelegationService asyncService; // 可空（未接线时仅同步路径）

    public SubAgentDelegationTool(SubAgentOrchestrator orchestrator) {
        this(orchestrator, null, null);
    }

    public SubAgentDelegationTool(SubAgentOrchestrator orchestrator, SpawnGate spawnGate, AsyncDelegationService asyncService) {
        this.orchestrator = orchestrator;
        this.spawnGate = spawnGate;
        this.asyncService = asyncService;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return TOOL_DESCRIPTION;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of(
                                "type", "string",
                                "description", "子任务描述，明确说明需要完成什么、返回什么格式"),
                        "tool_names", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "允许子Agent使用的工具名称列表。为空或省略则子Agent无工具可用。"),
                        "model_ref", Map.of(
                                "type", "string",
                                "description", "可选，模型引用。省略则复用父Agent的模型。"),
                        "async", Map.of(
                                "type", "boolean",
                                "description", "可选。true 时异步委派（立即返回 delegationId+QUEUED，不等待子Agent完成）；默认 false 同步等待。")
                ),
                "required", List.of("task")
        );
    }

    @Override
    public ToolResult call(Map<String, Object> input, ToolContext context) {
        String task = (String) input.get("task");
        if (task == null || task.isBlank()) {
            return ToolResult.error(context.toolCallId(), name(),
                    "参数 'task' 为空 — 请提供明确的子任务描述",
                    ToolResult.ErrorType.VALIDATION);
        }

        @SuppressWarnings("unchecked")
        List<String> toolNames = input.get("tool_names") instanceof List<?> list
                ? (List<String>) list.stream()
                        .filter(o -> o instanceof String)
                        .map(Object::toString)
                        .toList()
                : List.of();

        String modelRef = input.get("model_ref") instanceof String s && !s.isBlank()
                ? s : null;

        String userId = context.userId() != null ? context.userId() : "system";
        String parentSessionId = context.sessionId() != null ? context.sessionId() : "delegation";

        // D1: SpawnGate 闸门（暂停/深度上限 → 拒绝，对齐 hermes delegate_tool.py L2775）
        if (spawnGate != null && !spawnGate.enter()) {
            return ToolResult.error(context.toolCallId(), name(),
                    "[委派被拒绝: spawn 已暂停或达到深度上限]",
                    ToolResult.ErrorType.EXECUTION);
        }
        try {
            // D1: 异步委派模式（方案 a：平行新能力，同步路径保留）
            if (asyncService != null && Boolean.TRUE.equals(input.get("async"))) {
                String delegationId = asyncService.dispatch(new DelegationTask(
                        task, toolNames, modelRef, userId, parentSessionId, null));
                if (delegationId == null) {
                    return ToolResult.error(context.toolCallId(), name(),
                            "[委派被拒绝: session 并发委派达上限]",
                            ToolResult.ErrorType.EXECUTION);
                }
                log.info("SubAgentDelegationTool: 异步委派已提交 id={}", delegationId);
                return ToolResult.success(context.toolCallId(), name(),
                        "[委派已异步提交] delegationId=" + delegationId + " state=QUEUED");
            }

            log.info("SubAgentDelegationTool: 发起委派 task='{}' tools={} model={} session={}",
                    task.length() > 80 ? task.substring(0, 77) + "..." : task,
                    toolNames, modelRef, parentSessionId);

            // 父Agent的 TokenBudget 不可用于子Agent（隔离上下文），传 null
            ResultRefiner.SubAgentResult result = orchestrator.dispatch(
                    task, toolNames, null, modelRef, userId, parentSessionId);

            String output = result.summary();
            boolean success = "成功".equals(result.status())
                    || "超时".equals(result.status());

            if (success) {
                log.info("SubAgentDelegationTool: 委派完成 status={} toolsUsed={}",
                        result.status(), result.toolStats());
                return ToolResult.success(context.toolCallId(), name(), output);
            } else {
                log.warn("SubAgentDelegationTool: 委派失败 status={} summary={}",
                        result.status(),
                        result.summary().length() > 200 ? result.summary().substring(0, 197) + "..." : result.summary());
                return ToolResult.error(context.toolCallId(), name(),
                        "[委派失败: " + result.status() + "] " + output,
                        ToolResult.ErrorType.EXECUTION);
            }
        } finally {
            if (spawnGate != null) {
                spawnGate.exit();
            }
        }
    }

    @Override
    public boolean isConcurrencySafe() {
        // SubAgentOrchestrator 内部有 Semaphore(5) 限流，不支持无限并发
        return false;
    }

    @Override
    public boolean isReadOnly() {
        // 子Agent可能执行写操作
        return false;
    }

    // ========== 权限检查 ==========

    /**
     * 委派操作的权限检查。
     *
     * <p>当前默认放行——更细粒度的委派权限控制将在
     * Batch C (M3) 的 SubAgentDenyApprovalRule 中落地。
     * 届时本方法可委托给 PermissionEngine。
     */
    @Override
    public boolean checkPermissions(Map<String, Object> input) {
        // 默认放行：委派是 Agent 的核心能力
        // M3 落地后此处改为委托 PermissionEngine 评估 DenyApprovalRule
        return true;
    }
}
