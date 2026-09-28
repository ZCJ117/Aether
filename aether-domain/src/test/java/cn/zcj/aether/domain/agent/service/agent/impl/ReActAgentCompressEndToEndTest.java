package cn.zcj.aether.domain.agent.service.agent.impl;

import cn.zcj.aether.domain.agent.service.agent.core.AgentConfig;
import cn.zcj.aether.domain.agent.service.agent.core.CancelToken;
import cn.zcj.aether.domain.agent.service.agent.core.RuntimeContext;
import cn.zcj.aether.domain.agent.service.context.ContextManager;
import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.service.context.compaction.CompactionTrigger;
import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.ModelProvider;
import cn.zcj.aether.domain.agent.service.model.ModelProviderRegistry;
import cn.zcj.aether.domain.agent.service.model.failover.ClassifiedError;
import cn.zcj.aether.domain.agent.service.model.failover.FailoverMetrics;
import cn.zcj.aether.domain.agent.service.model.failover.FailoverReason;
import cn.zcj.aether.domain.agent.service.model.failover.ModelErrorClassifier;
import cn.zcj.aether.domain.agent.service.model.failover.ResilientChatModelExecutor;
import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import cn.zcj.aether.domain.agent.service.tool.ToolExecutor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VUL-04 端到端：装配 → 注册 → 触发 → 真压缩 → <b>重建请求</b> → 重试成功。
 *
 * <p>全程真对象，只有模型（{@link ChatModel}）与错误分类器是替身：
 * 真 {@code ReActAgent.execute} → 真 {@code wireResilientExecutor} + {@code wirePromptRebuilder}
 * → 真 {@link ResilientChatModelExecutor} 恢复循环 → 真 {@link ContextManager} 两级窗口压缩
 * → 真 {@link ModelInvoker}（真请求拼装、真真流式路径，{@code aether.model.invoker.true-streaming}
 * 生产默认 true）→ 真 {@link FailoverMetrics} 打点。</p>
 *
 * <p><b>本用例的核断言</b>：第 2 次实际发出的请求必须是一个<b>更短的新请求</b>。
 * 若只压缩 {@code AgentState} 而不重建请求（改造前形态），重试复用的是同一个
 * {@link Prompt} 对象，长度不变、必然再次溢出 —— 该断言正是这条边界的回归锁。</p>
 */
class ReActAgentCompressEndToEndTest {

    private static final String SESSION = "e2e-session";

    @Test
    void overflowRecoveryShrinksRetriedRequestAndSucceeds() throws Exception {
        // ── 替身：模型（首次溢出，之后成功）与分类器 ──
        List<Prompt> requests = new ArrayList<>();
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.stream(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            requests.add(p);
            return requests.size() == 1
                    ? Flux.error(new RuntimeException("maximum context length exceeded"))
                    : Flux.just(ok());
        });

        ModelErrorClassifier classifier = mock(ModelErrorClassifier.class);
        when(classifier.classify(any(), any(), any())).thenReturn(
                ClassifiedError.of(FailoverReason.CONTEXT_OVERFLOW, null,
                        "anthropic", "claude-sonnet", "maximum context length exceeded"));

        ModelProvider provider = mock(ModelProvider.class);
        when(provider.providerName()).thenReturn("anthropic");
        when(provider.createChatModel(any())).thenReturn(delegate);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ResilientChatModelExecutor executor = new ResilientChatModelExecutor(
                delegate,
                ModelConfig.builder().modelId("claude-sonnet").apiKey("key1").build(),
                provider, mock(ModelProviderRegistry.class), classifier, List.of());
        executor.setFailoverMetrics(new FailoverMetrics(meterRegistry));
        // 不替换退避等待器：CONTEXT_COMPRESSION 分支不经过 backoffWaiter，本场景无真实 sleep

        ReActAgent agent = new ReActAgent(
                AgentConfig.builder().name("e2e-agent").instruction("测试指令")
                        .cancelToken(new CancelToken()).build(),
                executor, new ModelInvoker(), mock(ToolExecutor.class), realContextManager(),
                null, null, null, null, null, null);

        // ── 预置超阈值消息：5 条大 + 15 条中 = 20 条 ──
        agent.getState().messagesMutable().addAll(seedMessages());
        int before = agent.getState().messagesMutable().size();

        List<RuntimeEvent> events = agent
                .execute(new RuntimeContext("user1", SESSION, null, null, null, null, null))
                .toList().blockingGet();

        // ── 1. 确实发生了 «溢出 → 压缩 → 重试» 两跳 ──
        assertEquals(2, requests.size(), "应恰好是首次请求 + 一次压缩后的重试请求");

        // ── 2. 核断言：重试发出的是一个更短的新请求（本次修复的全部意义） ──
        Prompt first = requests.get(0);
        Prompt retry = requests.get(1);
        assertNotSame(first, retry, "压缩改变了上下文时必须重建请求，而不是复用原 Prompt");
        assertTrue(requestChars(retry) < requestChars(first),
                "重试请求必须真的变短：首次 " + requestChars(first)
                        + " 字符 → 重试 " + requestChars(retry) + " 字符");

        // ── 3. 压缩真实作用于 AgentState ──
        assertTrue(agent.getState().messagesMutable().size() < before,
                "恢复分支必须真的缩短 AgentState（压缩前 " + before + " 条，压缩后 "
                        + agent.getState().messagesMutable().size() + " 条）");

        // ── 4. 装配生效：回调缺失时才会出现 noop 打点，此处必须缺席 ──
        assertNull(meterRegistry.find(FailoverMetrics.COMPRESS_METRIC).tag("result", "noop").counter(),
                "生产装配点已注入回调，不应出现「无压缩回调」的 noop 打点");
        assertEquals(1.0, compressCount(meterRegistry, "success"),
                "压缩应真实生效并被记为 success");

        // ── 5. 端到端成功：压缩后重试成功，本轮以 done 收场而非报错 ──
        assertTrue(events.stream().anyMatch(e -> e.getType() == RuntimeEvent.EventType.done),
                "压缩后重试应成功，本轮正常结束");
        assertTrue(events.stream().noneMatch(e -> e.getType() == RuntimeEvent.EventType.error),
                "不应再出现「配额耗尽」的 error 事件");
    }

    // ── helpers ──

    private static ChatResponse ok() {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .build();
    }

    /** 请求体量 = 全部消息文本长度之和（system 指令 + 消息）。 */
    private static int requestChars(Prompt prompt) {
        return prompt.getInstructions().stream()
                .mapToInt(m -> m.getText() == null ? 0 : m.getText().length())
                .sum();
    }

    /** 真 ContextManager：真 TokenEstimator（替身掉窗口与系数），消息数下限收紧到 10 以便恢复阶段仍可压缩。 */
    private static ContextManager realContextManager() throws Exception {
        TokenEstimator estimator = mock(TokenEstimator.class);
        when(estimator.estimate(any())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            return text == null ? 0 : (int) Math.ceil(text.length() / 3.5);
        });
        when(estimator.getContextWindow(any())).thenReturn(30_000);

        CompactionTrigger trigger = new CompactionTrigger();
        setField(trigger, "minMessagesToCompact", 10);

        ContextManager cm = new ContextManager(null, null);
        setField(cm, "tokenEstimator", estimator);
        setField(cm, "compactionTrigger", trigger);
        return cm;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static List<TurnMessage> seedMessages() {
        List<TurnMessage> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            messages.add(TurnMessage.user("大消息 " + i + " " + "x".repeat(6000)));
        }
        for (int i = 0; i < 15; i++) {
            messages.add(TurnMessage.user("中消息 " + i + " " + "y".repeat(2200)));
        }
        return messages;
    }

    private static double compressCount(SimpleMeterRegistry registry, String result) {
        Counter c = registry.find(FailoverMetrics.COMPRESS_METRIC).tag("result", result).counter();
        assertTrue(c != null, "指标 " + FailoverMetrics.COMPRESS_METRIC
                + "{result=" + result + "} 未被注册 —— 恢复分支没有真正执行压缩");
        return c.count();
    }
}
