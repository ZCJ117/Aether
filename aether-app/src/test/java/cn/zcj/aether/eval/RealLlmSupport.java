package cn.zcj.aether.eval;

import cn.zcj.aether.domain.agent.service.runtime.ModelInvoker;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * P0(4.1) 真实模式支持 —— AETHER_EVAL_MODE=real 且提供 DEEPSEEK_API_KEY 时，
 * tool_selection / multi_step 用例改走真实 OpenAI 兼容模型（DeepSeek），
 * 由模型基于工具描述自主选择（验证提示词/工具描述的真实效果）。
 *
 * 环境变量：DEEPSEEK_API_KEY（必需）、DEEPSEEK_BASE_URL（默认 https://api.deepseek.com）、
 * DEEPSEEK_MODEL（默认 deepseek-chat）。
 */
final class RealLlmSupport {

    private RealLlmSupport() {
    }

    static boolean enabled() {
        return "real".equalsIgnoreCase(System.getenv("AETHER_EVAL_MODE"))
                && System.getenv("DEEPSEEK_API_KEY") != null
                && !System.getenv("DEEPSEEK_API_KEY").isBlank();
    }

    static ModelInvoker newRealInvoker() {
        return new ModelInvoker(); // @Value 字段走默认（trueStreaming=true, 120s 超时）
    }

    static ChatModel buildChatModel(EvalCase c) {
        String baseUrl = env("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        String model = env("DEEPSEEK_MODEL", "deepseek-chat");

        List<FunctionToolCallback> callbacks = EvalEngines.orEmpty(c.tools).stream()
                .map(t -> FunctionToolCallback
                        .builder(t.name, (java.util.function.Function<Map<String, Object>, String>) input ->
                                "工具 " + t.name + " 执行成功，输入=" + String.valueOf(input))
                        .description(t.description)
                        .inputType(Map.class)
                        .inputSchema(t.inputSchema == null || t.inputSchema.isEmpty()
                                ? "{\"type\":\"object\",\"properties\":{}}"
                                : toJson(t.inputSchema))
                        .build())
                .collect(Collectors.toList());

        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .toolCallbacks(callbacks.toArray(new org.springframework.ai.tool.ToolCallback[0]))
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private static String toJson(Map<String, Object> schema) {
        try {
            return EvalEngines.JSON.writeValueAsString(schema);
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }
}
