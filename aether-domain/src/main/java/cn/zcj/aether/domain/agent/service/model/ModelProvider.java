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
     * 构建带显式 HTTP 超时的 {@link OpenAiApi}。
     *
     * <p>关键：模型服务端不响应时，底层同步阻塞的 HTTP 调用会无限挂起，
     * 前端表现为"思考中"卡死（Reactor 的 block(timeout) 无法中断同线程阻塞调用）。
     * 所有 OpenAiApi 构造（含带工具的路径）必须走此方法，确保有连接/读取超时兜底。</p>
     */
    default OpenAiApi buildOpenAiApi(ModelConfig config) {
        // 两条 HTTP 通道都必须配置显式超时：
        // 1. RestClient —— OpenAiChatModel.call() 非流式路径（ResilientChatModelExecutor 包装）
        // 2. WebClient —— OpenAiChatModel.stream() 流式路径（ReActAgent 经 ModelInvoker 实际使用）
        //    流式路径此前无任何超时：模型服务端不响应时无限挂起 → 前端"思考中"永久卡死。
        SimpleClientHttpRequestFactory httpFactory = new SimpleClientHttpRequestFactory();
        httpFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        httpFactory.setReadTimeout(READ_TIMEOUT_MS);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(httpFactory);

        HttpClient nettyHttpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(Duration.ofMillis(READ_TIMEOUT_MS));
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
