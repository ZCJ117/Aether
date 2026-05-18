package cn.zcj.aether.domain.agent.service.compiler;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent图编译器
 * 将 AiAgentConfigTableVO (YAML解析结果) 编译为 AgentGraph IR
 *
 * 对应原策略树节点:
 *   AgentNode.doApply()      → compileAgentDefs()
 *   AgentWorkflowNode.get()  → compileEdges()
 *   RunnerNode.getRunner()   → compile(): 确定entryPoint
 */
@Slf4j
@Service
public class AgentGraphCompiler {

    public AgentGraph compile(AiAgentConfigTableVO config) {
        String appName = config.getAppName();
        AiAgentConfigTableVO.Agent agent = config.getAgent();
        AiAgentConfigTableVO.Module module = config.getModule();

        // Step 1: 编译 agents → AgentNodeDef
        Map<String, AgentNodeDef> agentDefs = compileAgentDefs(module);

        // Step 2: 编译 agent-workflows → edges
        List<AgentEdge> edges = compileEdges(module);

        // Step 3: 确定入口
        String entryPoint = module.getRunner() != null
                ? module.getRunner().getAgentName()
                : null;

        // Step 4: 提取模型引用
        String modelRef = module.getChatModel() != null
                ? module.getChatModel().getModel()
                : null;

        return AgentGraph.builder()
                .appName(appName)
                .agentDefs(agentDefs)
                .edges(edges)
                .entryPoint(entryPoint)
                .modelRef(modelRef)
                .build();
    }

    private Map<String, AgentNodeDef> compileAgentDefs(AiAgentConfigTableVO.Module module) {
        Map<String, AgentNodeDef> defs = new LinkedHashMap<>();
        List<AiAgentConfigTableVO.Module.Agent> agents = module.getAgents();

        if (agents == null || agents.isEmpty()) {
            return defs;
        }

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            String modelRef = module.getChatModel() != null
                    ? module.getChatModel().getModel() : null;
            log.info("Agent [{}] 指令已解析: instructionLen={}, modelRef={}",
                    agentConfig.getName(),
                    agentConfig.getInstruction() != null ? agentConfig.getInstruction().length() : 0,
                    modelRef);
            AgentNodeDef def = AgentNodeDef.builder()
                    .name(agentConfig.getName())
                    .instruction(agentConfig.getInstruction())
                    .description(agentConfig.getDescription())
                    .outputKey(agentConfig.getOutputKey())
                    .toolNames(List.of())
                    .modelRef(modelRef)
                    .build();
            defs.put(agentConfig.getName(), def);
        }

        return defs;
    }

    private List<AgentEdge> compileEdges(AiAgentConfigTableVO.Module module) {
        List<AiAgentConfigTableVO.Module.AgentWorkflow> workflows = module.getAgentWorkflows();

        if (workflows == null || workflows.isEmpty()) {
            return List.of();
        }

        List<AgentEdge> edges = new ArrayList<>();
        for (AiAgentConfigTableVO.Module.AgentWorkflow wf : workflows) {
            AgentEdge edge = AgentEdge.builder()
                    .workflowName(wf.getName())
                    .type(AgentEdgeType.fromYamlType(wf.getType()))
                    .subAgents(wf.getSubAgents() != null
                            ? new ArrayList<>(wf.getSubAgents()) : List.of())
                    .description(wf.getDescription())
                    .maxIterations(wf.getMaxIterations() != null
                            ? wf.getMaxIterations() : 3)
                    .build();
            edges.add(edge);
        }

        return edges;
    }
}
