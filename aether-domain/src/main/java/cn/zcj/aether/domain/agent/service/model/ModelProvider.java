package cn.zcj.aether.domain.agent.service.model;

import io.netty.channel.ChannelOption;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

/**
 * 模型提供商 SPI。
 * 灵感来源：AgentScope Model 接口 + CrewAI BaseLLM 层次。
 *
 * 通过 Spring Bean 自动发现：所有实现此接口的 Bean 会被 ModelProviderRegistry 收集。
 */
public interface ModelProvider {

    /** 模型 HTTP 超时（对齐 HttpClientConfig 全局超时：连接 30s / 读取 2min）。 */
    int CONNECT_TIMEOUT_MS = 30_000;
    int READ_TIMEOUT_MS = 120_000;

    /** 提供商名称（如 "openai"、"anthropic"、"dashscope"） */
    String providerName();

    /** 判断是否能处理给定的模型 ID */
    boolean supports(String modelId);

    /** 根据配置创建一个可用的 ChatModel */
    ChatModel createChatModel(ModelConfig config);

    /**
     * O12: 超时钩子。默认常量；Provider 可覆盖为从 aether.model.invoker.* 读取的配置值。
     */
    default int connectTimeoutMs() { return CONNECT_TIMEOUT_MS; }
    default int readTimeoutMs() { return READ_TIMEOUT_MS; }

    /**
     * O12: 可测试接缝 —— 构造带显式超时的 RestClient 请求工厂。
     */
    default SimpleClientHttpRequestFactory httpRequestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    /**
     * 构建带显式 HTTP 超时的 {@link OpenAiApi}（使用 Provider 超时钩子）。
     */
    default OpenAiApi buildOpenAiApi(ModelConfig config) {
        return buildOpenAiApi(config, connectTimeoutMs(), readTimeoutMs());
    }

    /**
     * 构建带显式 HTTP 超时的 {@link OpenAiApi}（参数化）。
     */
    default OpenAiApi buildOpenAiApi(ModelConfig config, int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory httpFactory = httpRequestFactory(connectTimeoutMs, readTimeoutMs);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(httpFactory);

        HttpClient nettyHttpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs));
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(nettyHttpClient));

        return OpenAiApi.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey())
            .completionsPath(StringUtils.isNotBlank(config.getCompletionsPath())
                ? config.getCompletionsPath() : defaultCompletionsPath())
            .embeddingsPath(StringUtils.isNotBlank(config.getEmbeddingsPath())
                ? config.getEmbeddingsPath() : "v1/embeddings")
            .restClientBuilder(restClientBuilder)
            .webClientBuilder(webClientBuilder)
            .build();
    }

    /**
     * 创建带工具回调的 ChatModel。
     * 因为 Spring AI 的 OpenAiChatOptions.toolCallbacks() 必须在构造时设置，
     * 而 createChatModel() 返回通用 ChatModel 接口无法追加工具，
     * 此默认方法封装了"通过 OpenAiApi 重建 OpenAiChatModel 并注入 toolCallbacks"的模式。
     *
     * P0-2：此方法替代了 ChatModelNode 中直接 new OpenAiApi() 的硬编码。
     */
    default ChatModel createChatModelWithTools(ModelConfig config, List<ToolCallback> toolCallbacks) {
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            return createChatModel(config);
        }
        OpenAiApi api = buildOpenAiApi(config);
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(config.getModelId())
                .toolCallbacks(toolCallbacks)
                .build())
            .build();
    }

    /** 该 Provider 默认的 API 路径前缀（用于 YAML 中省略 completionsPath 时的默认值） */
    default String defaultCompletionsPath() { return "v1/chat/completions"; }

    /**
     * P1: Provider 特有的错误分类逻辑。
     *
     * 各 Provider 实现可重写此方法，将 Provider 特有的状态码/错误体格式
     * 翻译为 {@link cn.zcj.aether.domain.agent.service.model.failover.ClassifiedError}。
     * 返回 null 表示"无特定分类"，由默认分类器处理。
     *
     * @param error   原始异常
     * @param modelId 当前模型 ID
     * @return 分类结果，或 null 回退到默认分类器
     */
    default cn.zcj.aether.domain.agent.service.model.failover.ClassifiedError classifyError(
            Throwable error, String modelId) {
        return null;  // 默认：交给 DefaultModelErrorClassifier
    }
}
