package cn.zcj.aether.domain.agent.service.retrieval.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1(4.2) 一级：查询改写测试 —— 正常改写 / 异常与超时回退原查询。
 */
class QueryRewriterTest {

    @Test
    void rewritesWithVariants() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse(
                "{\"rewritten\":\"分布式限流 令牌桶 Redis 实现\",\"variants\":[\"分布式限流算法\",\"Redis 令牌桶\"]}"));
        QueryRewriter rewriter = new QueryRewriter(chatModel);

        var result = rewriter.rewrite("帮我看看限流那个怎么弄的", 800, 2);
        assertTrue(result.rewritten());
        assertEquals("分布式限流 令牌桶 Redis 实现", result.rewrittenQuery());
        assertEquals(2, result.variants().size());
    }

    @Test
    void fallsBackToOriginalOnModelFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("llm down"));
        QueryRewriter rewriter = new QueryRewriter(chatModel);

        var result = rewriter.rewrite("原始查询", 800, 2);
        assertFalse(result.rewritten());
        assertEquals("原始查询", result.rewrittenQuery());
    }

    @Test
    void timesOutWithinBudget() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            Thread.sleep(2000);
            return chatResponse("{\"rewritten\":\"late\"}");
        });
        QueryRewriter rewriter = new QueryRewriter(chatModel);

        long start = System.currentTimeMillis();
        var result = rewriter.rewrite("慢查询", 100, 2);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result.rewritten(), "超时应回退原查询");
        assertTrue(elapsed < 1500, "应在预算内返回: " + elapsed + "ms");
    }

    @Test
    void noChatModelIsIdentity() {
        QueryRewriter rewriter = new QueryRewriter(null);
        var result = rewriter.rewrite("原始查询", 800, 2);
        assertFalse(result.rewritten());
        assertEquals("原始查询", result.rewrittenQuery());
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
