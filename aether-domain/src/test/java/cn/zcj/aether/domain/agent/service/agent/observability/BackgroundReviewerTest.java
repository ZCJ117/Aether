package cn.zcj.aether.domain.agent.service.agent.observability;

import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.executor.GraphFlowState;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BackgroundReviewerTest {

    @Test
    void reviewProducesReviewTextFromModelCall() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        ModelInvoker.ModelCallResult result = ModelInvoker.ModelCallResult.builder()
                .fullText("评审通过").build();
        when(modelInvoker.callWithStream(any(), any(), any(), any())).thenReturn(result);

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。");
        assertEquals("评审通过", reviewer.review("final output"));
    }

    @Test
    void submitWithBlankOutputIsIgnored() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。");
        reviewer.submit("gx-1", "goal", "   ");
        verify(modelInvoker, never()).callWithStream(any(), any(), any(), any());
    }

    @Test
    void submitAsyncRunsReviewRecordsEventAndPublishes() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        ModelInvoker.ModelCallResult result = ModelInvoker.ModelCallResult.builder()
                .fullText("评审通过").build();
        when(modelInvoker.callWithStream(any(), any(), any(), any())).thenReturn(result);

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。");

        try {
            String graphExecutionId = recorder.beginExecution("sess-1");
            reviewer.submit(graphExecutionId, "goal", "output");

            verify(publisher, timeout(3000)).publishBackgroundReview(graphExecutionId, null, "goal", "评审通过");

            boolean reviewEvent = recorder.getExecutionTrace(graphExecutionId).stream()
                    .anyMatch(e -> "__review".equals(e.nodeName())
                            && e.status() == GraphFlowState.NodeStatus.COMPLETED);
            assertTrue(reviewEvent, "应录制一条 __review COMPLETED 节点事件");
        } finally {
            reviewer.shutdown();
        }
    }
}
