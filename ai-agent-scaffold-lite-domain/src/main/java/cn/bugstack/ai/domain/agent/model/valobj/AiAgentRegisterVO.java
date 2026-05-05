package cn.bugstack.ai.domain.agent.model.valobj;

import com.google.adk.runner.InMemoryRunner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Ai Agent 智能体注册值对象
 * @author zuochangjian
 * 2026/4/27
 * NOTE 这个是给RunnerNode节点使用的注册对象，包含了智能体的基本信息和运行器对象，用于在Spring容器中注册AI Agent实例
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentRegisterVO {

    /**
     * 智能体名称
     */
    private String appName;

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

    /**
     * 智能体执行对象
     */
    private InMemoryRunner runner;

}
