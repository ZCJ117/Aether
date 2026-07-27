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

    /** P0-#8: 是否启用检查点（默认 true，每 checkpointInterval 轮自动保存） */
    @Builder.Default
    boolean checkpointEnabled = true;

    /** P0-#8: 检查点保存间隔（轮数） */
    @Builder.Default
    int checkpointInterval = 5;

    /** P1-#2: 是否启用 LLM 响应缓存 */
    @Builder.Default
    boolean cacheEnabled = true;

    /** P1-#2: 缓存 TTL（秒） */
    @Builder.Default
    int cacheTtlSeconds = 60;

    /** 可取消执行令牌（默认无超时） */
    @Builder.Default
    CancelToken cancelToken = new CancelToken();

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
