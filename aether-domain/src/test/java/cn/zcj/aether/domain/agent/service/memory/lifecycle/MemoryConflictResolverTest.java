package cn.zcj.aether.domain.agent.service.memory.lifecycle;

import cn.zcj.aether.domain.agent.service.memory.MemoryRecord;
import cn.zcj.aether.domain.agent.service.memory.MemoryScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1(4.3): 冲突合并策略测试 —— concat（原行为）/ new-wins / llm（成功与回退）。
 */
class MemoryConflictResolverTest {

    private static MemoryRecord existing() {
        return MemoryRecord.builder()
                .id("mem-1")
                .content("用户的编辑器是 IntelliJ IDEA")
                .scope(new MemoryScope("global", false))
                .importance(0.8f)
                .createdAt(Instant.now())
                .lastAccessedAt(Instant.now())
                .accessCount(3)
                .build();
    }

    @Test
    void concatStrategyKeepsBothAndAveragesImportance() {
        MemoryConflictResolver resolver = new MemoryConflictResolver(null, "concat");
        var outcome = resolver.resolve(existing(), "用户最近主要用 VSCode", 0.4f);
        assertEquals("concat", outcome.strategy());
        assertTrue(outcome.content().contains("IntelliJ IDEA"));
        assertTrue(outcome.content().contains("VSCode"));
        assertEquals(0.6f, outcome.importance(), 1e-6);
    }

    @Test
    void newWinsStrategyReplacesContent() {
        MemoryConflictResolver resolver = new MemoryConflictResolver(null, "new-wins");
        var outcome = resolver.resolve(existing(), "用户最近主要用 VSCode", 0.7f);
        assertEquals("new-wins", outcome.strategy());
        assertEquals("用户最近主要用 VSCode", outcome.content());
        assertEquals(0.7f, outcome.importance(), 1e-6);
    }

    @Test
    void llmStrategyMergesWhenModelDecides() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(chatResponse("{\"action\":\"merge\",\"content\":\"用户的编辑器是 VSCode（原为 IntelliJ IDEA，已迁移）\"}"));
        MemoryConflictResolver resolver = new MemoryConflictResolver(chatModel, "llm");
        var outcome = resolver.resolve(existing(), "用户主要用 VSCode", 0.9f);
        assertEquals("llm-merge", outcome.strategy());
        assertTrue(outcome.content().contains("VSCode"));
        assertEquals(0.9f, outcome.importance(), 1e-6);
    }

    @Test
    void llmStrategyFallsBackToConcatOnModelFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("provider down"));
        MemoryConflictResolver resolver = new MemoryConflictResolver(chatModel, "llm");
        var outcome = resolver.resolve(existing(), "用户主要用 VSCode", 0.9f);
        assertEquals("concat-fallback", outcome.strategy());
        assertTrue(outcome.content().contains("IntelliJ IDEA"), "回退应保留原拼接语义");
    }

    @Test
    void llmStrategyWithoutModelFallsBack() {
        MemoryConflictResolver resolver = new MemoryConflictResolver(null, "llm");
        var outcome = resolver.resolve(existing(), "x", 0.5f);
        assertEquals("concat-fallback", outcome.strategy());
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(text))));
    }
}
