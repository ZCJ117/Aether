package cn.zcj.aether.domain.agent.service.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ModelInvoker 单元测试 — P0-2 重试收口后
 *
 * 验证：
 * 1. 同步路径是单次调用：持续 5xx 时 stream() 只调一次、返回错误结果、无退避 sleep；
 * 2. 总超时兜底：stream 永不完成时按 call-timeout-ms 返回错误结果；
 * 3. 成功路径解析（文本/工具调用）不变。
 */
class ModelInvokerTest {

    private final ModelInvoker invoker = new ModelInvoker();

    private static List<Message> messages() {
        return List.of(new UserMessage("hi"));
    }

    @Test
    void shouldCallStreamExactlyOnceOnPersistentError() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("HTTP 503 Service Unavailable")));

        long start = System.currentTimeMillis();
        ModelInvoker.ModelCallResult result =
                invoker.callWithStream(chatModel, messages(), "sys", "test-model");
        long elapsed = System.currentTimeMillis() - start;

        // P0-2 核心：无外层重试循环，stream() 只调一次
        verify(chatModel, times(1)).stream(any(Prompt.class));
        assertTrue(result.hasError(), "持续 5xx 应返回错误结果");
        assertTrue(elapsed < 1000, "不应有 Thread.sleep 退避，耗时: " + elapsed + "ms");
    }

    @Test
    void shouldReturnErrorOnTotalTimeout() {
        ModelInvoker invoker = new ModelInvoker();
        ReflectionTestUtils.setField(invoker, "callTimeoutMs", 100L); // 缩短总超时加速测试
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.never()); // 永不完成

        long start = System.currentTimeMillis();
        ModelInvoker.ModelCallResult result =
                invoker.callWithStream(chatModel, messages(), "sys", "test-model");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.hasError(), "总超时应返回错误结果");
        assertTrue(elapsed < 5000, "应受 call-timeout-ms 约束而非挂死，耗时: " + elapsed + "ms");
        verify(chatModel, times(1)).stream(any(Prompt.class));
    }

    @Test
    void shouldParseTextAndToolCallsOnSuccess() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse textResp = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("你好"))))
                .build();
        ChatResponse toolResp = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("", Map.of(),
                        List.of(new AssistantMessage.ToolCall("call-1", "function",
                                "get_weather", "{\"city\":\"北京\"}"))))))
                .build();
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(textResp, toolResp));

        ModelInvoker.ModelCallResult result =
                invoker.callWithStream(chatModel, messages(), "sys", "test-model");

        assertFalse(result.hasError());
        assertEquals("你好", result.getFullText());
        assertEquals(1, result.getToolCalls().size());
        assertEquals("get_weather", result.getToolCalls().get(0).getName());
        assertEquals("北京", result.getToolCalls().get(0).getInput().get("city"));
        verify(chatModel, times(1)).stream(any(Prompt.class));
    }

    @Test
    void shouldReturnErrorResultWhenExceptionHasNoMessage() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException())); // getMessage() == null

        ModelInvoker.ModelCallResult result =
                invoker.callWithStream(chatModel, messages(), "sys", "test-model");

        assertTrue(result.hasError(), "异常无 message 时 error 字段不得为 null（否则 hasError() 误判成功）");
        assertNotNull(result.getError());
        verify(chatModel, times(1)).stream(any(Prompt.class));
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

    // ============== O5: 真流式路径 callWithStreamingAsync ==============

    @Test
    void shouldPushDeltasIncrementallyAndSummarize() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                textResponse("你"),
                textResponse("好"),
                toolCallResponse("call-1", "get_weather", "{\"city\":\"北京\"}")));

        List<cn.zcj.aether.domain.agent.service.runtime.RuntimeEvent> deltas = new java.util.ArrayList<>();
        ModelInvoker.ModelCallResult result = invoker
                .callWithStreamingAsync(chatModel, messages(), "sys", "test-model", deltas::add)
                .block(java.time.Duration.ofSeconds(5));

        assertFalse(result.hasError());
        assertEquals("你好", result.getFullText(), "text chunk 应滚动汇总");
        assertEquals(1, result.getToolCalls().size());
        assertEquals("get_weather", result.getToolCalls().get(0).getName());
        // O5 核心：text chunk 边收边发，deltaSink 逐 chunk 收到
        assertEquals(2, deltas.size(), "两个 text chunk 应各下发一次");
        assertEquals("你", deltas.get(0).getText());
        assertEquals("好", deltas.get(1).getText());
        // 返回结果只含 toolCall 事件（text 已实时下发，避免重复回放）
        assertEquals(1, result.getEvents().size());
        assertEquals("call-1", result.getEvents().get(0).getToolCallId());
    }

    @Test
    void shouldEstimateTokensWhenUsageMissingOnStreaming() {
        ChatModel chatModel = mock(ChatModel.class);
        // 20 字符输出 → outputTokens = 20/4 = 5；输入 sys(3)+hi(2) → inputTokens = 5/4 = 1
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(textResponse("hello world response!")));

        ModelInvoker.ModelCallResult result = invoker
                .callWithStreamingAsync(chatModel, messages(), "sys", "test-model", null)
                .block(java.time.Duration.ofSeconds(5));

        assertFalse(result.hasError());
        assertEquals(1, result.getInputTokens(), "usage 缺失时应按 ≈4 chars/token 估算兜底（成本熔断不被绕过）");
        assertEquals(5, result.getOutputTokens());
    }

    @Test
    void shouldPropagateErrorOnStreamingFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("HTTP 503 Service Unavailable")));

        assertThrows(RuntimeException.class, () -> invoker
                .callWithStreamingAsync(chatModel, messages(), "sys", "test-model", null)
                .block(java.time.Duration.ofSeconds(5)));
        verify(chatModel, times(1)).stream(any(Prompt.class));
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    private static ChatResponse toolCallResponse(String id, String name, String arguments) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("", Map.of(),
                        List.of(new AssistantMessage.ToolCall(id, "function", name, arguments))))))
                .build();
    }
}
