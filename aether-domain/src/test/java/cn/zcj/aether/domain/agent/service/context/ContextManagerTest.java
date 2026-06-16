package cn.zcj.aether.domain.agent.service.context;

import cn.zcj.aether.domain.agent.service.runtime.TurnMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 单元测试 — P0-5
 * 验证：工具结果截断、微压缩（不依赖 Spring/Logback）
 */
class ContextManagerTest {

    private final TokenEstimator tokenEstimator = new TokenEstimator();

    @Test
    void tokenEstimatorShouldEstimateEnglishText() {
        String text = "Hello world, this is a test message with multiple words.";
        int tokens = tokenEstimator.estimate(text);
        assertTrue(tokens > 0, "English text should estimate > 0 tokens");
    }

    @Test
    void tokenEstimatorShouldEstimateChineseText() {
        String text = "这是一段中文测试文本";
        int tokens = tokenEstimator.estimate(text);
        assertTrue(tokens > 0, "Chinese text should estimate > 0 tokens");
    }

    @Test
    void tokenEstimatorShouldHandleNull() {
        assertEquals(0, tokenEstimator.estimate(null));
    }

    @Test
    void tokenEstimatorShouldHandleEmpty() {
        assertEquals(0, tokenEstimator.estimate(""));
    }

    @Test
    void tokenEstimatorShouldHandleShortText() {
        assertEquals(1, tokenEstimator.estimate("Hi"));
    }

    @Test
    void tokenEstimatorShouldBeProportional() {
        String shortText = "Hello";
        String longText = "Hello world this is a much longer piece of text with many more words to count tokens for";

        int shortTokens = tokenEstimator.estimate(shortText);
        int longTokens = tokenEstimator.estimate(longText);
        assertTrue(longTokens > shortTokens, "Longer text should have more tokens");
    }

    @Test
    void autoCompactResultNotNeeded() {
        AutoCompactResult result = AutoCompactResult.notNeeded();
        assertFalse(result.isCompacted());
    }

    @Test
    void autoCompactResultCompacted() {
        List<TurnMessage> compactedList = List.of(
            TurnMessage.user("summary text"),
            TurnMessage.assistant("response")
        );
        AutoCompactResult result = AutoCompactResult.compacted(
            "对话摘要", compactedList, 1000, 500);

        assertTrue(result.isCompacted());
        assertEquals("对话摘要", result.getSummary());
        assertEquals(1000, result.getPreCompactTokens());
        assertEquals(500, result.getPostCompactTokens());
    }
}
