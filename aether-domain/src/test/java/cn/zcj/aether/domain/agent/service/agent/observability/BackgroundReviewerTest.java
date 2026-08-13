package cn.zcj.aether.domain.agent.service.agent.observability;

import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.executor.GraphFlowState;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import java.util.List;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.prompt.Prompt;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.test.util.ReflectionTestUtils;

class BackgroundReviewerTest {

    @Test
    void reviewProducesReviewTextFromModelCall() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        ModelInvoker.ModelCallResult result = ModelInvoker.ModelCallResult.builder()
                .fullText("评审通过").build();
        when(modelInvoker.callWithStreamAsync(any(), any(), any(), any())).thenReturn(reactor.core.publisher.Mono.just(result));

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。");
        assertEquals("评审通过", reviewer.review("目标A", "final output"));
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
        verify(modelInvoker, never()).callWithStreamAsync(any(), any(), any(), any());
    }

    @Test
    void submitAsyncRunsReviewRecordsEventAndPublishes() {
        GraphExecutionRecorder recorder = new GraphExecutionRecorder(200);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        ModelInvoker.ModelCallResult result = ModelInvoker.ModelCallResult.builder()
                .fullText("评审通过").build();
        when(modelInvoker.callWithStreamAsync(any(), any(), any(), any())).thenReturn(reactor.core.publisher.Mono.just(result));

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

    @Test
    void reviewIncludesGoalAndFinalOutputInPrompt() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        when(modelInvoker.callWithStreamAsync(any(), any(), any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(ModelInvoker.ModelCallResult.builder().fullText("评审通过").build()));

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。");

        reviewer.review("写一份报告", "报告正文");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelInvoker).callWithStreamAsync(any(), captor.capture(), any(), any());
        String text = captor.getValue().get(0).getText();
        assertTrue(text.contains("写一份报告"), "提示词应含 goal");
        assertTrue(text.contains("报告正文"), "提示词应含 finalOutput");
    }

    @Test
    void reviewBlankGoalUsesOnlyFinalOutput() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);
        when(modelInvoker.callWithStreamAsync(any(), any(), any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(ModelInvoker.ModelCallResult.builder().fullText("评审通过").build()));

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。");

        reviewer.review("   ", "报告正文");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelInvoker).callWithStreamAsync(any(), captor.capture(), any(), any());
        String text = captor.getValue().get(0).getText();
        assertEquals("报告正文", text, "goal 为 blank 时提示词只应含 finalOutput");
    }

    @Test
    void reviewReturnsErrorPlaceholderOnTimeout() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = new ModelInvoker(); // 真实 ModelInvoker 走真实 block(timeout)
        org.springframework.ai.chat.model.ChatModel chatModel =
                mock(org.springframework.ai.chat.model.ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.never());

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, chatModel,
                "gpt-4o", "你是一名评审。", Duration.ofMillis(100), 4);

        long start = System.currentTimeMillis();
        String r = reviewer.review("goal", "output");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(r.startsWith("[评审失败"), "超时应返回错误占位，实际: " + r);
        assertTrue(elapsed < 5000, "超时应快速返回而非挂死，耗时: " + elapsed + "ms");
        reviewer.shutdown();
    }

    @Test
    void executorQueueIsBounded() {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);

        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。", Duration.ofSeconds(30), 4);

        try {
            ExecutorService exec = (ExecutorService) ReflectionTestUtils.getField(reviewer, "executor");
            assertTrue(exec instanceof ThreadPoolExecutor, "executor 应为 ThreadPoolExecutor");
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) exec;
            assertTrue(tpe.getQueue() instanceof ArrayBlockingQueue, "队列必须是有界的 ArrayBlockingQueue");
            assertEquals(4, tpe.getQueue().remainingCapacity() + tpe.getQueue().size(), "队列容量应为 4");
        } finally {
            reviewer.shutdown();
        }
    }

    @Test
    void submitDoesNotBlockWhenQueueFull() throws InterruptedException {
        GraphExecutionRecorder recorder = mock(GraphExecutionRecorder.class);
        AgentEventPublisher publisher = mock(AgentEventPublisher.class);
        ModelInvoker modelInvoker = mock(ModelInvoker.class);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(modelInvoker.callWithStreamAsync(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    started.countDown();
                    release.await();
                    return reactor.core.publisher.Mono.just(
                            ModelInvoker.ModelCallResult.builder().fullText("ok").build());
                });

        // queueCapacity=1：1 running + 1 queued 即满，第 3 个 submit 应被 DiscardPolicy 丢弃而非阻塞调用方
        BackgroundReviewer reviewer = new BackgroundReviewer(
                recorder, publisher, modelInvoker, mock(org.springframework.ai.chat.model.ChatModel.class),
                "gpt-4o", "你是一名评审。", Duration.ofSeconds(30), 1);

        try {
            reviewer.submit("gx-1", "goal", "o1");           // 占用唯一线程（阻塞在 release）
            assertTrue(started.await(5, TimeUnit.SECONDS), "首个任务应已开始执行");
            reviewer.submit("gx-2", "goal", "o2");           // 填满容量为 1 的队列

            long start = System.currentTimeMillis();
            reviewer.submit("gx-3", "goal", "o3");           // 队列已满 → DiscardPolicy 静默丢弃，立即返回
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 2000, "满载时 submit 不应阻塞调用方，耗时: " + elapsed + "ms");
        } finally {
            release.countDown();
            reviewer.shutdown();
        }
    }
}
