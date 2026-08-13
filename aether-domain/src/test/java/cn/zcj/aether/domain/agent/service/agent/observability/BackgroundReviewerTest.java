package cn.zcj.aether.domain.agent.service.agent.observability;

import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
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
}
