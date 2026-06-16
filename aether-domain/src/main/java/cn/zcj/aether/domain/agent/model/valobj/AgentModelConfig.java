package cn.zcj.aether.domain.agent.model.valobj;

import lombok.Data;

/**
 * Per-Agent 模型配置。
 * 在 YAML 中 agents[].model 节点下定义。
 *
 * P0-3 新增：支持每个 Agent 使用独立模型。
 */
@Data
public class AgentModelConfig {
    /** 模型 ID，如 "claude-sonnet-4-6"、"gpt-4o" */
    private String modelId;

    /** 提供商（可选，如 "anthropic"，不填则从全局 ai-api 推断） */
    private String provider;

    /** 独立 API Key（可选，不填则复用全局） */
    private String apiKey;

    /** 独立 Base URL（可选，不填则复用全局） */
    private String baseUrl;
}
