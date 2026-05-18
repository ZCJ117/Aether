package cn.zcj.aether.domain.agent.model.valobj;

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

        @Data
        public static class AiApi {
            //NOTE: 这里的 AiApi 是为了适配 DeepSeek 兼容 OpenAI 协议的 API 配置，
            // 如果后续接入其他大模型，可以在这里扩展更多字段。
            //Note 为什么有这些字段？参考SequentialAgentTest中的OpenAiApi配置，以及SpringAiToolTest中的OpenAiApi配置。
            private String baseUrl; //基础的URL，最终会和 embeddingsPath或者completionsPath 拼接成完整的API地址
            private String apiKey;  //鉴权用的 API Key
            private String completionsPath = "/v1/chat/completions"; //聊天接口路径,"v1"要和官方文档保持一致
            private String embeddingsPath = "/v1/embeddings"; //向量接口路径

        }

        @Data
        public static class ChatModel {

            private String model;

            private List<ToolMcp> toolMcpList; // MCP 服务器配置列表，智能体通过这些 MCP 服务器连接工具，获取工具调用结果

            private List<ToolSkills> toolSkillsList; // 工具技能配置列表，智能体通过这些工具技能调用工具，获取工具调用结果，工具技能可以是用户配置的，也可以是放到工程下的资源文件

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
            private String name;
            private String instruction;
            private String description;
            private String outputKey;

        }

        // 智能体工作流配置，定义智能体之间的调用关系和执行方式，例如循环、并行、顺序等。
        @Data
        public static class AgentWorkflow {
            /**
             * 类型；loop、parallel、sequential
             */
            private String type; // 工作流类型，决定智能体的执行方式，例如 loop 表示循环执行，parallel 表示并行执行，sequential 表示顺序执行
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3; // loop 类型的工作流的最大循环次数，防止死循环

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
        }
    }

}
