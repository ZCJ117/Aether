package cn.bugstack.ai.test.api.tool.mcp;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * LangChain4j - 接入 DeepSeek + 百度搜索 MCP（SSE）
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/12/14 09:20
 */
@Slf4j
public class LangChain4jToolTest {

    interface Assistant {
        String chat(String message);
    }

    public static void main(String[] args) {
        // --------- 大模型：DeepSeek（兼容 OpenAI 协议）---------
        // DeepSeek 官方 base_url 兼容两种写法（不带 /v1 也可以）：
        //   https://api.deepseek.com 或 https://api.deepseek.com/v1
        // 这里采用 LangChain4j 官方示例中的带 /v1 的写法，更通用。
        String deepSeekBaseUrl = "https://api.deepseek.com/v1";        // 必改：DeepSeek 的 base_url
        String deepSeekApiKey = "***REMOVED-CREDENTIAL***";      // 必改：例如 sk-xxx
        String modelName = "deepseek-chat";                           // DeepSeek 默认对话模型（DeepSeek-V3）

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(deepSeekBaseUrl)
                .apiKey(deepSeekApiKey)
                .modelName(modelName)
                .build();

        // --------- MCP 工具：百度搜索（SSE）---------
        // 注意：百度 MCP SSE 的 api_key 格式为 “Bearer+<AppBuilder API Key>”（中间有加号），
        // 官方/社区示例都是这么写的。
        String baiduAppbuilderApiKey = "***REMOVED-CREDENTIAL***"; // 必改：bce-v3/ALTAK-...

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .tools(sseMcpClient(baiduAppbuilderApiKey))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .build();

        String answer = assistant.chat("你有哪些工具能力");
        log.info("测试结果:{}", answer);
    }

    /**
     * 百度搜索 MCP（SSE）接入示例
     *
     * 文档/说明：
     * - MCP Server URL 示例：http://appbuilder.baidu.com/v2/ai_search/mcp/sse?api_key=Bearer+<AppBuilder API Key>
     * - api_key 需保留 “Bearer+” 与 AppBuilder API Key 中间的加号
     */
    public static McpSyncClient sseMcpClient(String baiduAppbuilderApiKey) {
        // 为了适配 HttpClientSseClientTransport，把完整 SSE 拆成 baseUrl + sseEndpoint：
        // - baseUrl：协议+主机（不带路径）
        // - sseEndpoint：从路径开始的部分（含路径与查询参数）
        String baseUrl = "http://appbuilder.baidu.com";
        // 这里直接把完整路径放在 sseEndpoint 里
        String sseEndpoint = "/v2/ai_search/mcp/sse?api_key=Bearer+" + baiduAppbuilderApiKey;

        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport.builder(baseUrl)
                .sseEndpoint(sseEndpoint)
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport)
                .requestTimeout(Duration.ofMinutes(360))
                .build();
        var init_sse = mcpSyncClient.initialize();
        log.info("Tool SSE MCP Initialized {}", init_sse);

        return mcpSyncClient;
    }
}
