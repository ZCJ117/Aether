package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.core.*;
import cn.zcj.aether.domain.agent.service.agent.hook.AgentHook;
import cn.zcj.aether.domain.agent.service.agent.middleware.MiddlewareChain;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P1-#4: Plan-Act-Synthesize 三阶段 Agent。
 * 借鉴 MetaGPT PLAN_AND_ACT + AutoGen MagenticOne Orchestrator。
 */
@Slf4j
public class PlanActAgent extends BaseAgent {

    private final ChatModel chatModel;
    private final ModelInvoker modelInvoker;
    private final ToolExecutor toolExecutor;
    private final ContextManager contextManager;
    private final AgentEventPublisher eventPublisher;
    private final CheckpointCollector checkpointCollector;

    public PlanActAgent(AgentConfig config,
                         ChatModel chatModel,
                         ModelInvoker modelInvoker,
                         ToolExecutor toolExecutor,
                         ContextManager contextManager,
                         AgentEventPublisher eventPublisher,
                         CheckpointCollector checkpointCollector) {
        super(config);
        this.chatModel = chatModel;
        this.modelInvoker = modelInvoker;
        this.toolExecutor = toolExecutor;
        this.contextManager = contextManager;
        this.eventPublisher = eventPublisher;
        this.checkpointCollector = checkpointCollector;
    }

    @Override
    public Flowable<RuntimeEvent> execute(RuntimeContext ctx) {
        return Flowable.create(emitter -> {
            try {
                MiddlewareChain chain = new MiddlewareChain(this, ctx);
                AtomicBoolean aborted = new AtomicBoolean(false);
                emitter.setCancellable(() -> aborted.set(true));

                // ====== Phase 1: Plan ======
                log.info("[PlanActAgent] Phase 1: Plan");
                Plan plan = generatePlan(ctx.initialMessage(), config.getInstruction());

                if (plan.getSteps().isEmpty()) {
                    emitter.onNext(RuntimeEvent.error("无法生成执行计划"));
                    emitter.onComplete();
                    return;
                }

                emitter.onNext(RuntimeEvent.text("📋 执行计划（共" + plan.getTotalSteps() + "步）:\n"));
                for (var step : plan.getSteps()) {
                    emitter.onNext(RuntimeEvent.text("  " + step.getId() + ". " + step.getDescription() + "\n"));
                }
                if (aborted.get()) return;

                // ====== Phase 2: Act ======
                log.info("[PlanActAgent] Phase 2: Act");
                List<StepResult> stepResults = new ArrayList<>();

                for (var step : plan.getSteps()) {
                    if (aborted.get()) break;
                    step.setStatus("running");
                    long stepStart = System.currentTimeMillis();

                    emitter.onNext(RuntimeEvent.text("\n▶ 步骤" + step.getId() + "/" + plan.getTotalSteps()
                            + ": " + step.getDescription() + "\n"));

                    AgentConfig stepConfig = AgentConfig.builder()
                            .name(config.getName() + "-step-" + step.getId())
                            .instruction(step.getDescription() + "\n预期产出: " + step.getExpectedOutput())
                            .toolNames(config.getToolNames())
                            .modelRef(config.getModelRef())
                            .agentType("react")
                            .checkpointEnabled(false)
                            .cancelToken(new CancelToken())
                            .build();

                    ReActAgent subAgent = new ReActAgent(stepConfig, chatModel, modelInvoker,
                            toolExecutor, contextManager, eventPublisher, checkpointCollector);

                    RuntimeContext stepCtx = new RuntimeContext(
                            ctx.userId(), ctx.sessionId() + "-s" + step.getId(),
                            null, null,
                            step.getDescription(), Map.of(), null);

                    List<String> outputs = new ArrayList<>();
                    subAgent.execute(stepCtx)
                            .doOnNext(event -> {
                                emitter.onNext(event);
                                if (event.getType() == RuntimeEvent.EventType.textDelta
                                        && event.getText() != null) {
                                    outputs.add(event.getText());
                                }
                            })
                            .doOnComplete(() -> {
                                String output = String.join("", outputs);
                                stepResults.add(StepResult.builder()
                                        .stepId(step.getId()).description(step.getDescription())
                                        .output(output).success(true)
                                        .durationMs(System.currentTimeMillis() - stepStart).build());
                            })
                            .blockingSubscribe();

                    step.setStatus("completed");
                    if (!stepResults.isEmpty()) {
                        step.setResult(stepResults.get(stepResults.size() - 1).getOutput());
                    }
                }

                // ====== Phase 3: Synthesize ======
                log.info("[PlanActAgent] Phase 3: Synthesize");
                String finalAnswer = synthesize(plan, stepResults, config.getInstruction());
                emitter.onNext(RuntimeEvent.text(finalAnswer));
                emitter.onNext(RuntimeEvent.done());
                emitter.onComplete();

            } catch (Exception e) {
                log.error("[PlanActAgent] 执行失败", e);
                emitter.onNext(RuntimeEvent.error(e.getMessage()));
                emitter.onComplete();
            }
        }, BackpressureStrategy.BUFFER);
    }

    private Plan generatePlan(String userMessage, String instruction) {
        String prompt = "你是一个任务规划助手。请将以下任务分解为具体可执行的步骤。严格返回JSON格式。\n"
                + "格式: {\"taskDescription\":\"...\",\"steps\":[{\"description\":\"...\",\"expectedOutput\":\"...\"}]}\n\n"
                + "任务：" + userMessage + "\n上下文：" + (instruction != null ? instruction : "");
        var response = chatModel.call(new Prompt(new UserMessage(prompt)));
        String text = response.getResult().getOutput().getText();
        return Plan.parse(extractJson(text));
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

    private String extractJson(String text) {
        if (text == null) return "{}";
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        return (s >= 0 && e > s) ? text.substring(s, e + 1) : text;
    }
}
