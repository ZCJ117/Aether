package cn.bugstack.ai.test.api.tool.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

/**
 * Spring AI Tool - 接入 DeepSeek + 百度搜索 MCP
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/14 09:51
 */
@Slf4j
public class SpringAiToolTest {

    public static void main(String[] args) {
        // ⚠️ 安全规范：永远不要把真实的 API Key 写在代码里！
        // 请在您的 IDEA 运行配置中添加环境变量：
        // DEEPSEEK_API_KEY = sk-xxxx (您重置后的新Key)
        // BAIDU_MCP_API_KEY = bce-v3/ALTAK-xxxx (您重置后的新Key)
        String deepSeekApiKey = "***REMOVED-CREDENTIAL***";
        String baiduMcpApiKey = "***REMOVED-CREDENTIAL***";

        if (deepSeekApiKey == null || deepSeekApiKey == null) {
            log.error("运行失败：请先在系统环境变量或 IDEA 启动配置中设置 DEEPSEEK_API_KEY 和 BAIDU_MCP_API_KEY");
            return;
        }

        // 1. 构建 DeepSeek 的 API 客户端 (DeepSeek 兼容 OpenAI 协议)
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl("https://api.deepseek.com")         // DeepSeek 官方 base_url
                .apiKey(deepSeekApiKey)                      // 从环境变量读取
                .completionsPath("/chat/completions")        // DeepSeek 对话路径
                .embeddingsPath("/v1/embeddings")            // DeepSeek 向量路径
                .build();

        // 2. 构建 ChatModel，并注入百度搜索 MCP 工具
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-chat")              // 使用 deepseek-chat 模型
                        .toolCallbacks(SyncMcpToolCallbackProvider.builder()
                                .mcpClients(sseMcpClient(baiduMcpApiKey)) // 注入 MCP 工具
                                .build()
                                .getToolCallbacks())
                        .build())
                .build();

        // 3. 发起测试调用
        String call = chatModel.call("你有哪些工具能力");
        log.info("测试结果:{}", call);
    }

    /**
     * 初始化百度搜索 MCP 客户端
     */
    public static McpSyncClient sseMcpClient(String apiKey) {
        // 将环境变量中的 key 拼接到 SSE 端点路径中
        String sseEndpoint = "/v2/ai_search/mcp/sse?api_key=" + apiKey;

        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport.builder("http://appbuilder.baidu.com")
                .sseEndpoint(sseEndpoint)
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport)
                .requestTimeout(Duration.ofMinutes(360))
                .build();

        var init_sse = mcpSyncClient.initialize();
        log.info("Tool SSE MCP Initialized {}", init_sse);

        return mcpSyncClient;
    }

    @Test
    public void test_url() throws MalformedURLException {
        String fullUrl = "http://appbuilder.baidu.com/v2/ai_search/mcp/sse?api_key=***REMOVED-CREDENTIAL***";
        fullUrl = "http://127.0.0.1:9999/sse?apiKey=xxxx";

        URL url = new URL(fullUrl);
        String protocol = url.getProtocol();
        String host = url.getHost();
        int port = url.getPort();

        String baseUrl  = port == -1 ? protocol + "://" + host : protocol + "://" + host + ":" + port;
        String endpoint = "";

        int index = fullUrl.indexOf(baseUrl);
        if (index != -1) {
            endpoint = fullUrl.substring(index + baseUrl.length());
        }

        log.info("baseUrl:{}", baseUrl);
        log.info("endpoint:{}", endpoint);
    }
}
