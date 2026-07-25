package cn.zcj.aether.domain.agent.service.compiler;

import cn.zcj.aether.domain.agent.model.graph.AgentEdge;
import cn.zcj.aether.domain.agent.model.graph.AgentEdgeType;
import cn.zcj.aether.domain.agent.model.graph.AgentGraph;
import cn.zcj.aether.domain.agent.model.graph.AgentNodeDef;
import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // Step 5: 编译后校验 {outputKey} 引用
        validateOutputKeyReferences(agentDefs, edges, entryPoint);

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
            // P0-3: Per-Agent 模型优先，回退全局
            String modelRef;
            if (agentConfig.getModel() != null && agentConfig.getModel().getModelId() != null) {
                modelRef = agentConfig.getModel().getModelId();
            } else {
                modelRef = module.getChatModel() != null
                        ? module.getChatModel().getModel() : null;
            }
            log.info("Agent [{}] 指令已解析: instructionLen={}, modelRef={}",
                    agentConfig.getName(),
                    agentConfig.getInstruction() != null ? agentConfig.getInstruction().length() : 0,
                    modelRef);
            AgentNodeDef def = AgentNodeDef.builder()
                    .name(agentConfig.getName())
                    .instruction(agentConfig.getInstruction())
                    .description(agentConfig.getDescription())
                    .outputKey(agentConfig.getOutputKey())
                    .toolNames(compileToolNames(agentConfig))
                    .modelRef(modelRef)
                    .agentType("react")
                    .build();
            defs.put(agentConfig.getName(), def);
        }

        return defs;
    }

    /**
     * 编译 Agent 的工具名列表。
     * null/空 = 全部工具（语义："*"）。
     */
    private List<String> compileToolNames(AiAgentConfigTableVO.Module.Agent agentConfig) {
        List<String> rawNames = agentConfig.getToolNames();
        if (rawNames == null || rawNames.isEmpty()) {
            return List.of("*");
        }
        return List.copyOf(rawNames);
    }

    private List<AgentEdge> compileEdges(AiAgentConfigTableVO.Module module) {
        List<AiAgentConfigTableVO.Module.AgentWorkflow> workflows = module.getAgentWorkflows();

        if (workflows == null || workflows.isEmpty()) {
            return List.of();
        }

        List<AgentEdge> edges = new ArrayList<>();
        for (AiAgentConfigTableVO.Module.AgentWorkflow wf : workflows) {
            AgentEdgeType type = AgentEdgeType.fromYamlType(wf.getType());

            if (type == AgentEdgeType.GRAPHFLOW) {
                // P1-1: GraphFlow 模式 — 编译 nodes + edges 列表
                if (wf.getEdges() != null) {
                    List<AgentEdge> flowEdges = wf.getEdges().stream()
                        .map(yamlEdge -> AgentEdge.builder()
                            .type(AgentEdgeType.GRAPHFLOW)
                            .from(yamlEdge.getFrom())
                            .to(yamlEdge.getTo())
                            .condition(yamlEdge.getCondition())
                            .activation(yamlEdge.getActivation())
                            .exitCondition(yamlEdge.getExitCondition())
                            .description(yamlEdge.getDescription())
                            .build())
                        .toList();
                    edges.addAll(flowEdges);
                }
            } else {
                // 旧模式（SEQUENTIAL/PARALLEL/LOOP）：保持不变
                AgentEdge edge = AgentEdge.builder()
                        .workflowName(wf.getName())
                        .type(type)
                        .subAgents(wf.getSubAgents() != null
                                ? new ArrayList<>(wf.getSubAgents()) : List.of())
                        .description(wf.getDescription())
                        .maxIterations(wf.getMaxIterations() != null
                                ? wf.getMaxIterations() : 3)
                        .build();
                edges.add(edge);
            }
        }

        return edges;
    }

    /**
     * 编译后校验：确保所有 instruction 中引用的 {outputKey}
     * 都在已定义的 outputKey 中有对应定义。未解析的引用 → AgentCompileException。
     */
    private void validateOutputKeyReferences(
            Map<String, AgentNodeDef> agentDefs,
            List<AgentEdge> edges,
            String entryPoint) {

        // 收集所有定义了 outputKey 的 agent
        Map<String, String> definedOutputKeys = new LinkedHashMap<>();
        for (var def : agentDefs.values()) {
            if (def.getOutputKey() != null && !def.getOutputKey().isBlank()) {
                definedOutputKeys.put(def.getOutputKey(), def.getName());
            }
        }

        if (definedOutputKeys.isEmpty()) {
            // 没有定义任何 outputKey → 无需校验
            return;
        }

        // 对每个 agent，校验其 instruction 中的 {key} 引用
        Set<String> availableKeys = new HashSet<>(definedOutputKeys.keySet());
        for (var def : agentDefs.values()) {
            String instruction = def.getInstruction();
            if (instruction == null) continue;

            Set<String> referencedKeys = extractTemplateKeys(instruction);
            for (String key : referencedKeys) {
                if (!availableKeys.contains(key)) {
                    throw new AgentCompileException(
                        "Agent [" + def.getName() + "] 的 instruction 引用了未定义的 outputKey: {" +
                        key + "}。已定义的 outputKey: " + definedOutputKeys.keySet());
                }
            }
        }

        log.info("outputKey 引用校验通过: {} 个 Agent，{} 个已定义 outputKey",
                agentDefs.size(), definedOutputKeys.size());
    }

    /**
     * 从 instruction 文本中提取所有 {key} 占位符
     */
    private Set<String> extractTemplateKeys(String instruction) {
        Set<String> keys = new LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\w+)\\}")
                .matcher(instruction);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }
}
