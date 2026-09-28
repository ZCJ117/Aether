package cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.impl;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.Duration;

// NOTE 这个类作用是给ChatModelNode构建SSE类型的MCP客户端工具回调，核心逻辑是从配置中获取SSE的基础URI和SSE端点，如果没有明确配置SSE端点，则从基础URI中解析出SSE端点；最后构建MCP客户端并返回工具回调数组

@Slf4j
@Service
public class SSEToolMcpCreateService implements TooMcpCreateService {

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.SSEServerParameters sseConfig = toolMcp.getSse();

        // http://appbuilder.baidu.com/v2/ai_search/mcp/sse?api_key=<BAIDU_API_KEY>

        String originalBaseUri = sseConfig.getBaseUri();  // 基础的

        String baseUri = originalBaseUri;                // 真正截取后的需要的URI，默认是和originalBaseUri一样的，如果sseEndpoint没有配置，那么就从originalBaseUri中解析出真正的baseUri；
        // 如果sseEndpoint有配置，那么就直接使用originalBaseUri作为baseUri

        String sseEndpoint = sseConfig.getSseEndpoint(); // 这个是可选的，如果不配置，就从baseUri中解析；如果配置了，就直接使用配置的

        //NOTE 这里的逻辑是，如果sseEndpoint没有配置，那么就从baseUri中解析出baseUri和sseEndpoint；
        // 如果sseEndpoint有配置，那么就直接使用配置的baseUri和sseEndpoint
        if (StringUtils.isBlank(sseEndpoint)) {
            URL url = new URL(originalBaseUri);

            String protocol = url.getProtocol(); // http or https
            String host = url.getHost();  // 这个是域名或者IP地址
            int port = url.getPort();  // 端口号，如果URL中没有明确指定端口，则返回-1

            // NOTE 这里构建 baseUrl 的时候，如果端口号是-1，说明URL中没有明确指定端口，那么就不添加端口部分；如果端口号不是-1，则添加端口部分
            String baseUrl = port == -1 ? protocol + "://" + host : protocol + "://" + host + ":" + port;

            int index = originalBaseUri.indexOf(baseUrl);
            if (index != -1) {
                sseEndpoint = originalBaseUri.substring(index + baseUrl.length());
            }

            baseUri = baseUrl;
        }

        // NOTE 这里若为空，则默认使用 "/sse" 作为 SSE 端点；如果不为空，则使用配置的 SSE 端点
        sseEndpoint = StringUtils.isBlank(sseEndpoint) ? "/sse" : sseEndpoint;

        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport
                .builder(baseUri)
                .sseEndpoint(sseEndpoint)
                .build();

        McpSyncClient mcpSyncClient = McpClient
                .sync(sseClientTransport)
                .requestTimeout(Duration.ofMillis(sseConfig.getRequestTimeout())).build();
        McpSchema.InitializeResult initialize = mcpSyncClient.initialize();

        log.info("tool sse mcp initialize {}", initialize);

        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpSyncClient).build()
                .getToolCallbacks();
    }

}