package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.Plan;
import cn.zcj.aether.domain.agent.service.agent.core.StepResult;
import cn.zcj.aether.domain.agent.service.agent.observability.AgentMetrics;
import cn.zcj.aether.domain.agent.service.agent.observability.BackgroundReviewer;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.curation.CurationPipeline;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.notes.ExternalNotes;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanActAgentReplanTest {

    @Mock private ChatModel chatModel;
    @Mock private ModelInvoker modelInvoker;
    @Mock private ToolExecutor toolExecutor;
    @Mock private ContextManager contextManager;
    @Mock private AgentEventPublisher eventPublisher;
    @Mock private ExternalNotes externalNotes;
    @Mock private BackgroundReviewer backgroundReviewer;
    @Mock private AgentMetrics metrics;

    private PlanActAgent agent;

    @BeforeEach
    void setUp() {
        AgentConfig config = AgentConfig.builder()
                .name("planner").instruction("instructions").agentType("plan_act").build();
        agent = new PlanActAgent(config, chatModel, modelInvoker, toolExecutor, contextManager,
                eventPublisher, null, null, mock(CurationPipeline.class), externalNotes,
                backgroundReviewer, metrics);
    }

    @Test
    void replanMergesCompletedStepsWithGeneratedStepsAndRecordsMetric() {
        Plan current = Plan.builder().taskDescription("original")
                .steps(List.of(
                        Plan.Step.builder().id(1).description("done").status("completed").build(),
                        Plan.Step.builder().id(2).description("bad").status("failed").build()))
                .totalSteps(2).build();
        ChatResponse chatResponse = response(
                "{\"taskDescription\":\"original\",\"steps\":["
                        + "{\"description\":\"check service\",\"expectedOutput\":\"status\"},"
                        + "{\"description\":\"retry write\",\"expectedOutput\":\"saved\"}]}");
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        Plan replanned = agent.replan(current, current.getSteps().get(1),
                List.of(StepResult.builder().stepId(1).output("a").success(true).build()),
                "检查点评估不达标");

        assertEquals(3, replanned.getSteps().size());
        assertEquals("done", replanned.getSteps().get(0).getDescription());
        assertEquals("check service", replanned.getSteps().get(1).getDescription());
        assertEquals(3, replanned.getSteps().get(1).getId());
        assertEquals("pending", replanned.getSteps().get(1).getStatus());
        verify(metrics).recordPlanReplan();
    }

    @Test
    void replanFailureKeepsCurrentPlanForRetry() {
        Plan current = Plan.builder().taskDescription("original")
                .steps(List.of(Plan.Step.builder().id(1).description("bad").status("failed").build()))
                .totalSteps(1).build();
        ChatResponse chatResponse = response("not json");
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        Plan replanned = agent.replan(current, current.getSteps().get(0), List.of(), "连续工具失败");

        assertEquals(current, replanned);
        verify(metrics).recordPlanReplan();
    }

    @Test
    void checkpointEvaluationMissTriggersReplanPath() {
        ChatResponse chatResponse = response("{\"meetsGoal\":false,\"reason\":\"missing output\"}");
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        Plan plan = Plan.builder().taskDescription("task")
                .steps(List.of(Plan.Step.builder().id(1).description("step")
                        .expectedOutput("expected").status("running").build()))
                .totalSteps(1).build();

        assertFalse(agent.evaluateStep(plan, plan.getSteps().get(0), "partial"));
    }

    @Test
    void unfinishedPlanStepsAreNotCountedAsCompleted() {
        Plan plan = Plan.builder().taskDescription("task")
                .steps(List.of(
                        Plan.Step.builder().id(1).description("done").status("completed").build(),
                        Plan.Step.builder().id(2).description("blocked").status("blocked").build()))
                .totalSteps(2).build();

        assertTrue(agent.hasUnfinishedSteps(plan));

        plan.getSteps().get(1).setStatus("completed");
        assertFalse(agent.hasUnfinishedSteps(plan));
    }

    @Test
    void reflectionPersistsNoteAndSubmitsReviewerWithSink() {
        ChatResponse chatResponse = response("计划合理，但需要更早校验依赖。");
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        ExternalNotes.NotesDocument document = new ExternalNotes.NotesDocument();
        when(externalNotes.load("session-1")).thenReturn(document);

        Plan plan = Plan.builder().taskDescription("task")
                .steps(List.of(Plan.Step.builder().id(1).description("step").status("completed").build()))
                .totalSteps(1).build();
        agent.reflectAndPersist("session-1", plan, "final answer");

        ArgumentCaptor<BackgroundReviewer.ReviewSink> sinkCaptor =
                ArgumentCaptor.forClass(BackgroundReviewer.ReviewSink.class);
        verify(backgroundReviewer).submit(eq("session-1"), eq("task"),
                contains("final answer"), sinkCaptor.capture());
        verify(metrics).recordPlanReflection();
        assertEquals(1, document.getNotes().size());

        sinkCaptor.getValue().accept("后台补充：工具失败率偏高。");
        assertEquals(2, document.getNotes().size());
    }

    private ChatResponse response(String text) {
        ChatResponse response = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage message = mock(AssistantMessage.class);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(message);
        when(message.getText()).thenReturn(text);
        return response;
    }
}
