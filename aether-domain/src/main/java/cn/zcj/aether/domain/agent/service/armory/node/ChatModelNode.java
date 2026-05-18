package cn.zcj.aether.domain.agent.service.armory.node;

import cn.zcj.aether.domain.agent.model.entity.ArmoryCommandEntity;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.zcj.aether.domain.agent.service.armory.AbstractArmorySupport;
import cn.zcj.aether.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import cn.zcj.aether.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import cn.zcj.aether.domain.agent.service.tool.McpToolAdapter;
import cn.zcj.aether.domain.agent.service.tool.SkillsToolAdapter;
import cn.zcj.aether.domain.agent.service.tool.Tool;
import cn.zcj.aether.domain.agent.service.tool.ToolRegistry;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

// NOTE 13，路由到ChatModelNode节点，这个节点的作用是根据配置构建ChatModel实例，
//  并放入上下文对象中，供后续节点使用，最后路由到AgentNode节点

@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource
    private AgentNode agentNode;

    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    @Resource
    private ToolRegistry toolRegistry;

    @Resource
    private McpToolAdapter mcpToolAdapter;

    @Resource
    private SkillsToolAdapter skillsToolAdapter;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        // 这个ChatModel的构建参考app模块中的测试类SpringAiToolTest
//        ChatModel chatModel = OpenAiChatModel.builder()
//                .openAiApi(openAiApi)
//                .defaultOptions(OpenAiChatOptions.builder()
//                        .model("deepseek-chat")              // 使用 deepseek-chat 模型
//                        .toolCallbacks(SyncMcpToolCallbackProvider.builder()
//                                .mcpClients(sseMcpClient(baiduMcpApiKey)) // 注入 MCP 工具
//                                .build()
//                                .getToolCallbacks())
//                        .build())
//                .build();

        //获取上下文对象,读取openAiApi,在下面传递给ChatModel
        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();

        // 获取配置对象
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        //NOTE 读取ChatModel
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();

        // 构建mcp工具回调
        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if (null != toolMcpList && !toolMcpList.isEmpty()) {
            // NOTE 遍历toolMcpList: SSE->SSEToolMcpCreateService.buildToolCallback()║Local → LocalToolMcpCreateService
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                TooMcpCreateService tooMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
                ToolCallback[] toolCallbacks = tooMcpCreateService.buildToolCallback(toolMcp);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // 构建skills服务
        if (null != toolSkillsList && !toolSkillsList.isEmpty()) {
            //NOTE 遍历toolSkillsList: 调用ToolSkillsCreateService.buildToolCallback()方法构建工具回调
            for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
                ToolCallback[] toolCallbacks = toolSkillsCreateService.buildToolCallback(toolSkills);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // 构建对话模型
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chatModelConfig.getModel())
                        .toolCallbacks(toolCallbackList)
                        .build())
                .build();

        dynamicContext.setChatModel(chatModel);

        // 注册工具到 ToolRegistry（AgentRuntime → ToolExecutor 调用链路）
        registerToolsToRegistry(toolMcpList, toolSkillsList);

        // 校验工具定义就绪 — MCP SSE 初始化可能尚未完全返回工具 schema
        validateToolDefinitions(toolCallbackList);

        // 注册为 Spring Bean，供 ChatService / ContextManager / AgentRuntime 注入使用
        registerBean("chatModel", ChatModel.class, chatModel);

        return router(requestParameter, dynamicContext);
    }

    /**
     * 将 MCP 和 Skills 工具适配为 Tool 接口并注册到 ToolRegistry
     *
     * 此步骤是 AgentRuntime → ToolExecutor → ToolRegistry 调用链路的关键：
     * 没有注册，LLM 返回的 tool_call 将因 "Tool not found" 而失败。
     */
    private void registerToolsToRegistry(
            List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList,
            List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList) {

        if (toolMcpList != null) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                try {
                    Tool tool = mcpToolAdapter.adapt(toolMcp);
                    toolRegistry.register(tool);
                    log.info("MCP 工具已注册: {}", tool.name());
                } catch (Exception e) {
                    log.error("MCP 工具注册失败: {}", extractMcpName(toolMcp), e);
                }
            }
        }

        if (toolSkillsList != null) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
                try {
                    Tool tool = skillsToolAdapter.adapt(toolSkills);
                    toolRegistry.register(tool);
                    log.info("Skills 工具已注册: {}", tool.name());
                } catch (Exception e) {
                    log.error("Skills 工具注册失败: type={} path={}", toolSkills.getType(), toolSkills.getPath(), e);
                }
            }
        }
    }

    private String extractMcpName(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        if (toolMcp.getSse() != null) return toolMcp.getSse().getName();
        if (toolMcp.getStdio() != null) return toolMcp.getStdio().getName();
        if (toolMcp.getLocal() != null) return toolMcp.getLocal().getName();
        return "unknown";
    }

    /**
     * 校验工具定义是否已就绪
     * MCP SSE 连接初始化期间，ToolCallback.getToolDefinition() 可能尚未返回完整 schema。
     * 等待最多 3 次 × 500ms，确保工具定义完整后再注册 ChatModel Bean。
     */
    private void validateToolDefinitions(List<ToolCallback> toolCallbacks) {
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            log.info("无工具回调需校验，跳过");
            return;
        }

        int totalTools = toolCallbacks.size();
        for (int retry = 0; retry < 3; retry++) {
            long nullCount = toolCallbacks.stream()
                    .filter(tc -> {
                        try {
                            return tc.getToolDefinition() == null;
                        } catch (Exception e) {
                            log.warn("获取工具定义异常: {}", e.getMessage());
                            return true;
                        }
                    })
                    .count();

            if (nullCount == 0) {
                log.info("工具定义校验通过: {}/{} 个工具已就绪", totalTools, totalTools);
                return;
            }

            if (retry < 2) {
                log.warn("工具定义未就绪 ({}/{} 为空)，等待 500ms 后重试 ({}/{})",
                        nullCount, totalTools, retry + 1, 3);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } else {
                log.warn("工具定义校验未完全通过: {} 个工具中仍有 {} 个未就绪，继续注册",
                        totalTools, nullCount);
            }
        }
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

}
