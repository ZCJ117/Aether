package cn.zcj.aether.domain.agent.model.valobj;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Ai Agent 智能体配置表值对象
 *
 * @author zuochangjian
 * 2026/04/19
 */
@Data
public class AiAgentConfigTableVO {

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 智能体配置
     */
    private Agent agent;

    /**
     * 智能体模块
     */
    private Module module;

    @Data
    public static class Agent {

        /**
         * 智能体ID
         */
        private String agentId;

        /**
         * 智能体名称
         */
        private String agentName;

        /**
         * 智能体描述
         */
        private String agentDesc;

    }

    @Data
    public static class Module {

        private AiApi aiApi;

        private ChatModel chatModel;

        private List<Agent> agents;

        private List<AgentWorkflow> agentWorkflows;

        private Runner runner;

        /** P1-#9: 工具安全配置 */
        private ToolSecurity toolSecurity;

        /** D3: 配置驱动生命周期 Hook 段（对齐 hermes shell_hooks register_from_config 的 hooks:） */
        private List<HookConfigVO> hooks;

        @Data
        public static class HookConfigVO {
            /** HookPoint 枚举名（如 SUBAGENT_START） */
            private String point;
            /** 外部命令（无 shell，按空白拆分） */
            private String command;
            /** 超时毫秒（默认 60000，上限 300000） */
            private Integer timeoutMs;
        }

        @Data
        public static class AiApi {
            //NOTE: 这里的 AiApi 是为了适配 DeepSeek 兼容 OpenAI 协议的 API 配置，
            // 如果后续接入其他大模型，可以在这里扩展更多字段。
            //Note 为什么有这些字段？参考SequentialAgentTest中的OpenAiApi配置，以及SpringAiToolTest中的OpenAiApi配置。
            @NotBlank(message = "ai-api.base-url 不能为空")
            private String baseUrl; //基础的URL，最终会和 embeddingsPath或者completionsPath 拼接成完整的API地址
            @NotBlank(message = "ai-api.api-key 不能为空")
            private String apiKey;  //鉴权用的 API Key
            private String completionsPath = "/v1/chat/completions"; //聊天接口路径,"v1"要和官方文档保持一致
            private String embeddingsPath = "/v1/embeddings"; //向量接口路径

        }

        @Data
        public static class ChatModel {

            @NotBlank(message = "chat-model.model 不能为空")
            private String model;

            private List<ToolMcp> toolMcpList; // MCP 服务器配置列表，智能体通过这些 MCP 服务器连接工具，获取工具调用结果

            private List<ToolSkills> toolSkillsList; // 工具技能配置列表，智能体通过这些工具技能调用工具，获取工具调用结果，工具技能可以是用户配置的，也可以是放到工程下的资源文件

            // ── P1 容错：Fallback 模型链配置 ──

            /** P1: 简单 fallback 模型 ID 列表（复用全局 ai-api 配置） */
            private List<String> fallbackModels;

            /** P1: 完整 fallback 路由列表（每项可独立指定 provider/baseUrl/apiKey） */
            private List<FallbackRoute> fallback;

            /** P1: 最大重试次数（覆盖 ModelConfig 默认值 3） */
            private Integer maxAttempts;

            /** P1: 初始退避秒数（覆盖 ModelConfig 默认值 2s） */
            private Integer initialBackoffSeconds;

            /** P1: 最大退避秒数（覆盖 ModelConfig 默认值 30s） */
            private Integer maxBackoffSeconds;

            /**
             * P1: Fallback 路由定义。
             * YAML 格式: fallback: [{provider: "anthropic", model: "claude-sonnet-4-6"}, ...]
             */
            @Data
            public static class FallbackRoute {
                /** 模型 ID */
                private String model;
                /** Provider 名称（可选，从 model 自动推断） */
                private String provider;
                /** API Base URL（可选，复用全局） */
                private String baseUrl;
                /** API Key（可选，复用全局） */
                private String apiKey;
                /** Completions 路径（可选） */
                private String completionsPath;
            }

            @Data
            public static class ToolMcp {

                private SSEServerParameters sse;

                private StdioServerParameters stdio;

                private LocalParameters local;

                // NOTE SSE server的方式连接，适合远程的 MCP 服务器，MCP 服务器需要实现 SSE 协议，智能体通过 SSE 连接 MCP 服务器获取工具调用结果。
                @Data
                public static class SSEServerParameters { // MCP 服务器参数配置
                    private String name;    // MCP 服务器名称，用户自定义
                    private String baseUri; // MCP 服务器的基础 URI，例如 "https://mcp.example.com"
                    private String sseEndpoint; // SSE 端点，例如 "/events"，最终会和 baseUri 拼接成完整的 URL
                    private Integer requestTimeout = 3000; // 请求超时时间，单位毫秒

                }

                // NOTE 本地进程服务器的方式连接，适合本地部署的 MCP 服务器，智能体通过启动一个本地进程来运行 MCP 服务器，进程的命令、参数和环境变量由用户配置。
                // 通过本地的jar包，command命令，python脚本等方式启动一个本地的 MCP 服务器，智能体通过标准输入输出与该进程通信，获取工具调用结果。
                @Data
                public static class StdioServerParameters { // 本地进程服务器参数配置
                    private String name;
                    private Integer requestTimeout = 3000;
                    private ServerParameters serverParameters;

                    // 服务器参数配置，包括启动命令、参数和环境变量
                    @Data
                    public static class ServerParameters {
                        private String command;
                        private List<String> args;
                        private Map<String, String> env;

                    }
                }

                @Data
                public static class LocalParameters {
                    private String name;
                }

            }

            @Data
            public static class ToolSkills {

                /**
                 * 类型；directory（用户配置的，映射进来的）、resource（放到工程下的）
                 */
                private String type = "directory";

                /**
                 * 路径；
                 */
                private String path;

            }

        }

        @Data
        public static class Agent {
            @NotBlank(message = "agent.name 不能为空")
            private String name;
            @NotBlank(message = "agent.instruction 不能为空")
            private String instruction;
            private String description;
            private String outputKey;

            // C1: Agent 级工具作用域 — 显式指定该 Agent 可使用的工具名列表
            // "*" 或未配置 = 全部工具；空列表 = 无工具
            private List<String> toolNames;

            // P0-3 新增：独立模型配置（可选）
            private AgentModelConfig model;

        }

        // 智能体工作流配置，定义智能体之间的调用关系和执行方式，例如循环、并行、顺序等。
        @Data
        public static class AgentWorkflow {
            /**
             * 类型；loop、parallel、sequential、graphflow
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;

            // ====== P1-1 新增：GraphFlow 专用字段 ======

            /** DAG 入口节点 ID */
            private String entryPoint;

            /** DAG 节点列表（声明所有参与节点） */
            private List<GraphFlowNode> nodes;

            /** DAG 边列表 */
            private List<GraphFlowEdge> edges;

            @Data
            public static class GraphFlowNode {
                private String id;
                private String agent;  // 引用 agents[] 中定义的 Agent 名称
            }

            @Data
            public static class GraphFlowEdge {
                private String from;
                private String to;
                private String condition;
                private String activation;
                private String exitCondition;
                private String description;
            }
        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
        }

        @Data
        public static class ToolSecurity {
            private List<String> allowlist;
            private List<String> denylist;
        }
    }

}
