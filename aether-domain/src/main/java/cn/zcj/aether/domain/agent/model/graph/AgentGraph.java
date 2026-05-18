package cn.zcj.aether.domain.agent.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent图中间表示 (IR)
 * 由 AgentGraphCompiler 从 YAML 配置编译生成
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentGraph {

    private String appName;

    @Builder.Default
    private Map<String, AgentNodeDef> agentDefs = new LinkedHashMap<>();

    @Builder.Default
    private List<AgentEdge> edges = List.of();

    private String entryPoint;

    private String modelRef;
}
