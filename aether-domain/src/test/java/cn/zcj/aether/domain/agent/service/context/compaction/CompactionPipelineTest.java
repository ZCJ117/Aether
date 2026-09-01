package cn.zcj.aether.domain.agent.service.context.compaction;

import cn.zcj.aether.domain.agent.service.context.TokenBudget;
import cn.zcj.aether.domain.agent.service.context.TokenEstimator;
import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CompactionPipeline 六步压缩管道单测 — 覆盖率盲区补测（P0 2.1）。
 */
class CompactionPipelineTest {

    private CompactionTrigger trigger;
    private SafeCutoffFinder cutoffFinder;
    private ChunkSummarizer summarizer;
    private MessageOffloader offloader;
    private TokenEstimator tokenEstimator;
    private CompactionMetrics metrics;
    private CompactionPipeline pipeline;

    @BeforeEach
    void setUp() {
        trigger = mock(CompactionTrigger.class);
        cutoffFinder = mock(SafeCutoffFinder.class);
        summarizer = mock(ChunkSummarizer.class);
        offloader = mock(MessageOffloader.class);
        tokenEstimator = mock(TokenEstimator.class);
        metrics = mock(CompactionMetrics.class);
        pipeline = new CompactionPipeline(trigger, cutoffFinder, summarizer, offloader,
                tokenEstimator, metrics);
        when(tokenEstimator.estimate(any())).thenReturn(10);
        when(trigger.getTriggerMessages()).thenReturn(100);
        when(trigger.getTriggerTokens()).thenReturn(1000);
        when(trigger.getKeepTokens()).thenReturn(500);
    }

    private List<TurnMessage> fourMessages() {
        return List.of(
                TurnMessage.user("第1轮问题"),
                TurnMessage.assistant("第1轮回答"),
                new TurnMessage("tool_use", "x".repeat(300), null, "read_file", null),
                TurnMessage.assistant("第2轮回答"));
    }

    @Test
    void nullOrEmptyMessagesSkipCompaction() {
        var r1 = pipeline.compactIfNeeded(null, "gpt", "s1", 0);
        assertFalse(r1.compacted());
        assertNull(r1.summary());

        var r2 = pipeline.compactIfNeeded(List.of(), "gpt", "s1", 0);
        assertFalse(r2.compacted());
    }

    @Test
    void triggerNotFiredReturnsOriginal() {
        when(trigger.shouldCompact(anyInt(), anyInt())).thenReturn(false);
        List<TurnMessage> messages = fourMessages();

        var r = pipeline.compactIfNeeded(messages, "gpt", "s1", 0);

        assertFalse(r.compacted());
        assertEquals(messages, r.messages());
        verify(offloader, never()).offload(anyString(), anyList(), anyInt());
    }

    @Test
    void cutoffAtZeroOrBeyondRangeSkipsCompaction() {
        when(trigger.shouldCompact(anyInt(), anyInt())).thenReturn(true);
        when(cutoffFinder.findCutoff(anyList(), anyInt())).thenReturn(0);
        assertFalse(pipeline.compactIfNeeded(fourMessages(), "gpt", "s1", 0).compacted());

        when(cutoffFinder.findCutoff(anyList(), anyInt())).thenReturn(4);
        assertFalse(pipeline.compactIfNeeded(fourMessages(), "gpt", "s1", 0).compacted());
    }

    @Test
    void happyPathCompactsPrefixAndKeepsSuffix() {
        when(trigger.shouldCompact(anyInt(), anyInt())).thenReturn(true);
        when(cutoffFinder.findCutoff(anyList(), anyInt())).thenReturn(2);
        when(summarizer.summarize(anyList(), anyString(), any()))
                .thenReturn("历史对话摘要");
        List<TurnMessage> messages = fourMessages();

        var r = pipeline.compactIfNeeded(messages, "gpt-4o", "sess-1", 3);

        assertTrue(r.compacted());
        assertEquals("历史对话摘要", r.summary());
        assertEquals(3, r.messages().size(), "摘要 user 消息 + 2 条 suffix");
        assertTrue(r.messages().get(0).content()
                .startsWith(cn.zcj.aether.domain.agent.service.context.ContextManager.SUMMARY_PREFIX));
        assertTrue(r.messages().get(0).content().endsWith("历史对话摘要"));
        // suffix 从切点起：tool_use 消息保留原文（截断只作用于 prefix），最后是第2轮回答
        assertEquals(300, r.messages().get(1).content().length());
        assertEquals("第2轮回答", r.messages().get(2).content());
        // 每消息 10 tokens：pre=4 条×10=40，post=(摘要+2 suffix)=3 条×10=30
        assertEquals(40, r.preCompactTokens());
        assertEquals(30, r.postCompactTokens());
        // Step 5: prefix 被泄流
        verify(offloader).offload(eq("sess-1"), anyList(), eq(3));
        // P0(1.5): 指标上报
        verify(metrics).recordCompaction(40, 30);
    }

    @Test
    void longToolArgsTruncatedToTwoHundredChars() {
        when(trigger.shouldCompact(anyInt(), anyInt())).thenReturn(true);
        when(cutoffFinder.findCutoff(anyList(), anyInt())).thenReturn(3);
        when(summarizer.summarize(anyList(), anyString(), any())).thenReturn("摘要");

        var r = pipeline.compactIfNeeded(fourMessages(), "gpt", "s1", 0);

        // prefix 含 tool_use 消息（300 chars）→ 截断到 200 + 标记
        ArgumentCaptor<List<TurnMessage>> offloadCap = ArgumentCaptor.captor();
        verify(offloader).offload(eq("s1"), offloadCap.capture(), eq(0));
        TurnMessage toolMsg = offloadCap.getValue().stream()
                .filter(TurnMessage::isToolUse).findFirst().orElseThrow();
        assertEquals(200 + "... [tool args truncated]".length(), toolMsg.content().length());
        assertTrue(toolMsg.content().endsWith("... [tool args truncated]"));
    }

    @Test
    void summarizerFailureDegradesToErrorPlaceholder() {
        when(trigger.shouldCompact(anyInt(), anyInt())).thenReturn(true);
        when(cutoffFinder.findCutoff(anyList(), anyInt())).thenReturn(1);
        when(summarizer.summarize(anyList(), anyString(), any()))
                .thenThrow(new RuntimeException("LLM 不可用"));

        var r = pipeline.compactIfNeeded(fourMessages(), "gpt", "s1", 0);

        assertTrue(r.compacted());
        assertTrue(r.summary().startsWith("[压缩摘要生成失败: "));
        assertTrue(r.messages().get(0).content().contains("LLM 不可用"));
    }

    @Test
    void tokenBudgetOverloadPassedToSummarizer() {
        when(trigger.shouldCompact(anyInt(), anyInt())).thenReturn(true);
        when(cutoffFinder.findCutoff(anyList(), anyInt())).thenReturn(1);
        TokenBudget budget = mock(TokenBudget.class);

        pipeline.compactIfNeeded(fourMessages(), "gpt", "s1", 0, budget);

        verify(summarizer).summarize(anyList(), eq("gpt"), eq(budget));
    }
}
