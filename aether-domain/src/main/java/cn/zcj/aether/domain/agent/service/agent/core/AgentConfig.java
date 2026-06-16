package cn.zcj.aether.domain.agent.service.agent.core;

import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import lombok.Builder;
import lombok.Value;
import java.util.List;

/**
 * Agent 不可变配置。从 AgentNodeDef 或 YAML 配置映射而来。
 */
@Value
@Builder
public class AgentConfig {
    /** Agent 名称（同 ID） */
    String name;

    /** 系统指令/提示词 */
    String instruction;

    /** 描述 */
    String description;

    /** 跨 Agent 输出键名 */
    String outputKey;

    /** 该 Agent 使用的工具名称列表 */
    @Builder.Default
    List<String> toolNames = List.of();

    /** 模型引用（如 "deepseek-chat"、"gpt-4o"） */
    String modelRef;

    /** Agent 类型标识（"react"、"flow"、"user_proxy"） */
    @Builder.Default
    String agentType = "react";

    /**
     * 从现有的 AgentNodeDef 转换（兼容过渡期）
     */
    public static AgentConfig fromNodeDef(AgentNodeDef nodeDef) {
        return AgentConfig.builder()
            .name(nodeDef.getName())
            .instruction(nodeDef.getInstruction())
            .description(nodeDef.getDescription())
            .outputKey(nodeDef.getOutputKey())
            .toolNames(nodeDef.getToolNames())
            .modelRef(nodeDef.getModelRef())
            .build();
    }
}
