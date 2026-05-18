package cn.zcj.aether.domain.agent.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工作流边定义
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentEdge {

    private String workflowName;

    private AgentEdgeType type;

    private List<String> subAgents;

    private String description;

    @Builder.Default
    private Integer maxIterations = 3;
}
