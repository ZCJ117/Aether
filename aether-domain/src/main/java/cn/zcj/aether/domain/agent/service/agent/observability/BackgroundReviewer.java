package cn.zcj.aether.domain.agent.service.agent.observability;

import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
import cn.zcj.aether.domain.agent.service.executor.GraphFlowState;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台图执行质量评审 — 对齐 hermes background_review.py（默认关闭，配置启用）。
 * <p>图执行结束后异步对最终输出做模型评审，回写 GraphExecutionRecorder（一条 __review 节点事件）
 * 并发布 AgentEventPublisher 评审事件。best-effort：失败只记 debug log。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aether.graph.background-review.enabled",
        havingValue = "true", matchIfMissing = false)
public class BackgroundReviewer {

    private final GraphExecutionRecorder recorder;
    private final AgentEventPublisher publisher;
    private final ModelInvoker modelInvoker;
    private final ChatModel chatModel;
    private final String modelRef;
    private final String systemPrompt;
    private final ExecutorService executor;

    @Autowired
    public BackgroundReviewer(GraphExecutionRecorder recorder,
                              AgentEventPublisher publisher,
                              ModelInvoker modelInvoker,
                              ChatModel chatModel,
                              @Value("${aether.graph.background-review.model-ref:gpt-4o}") String modelRef,
                              @Value("${aether.graph.background-review.system-prompt:你是资深评审。请对给定 Agent 执行结果做质量评审，200 字内。}") String systemPrompt) {
        this.recorder = recorder;
        this.publisher = publisher;
        this.modelInvoker = modelInvoker;
        this.chatModel = chatModel;
        this.modelRef = modelRef;
        this.systemPrompt = systemPrompt;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "background-review");
            t.setDaemon(true);
            return t;
        });
    }

    /** 异步提交图执行结果做后台评审。best-effort。 */
    public void submit(String graphExecutionId, String goal, String finalOutput) {
        if (graphExecutionId == null || finalOutput == null || finalOutput.isBlank()) {
            return;
        }
        if (executor.isShutdown()) {
            return;
        }
        executor.submit(() -> {
            try {
                String review = review(goal, finalOutput);
                recorder.recordNodeEvent(graphExecutionId, "__review", "background-review",
                        GraphFlowState.NodeStatus.COMPLETED, Instant.now(), Instant.now(), 0, null);
                publisher.publishBackgroundReview(graphExecutionId, null, goal, review);
                log.info("BackgroundReviewer: graphExecutionId={} 评审完成: {}",
                        graphExecutionId, truncate(review, 120));
            } catch (Exception e) {
                log.debug("BackgroundReviewer: 评审失败 graphExecutionId={}", graphExecutionId, e);
            }
        });
    }

    /** 对最终输出做模型评审；返回评审文本。goal 为 null/blank 时仅用 finalOutput。 */
    String review(String goal, String finalOutput) {
        String userContent = (goal == null || goal.isBlank())
                ? finalOutput
                : "任务目标:\n" + goal + "\n\n执行结果:\n" + finalOutput;
        ModelInvoker.ModelCallResult r = modelInvoker.callWithStream(chatModel,
                List.of(new UserMessage(userContent)), systemPrompt, modelRef);
        return r.hasError() ? "[评审失败: " + r.getError() + "]"
                : (r.getFullText() == null ? "" : r.getFullText());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
