package cn.zcj.aether.domain.agent.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent节点定义
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentNodeDef {

    private String name;
    private String instruction;
    private String description;
    private String outputKey;

    @Builder.Default
    private List<String> toolNames = List.of();

    private String modelRef;

    /**
     * Agent 类型标识（"react"、"flow"、"user_proxy"）
     * P0-1 新增：支持通过 AgentFactory 创建不同类型的 Agent 实例
     */
    @Builder.Default
    private String agentType = "react";
}
