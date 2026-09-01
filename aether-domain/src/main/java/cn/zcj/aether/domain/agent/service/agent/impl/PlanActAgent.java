package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.core.*;
import cn.zcj.aether.domain.agent.service.agent.middleware.MiddlewareChain;
import cn.zcj.aether.domain.agent.service.agent.observability.AgentMetrics;
import cn.zcj.aether.domain.agent.service.agent.observability.BackgroundReviewer;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.curation.CurationPipeline;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes.NoteCategory;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes.NoteItem;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes.NotesDocument;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plan-Act-Synthesize agent with adaptive re-planning.
 *
 * <p>P2-4.4 adds three closing-loop mechanisms: consecutive tool failures,
 * LLM checkpoint evaluation, and post-task reflection persisted to notes.</p>
 */
@Slf4j
public class PlanActAgent extends BaseAgent {

    private static final int MAX_STEP_RETRIES = 2;
    private static final int CONSECUTIVE_TOOL_FAILURE_REPLAN = 2;

    private final ChatModel chatModel;
    private final ModelInvoker modelInvoker;
    private final ToolExecutor toolExecutor;
    private final ContextManager contextManager;
    private final AgentEventPublisher eventPublisher;
    private final CheckpointCollector checkpointCollector;
    private final TokenBudget tokenBudget;
    private final CurationPipeline curationPipeline;
    private final ExternalNotes externalNotes;
    private final BackgroundReviewer backgroundReviewer;
    private final AgentMetrics metrics;

    public PlanActAgent(AgentConfig config, ChatModel chatModel, ModelInvoker modelInvoker,
                        ToolExecutor toolExecutor, ContextManager contextManager,
                        AgentEventPublisher eventPublisher, CheckpointCollector checkpointCollector,
                        TokenBudget tokenBudget, CurationPipeline curationPipeline,
                        ExternalNotes externalNotes) {
        this(config, chatModel, modelInvoker, toolExecutor, contextManager, eventPublisher,
                checkpointCollector, tokenBudget, curationPipeline, externalNotes, null, null);
    }

    public PlanActAgent(AgentConfig config, ChatModel chatModel, ModelInvoker modelInvoker,
                        ToolExecutor toolExecutor, ContextManager contextManager,
                        AgentEventPublisher eventPublisher, CheckpointCollector checkpointCollector,
                        TokenBudget tokenBudget, CurationPipeline curationPipeline,
                        ExternalNotes externalNotes, BackgroundReviewer backgroundReviewer,
                        AgentMetrics metrics) {
        super(config);
        this.chatModel = chatModel;
        this.modelInvoker = modelInvoker;
        this.toolExecutor = toolExecutor;
        this.contextManager = contextManager;
        this.eventPublisher = eventPublisher;
        this.checkpointCollector = checkpointCollector;
        this.tokenBudget = tokenBudget;
        this.curationPipeline = curationPipeline;
        this.externalNotes = externalNotes;
        this.backgroundReviewer = backgroundReviewer;
        this.metrics = metrics;
    }

    @Override
    public Flowable<RuntimeEvent> execute(RuntimeContext ctx) {
        return Flowable.create(emitter -> {
            try {
                MiddlewareChain chain = new MiddlewareChain(this, ctx);
                AtomicBoolean aborted = new AtomicBoolean(false);
                emitter.setCancellable(() -> aborted.set(true));

                Plan plan = generatePlan(ctx.initialMessage(), config.getInstruction());
                if (plan.getSteps().isEmpty()) {
                    emitter.onNext(RuntimeEvent.error("无法生成执行计划"));
                    emitter.onComplete();
                    return;
                }
                emitPlan(emitter, plan);

                List<StepResult> stepResults = new ArrayList<>();
                int stepIndex = 0;
                int maxIterations = plan.getSteps().size() * 3;
                int consecutiveToolFailures = 0;
                java.util.concurrent.atomic.AtomicInteger toolFailures =
                        new java.util.concurrent.atomic.AtomicInteger();

                while (stepIndex < plan.getSteps().size() && !aborted.get() && maxIterations-- > 0) {
                    Plan.Step step = plan.getSteps().get(stepIndex);
                    if (isSkipped(step)) {
                        stepIndex++;
                        continue;
                    }
                    if (dependencyFailed(plan, step, emitter)) {
                        stepIndex++;
                        continue;
                    }

                    step.setStatus("running");
                    emitStepStart(emitter, step, plan);
                    toolFailures.set(0);
                    StepResult result = executeStep(ctx, step, emitter, chain, toolFailures);
                    stepResults.add(result);

                    boolean stepSuccess = result.isSuccess()
                            && toolFailures.get() < CONSECUTIVE_TOOL_FAILURE_REPLAN
                            && evaluateStep(plan, step, result.getOutput());
                    if (!stepSuccess) {
                        step.setStatus("failed");
                        step.setRetryCount(step.getRetryCount() + 1);
                        consecutiveToolFailures++;
                        String reason = toolFailures.get() >= CONSECUTIVE_TOOL_FAILURE_REPLAN
                                ? "连续工具失败" : "检查点评估不达标";
                        if (step.getRetryCount() >= MAX_STEP_RETRIES) {
                            emitter.onNext(RuntimeEvent.text("\n🔁 " + reason + "，触发重规划。\n"));
                            Plan replanned = replan(plan, step, stepResults, reason);
                            plan = replanned;
                            maxIterations = plan.getSteps().size() * 3;
                            stepIndex = firstPendingIndex(plan);
                            consecutiveToolFailures = 0;
                        }
                        continue;
                    }

                    step.setStatus("completed");
                    step.setResult(result.getOutput());
                    consecutiveToolFailures = 0;
                    stepIndex++;
                }

                if (aborted.get()) {
                    if (metrics != null) metrics.recordPlanCompletion(false);
                    emitter.onComplete();
                    return;
                }

                String finalAnswer = synthesize(plan, stepResults, config.getInstruction());
                emitter.onNext(RuntimeEvent.text(finalAnswer));
                try {
                    reflectAndPersist(ctx.sessionId(), plan, finalAnswer);
                } catch (Exception reflectionError) {
                    log.warn("[PlanActAgent] 任务后反思失败: {}", reflectionError.getMessage(), reflectionError);
                }
                if (metrics != null) metrics.recordPlanCompletion(!hasUnfinishedSteps(plan));
                emitter.onNext(RuntimeEvent.done());
                emitter.onComplete();
            } catch (Exception e) {
                log.error("[PlanActAgent] 执行失败", e);
                if (metrics != null) metrics.recordPlanCompletion(false);
                emitter.onNext(RuntimeEvent.error(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage()));
                emitter.onComplete();
            }
        }, BackpressureStrategy.BUFFER);
    }

    private StepResult executeStep(RuntimeContext parentCtx, Plan.Step step, FlowableEmitter<RuntimeEvent> emitter,
                                   MiddlewareChain ignoredChain,
                                   java.util.concurrent.atomic.AtomicInteger toolFailures) {
        long stepStart = System.currentTimeMillis();
        AgentConfig stepConfig = AgentConfig.builder()
                .name(config.getName() + "-step-" + step.getId())
                .instruction(step.getDescription() + "\n预期产出: " + step.getExpectedOutput())
                .toolNames(config.getToolNames())
                .modelRef(config.getModelRef())
                .agentType("react")
                .checkpointEnabled(false)
                .cancelToken(new CancelToken())
                .build();
        ReActAgent subAgent = new ReActAgent(stepConfig, chatModel, modelInvoker, toolExecutor,
                contextManager, eventPublisher, checkpointCollector, tokenBudget, null,
                curationPipeline, externalNotes);
        RuntimeContext stepCtx = new RuntimeContext(parentCtx.userId(),
                parentCtx.sessionId() + "-s" + step.getId(), parentCtx.correlationId(), null,
                step.getDescription(), Map.of(), null, stepConfig.getName());

        List<String> outputs = new ArrayList<>();
        AtomicReference<StepResult> resultRef = new AtomicReference<>();
        try {
            subAgent.execute(stepCtx)
                    .doOnNext(event -> {
                        emitter.onNext(event);
                        if (event.getType() == RuntimeEvent.EventType.toolResult && event.isToolError()) {
                            toolFailures.incrementAndGet();
                        }
                        if (event.getType() == RuntimeEvent.EventType.textDelta && event.getText() != null) {
                            outputs.add(event.getText());
                        }
                    })
                    .doOnComplete(() -> resultRef.set(StepResult.builder()
                            .stepId(step.getId()).description(step.getDescription())
                            .output(String.join("", outputs)).success(true)
                            .durationMs(System.currentTimeMillis() - stepStart).build()))
                    .blockingSubscribe();
        } catch (Exception e) {
            log.warn("[PlanActAgent] step {} 执行失败: {}", step.getId(), e.getMessage(), e);
            emitter.onNext(RuntimeEvent.text("\n⚠️ 步骤" + step.getId() + "失败: " + e.getMessage() + "\n"));
        }
        if (resultRef.get() == null) {
            resultRef.set(StepResult.builder()
                    .stepId(step.getId()).description(step.getDescription())
                    .output(String.join("", outputs)).success(false)
                    .durationMs(System.currentTimeMillis() - stepStart).build());
        }
        return resultRef.get();
    }

    boolean hasUnfinishedSteps(Plan plan) {
        return plan.getSteps().stream().anyMatch(step -> !"completed".equals(step.getStatus()));
    }

    Plan replan(Plan current, Plan.Step failedStep, List<StepResult> results, String reason) {
        if (metrics != null) metrics.recordPlanReplan();
        Plan generated;
        try {
            String json = callModel(replanPrompt(current, failedStep, results, reason));
            generated = Plan.parse(extractJson(json));
            if (generated.getSteps().isEmpty()) {
                throw new IllegalStateException("重规划结果为空");
            }
        } catch (Exception e) {
            log.warn("[PlanActAgent] 重规划失败，保留失败步骤待重试: {}", e.getMessage());
            return current;
        }

        List<Plan.Step> merged = new ArrayList<>();
        for (Plan.Step step : current.getSteps()) {
            if ("completed".equals(step.getStatus())) {
                merged.add(step);
            }
        }
        int nextId = current.getSteps().stream().mapToInt(Plan.Step::getId).max().orElse(0) + 1;
        for (Plan.Step step : generated.getSteps()) {
            step.setId(nextId++);
            step.setStatus("pending");
            merged.add(step);
        }
        Plan replanned = Plan.builder()
                .taskDescription(current.getTaskDescription())
                .steps(merged)
                .totalSteps(merged.size())
                .build();
        log.info("[PlanActAgent] 重规划完成: reason={}, oldSteps={}, newSteps={}",
                reason, current.getSteps().size(), replanned.getSteps().size());
        return replanned;
    }

    boolean evaluateStep(Plan plan, Plan.Step step, String output) {
        String prompt = "你是计划执行检查点。只返回JSON：{\"meetsGoal\":true,\"reason\":\"...\"}。\n"
                + "任务：" + plan.getTaskDescription() + "\n"
                + "步骤：" + step.getDescription() + "\n"
                + "预期：" + step.getExpectedOutput() + "\n"
                + "实际：" + (output == null || output.isBlank() ? "[无输出]" : output);
        String json = callModel(prompt);
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(extractJson(json));
            return root.has("meetsGoal") && root.get("meetsGoal").asBoolean(false);
        } catch (Exception e) {
            log.warn("[PlanActAgent] 检查点评估解析失败，按不达标处理: {}", e.getMessage());
            return false;
        }
    }

    void reflectAndPersist(String sessionId, Plan plan, String finalAnswer) {
        String reflection = callModel(reflectionPrompt(plan, finalAnswer));
        if (metrics != null) metrics.recordPlanReflection();
        if (externalNotes != null) {
            NotesDocument doc = externalNotes.load(sessionId);
            doc.getNotes().add(NoteItem.builder()
                    .id("review-" + System.currentTimeMillis())
                    .content("任务复盘：" + plan.getTaskDescription() + "\n" + reflection)
                    .category(NoteCategory.DECISION)
                    .createdAt(Instant.now())
                    .build());
            externalNotes.persist(sessionId, doc);
        }
        if (backgroundReviewer != null) {
            backgroundReviewer.submit(sessionId, plan.getTaskDescription(),
                    finalAnswer + "\n\n自评：" + reflection,
                    review -> persistReviewerNote(sessionId, plan.getTaskDescription(), review));
        }
    }

    private void persistReviewerNote(String sessionId, String task, String review) {
        if (externalNotes == null || review == null || review.isBlank()) return;
        NotesDocument doc = externalNotes.load(sessionId);
        doc.getNotes().add(NoteItem.builder()
                .id("background-review-" + System.currentTimeMillis())
                .content("后台复盘：" + task + "\n" + review)
                .category(NoteCategory.DECISION)
                .createdAt(Instant.now())
                .build());
        externalNotes.persist(sessionId, doc);
    }

    private String replanPrompt(Plan current, Plan.Step failedStep, List<StepResult> results, String reason) {
        StringBuilder prompt = new StringBuilder("你是重规划助手。原计划失败，请只返回JSON。\n")
                .append("格式: {\"taskDescription\":\"...\",\"steps\":[")
                .append("{\"description\":\"...\",\"expectedOutput\":\"...\"}]}\n\n")
                .append("失败原因：").append(reason).append('\n')
                .append("失败步骤：").append(failedStep.getDescription()).append('\n')
                .append("预期：").append(failedStep.getExpectedOutput()).append('\n')
                .append("已完成结果：\n");
        for (StepResult result : results) {
            prompt.append("- 步骤").append(result.getStepId()).append(": ")
                    .append(result.isSuccess() ? "成功" : "失败").append(", ")
                    .append(result.getOutput()).append('\n');
        }
        prompt.append("\n请生成替代的后续步骤，不要重复已完成步骤。");
        return prompt.toString();
    }

    private String reflectionPrompt(Plan plan, String finalAnswer) {
        return "你是任务复盘助手。请用不超过200字总结：计划是否合理、主要问题、下次改进点。\n"
                + "任务：" + plan.getTaskDescription() + "\n最终答案：" + finalAnswer;
    }

    private String callModel(String prompt) {
        var response = chatModel.call(new Prompt(new UserMessage(prompt)));
        return response.getResult().getOutput().getText();
    }

    private Plan generatePlan(String userMessage, String instruction) {
        String prompt = "你是一个任务规划助手。请将以下任务分解为具体可执行的步骤。严格返回JSON格式。\n"
                + "格式: {\"taskDescription\":\"...\",\"steps\":[{\"description\":\"...\",\"expectedOutput\":\"...\"}]}\n\n"
                + "任务：" + userMessage + "\n上下文：" + (instruction != null ? instruction : "");
        return Plan.parse(extractJson(callModel(prompt)));
    }

    private String synthesize(Plan plan, List<StepResult> results, String instruction) {
        StringBuilder ctx = new StringBuilder("请根据以下步骤的执行结果，生成最终的综合回答。\n\n");
        ctx.append("原始任务：").append(plan.getTaskDescription()).append("\n\n");
        for (var r : results) {
            ctx.append("步骤").append(r.getStepId()).append(": ").append(r.getDescription()).append("\n");
            ctx.append("结果: ").append(r.getOutput()).append("\n\n");
        }
        var response = chatModel.call(new Prompt(new SystemMessage(ctx.toString())));
        return response.getResult().getOutput().getText();
    }

    private boolean isSkipped(Plan.Step step) {
        return "completed".equals(step.getStatus()) || "blocked".equals(step.getStatus())
                || ("failed".equals(step.getStatus()) && step.getRetryCount() >= MAX_STEP_RETRIES);
    }

    private boolean dependencyFailed(Plan plan, Plan.Step step, FlowableEmitter<RuntimeEvent> emitter) {
        for (int depId : step.getDependsOn()) {
            for (Plan.Step other : plan.getSteps()) {
                if (other.getId() == depId && "failed".equals(other.getStatus())) {
                    step.setStatus("blocked");
                    emitter.onNext(RuntimeEvent.text("\n⛔ 步骤" + step.getId()
                            + " 因依赖失败被阻塞: " + step.getDescription() + "\n"));
                    return true;
                }
            }
        }
        return false;
    }

    private void emitPlan(FlowableEmitter<RuntimeEvent> emitter, Plan plan) {
        emitter.onNext(RuntimeEvent.text("📋 执行计划（共" + plan.getTotalSteps() + "步）:\n"));
        for (Plan.Step step : plan.getSteps()) {
            emitter.onNext(RuntimeEvent.text("  " + step.getId() + ". " + step.getDescription() + "\n"));
        }
    }

    private void emitStepStart(FlowableEmitter<RuntimeEvent> emitter, Plan.Step step, Plan plan) {
        emitter.onNext(RuntimeEvent.text("\n▶ 步骤" + step.getId() + "/" + plan.getTotalSteps()
                + (step.getRetryCount() > 0 ? " (重试" + step.getRetryCount() + ")" : "")
                + ": " + step.getDescription() + "\n"));
    }

    private int firstPendingIndex(Plan plan) {
        for (int i = 0; i < plan.getSteps().size(); i++) {
            if (!"completed".equals(plan.getSteps().get(i).getStatus())) {
                return i;
            }
        }
        return plan.getSteps().size();
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        return (s >= 0 && e > s) ? text.substring(s, e + 1) : text;
    }
}
