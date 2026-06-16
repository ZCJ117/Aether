package cn.zcj.aether.domain.agent.service.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelInvoker 单元测试 — P0-5
 * 验证：错误分类、重试判断逻辑
 */
class ModelInvokerTest {

    private final ModelInvoker invoker = new ModelInvoker();

    @Test
    void shouldClassifyConnectionResetAsRetryable() {
        assertTrue(invoker.isRetryable(new java.net.SocketException("Connection reset")));
    }

    @Test
    void shouldClassifyTimeoutAsRetryable() {
        assertTrue(invoker.isRetryable(new java.util.concurrent.TimeoutException("timeout")));
    }

    @Test
    void shouldClassify503AsRetryable() {
        assertTrue(invoker.isRetryable(new RuntimeException("HTTP 503 Service Unavailable")));
    }

    @Test
    void shouldClassify429AsRetryable() {
        assertTrue(invoker.isRetryable(new RuntimeException("HTTP 429 Too Many Requests")));
    }

    @Test
    void shouldNotRetryOn401() {
        assertFalse(invoker.isRetryable(new RuntimeException("HTTP 401 Unauthorized")));
    }

    @Test
    void shouldNotRetryOn403() {
        assertFalse(invoker.isRetryable(new RuntimeException("HTTP 403 Forbidden")));
    }

    @Test
    void shouldNotRetryOn404() {
        assertFalse(invoker.isRetryable(new RuntimeException("HTTP 404 Not Found")));
    }

    @Test
    void shouldParseValidJsonArguments() {
        String json = "{\"query\":\"weather\",\"lang\":\"zh\"}";
        var result = invoker.parseArguments(json);
        assertEquals("weather", result.get("query"));
        assertEquals("zh", result.get("lang"));
    }

    @Test
    void shouldReturnEmptyMapForNullArguments() {
        var result = invoker.parseArguments(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyMapForBlankArguments() {
        var result = invoker.parseArguments("");
        assertTrue(result.isEmpty());
    }
}
