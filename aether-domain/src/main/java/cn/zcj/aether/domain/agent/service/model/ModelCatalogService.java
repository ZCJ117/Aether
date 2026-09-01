package cn.zcj.aether.domain.agent.service.model;

import cn.zcj.aether.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型目录服务 — O2 从 ChatService 拆出。
 *
 * <p>单一职责：从 YAML agent 配置收集已配置的模型列表，并按 modelId 推断 provider。
 */
@Slf4j
@Service
public class ModelCatalogService {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /**
     * 获取所有已配置的模型列表（从 YAML agent 配置中收集）。
     */
    public List<Map<String, String>> getConfiguredModels() {
        Map<String, Map<String, String>> unique = new LinkedHashMap<>();
        var tables = aiAgentAutoConfigProperties.getTables();
        if (tables == null) return List.of();

        for (var entry : tables.entrySet()) {
            var vo = entry.getValue();
            if (vo.getModule() == null || vo.getModule().getChatModel() == null) continue;
            String modelId = vo.getModule().getChatModel().getModel();
            if (modelId == null || modelId.isBlank()) continue;
            if (unique.containsKey(modelId)) continue;

            // 从 modelId 推断 provider
            String providerId = inferProvider(modelId);
            unique.put(modelId, Map.of(
                "id", modelId.toLowerCase().replaceAll("[^a-z0-9-]", "-"),
                "providerId", providerId,
                "modelId", modelId,
                "status", "ACTIVE"));
        }
        return new ArrayList<>(unique.values());
    }

    /** 从 modelId 推断 provider 名称 */
    private String inferProvider(String modelId) {
        if (modelId == null) return "unknown";
        String lower = modelId.toLowerCase();
        if (lower.contains("deepseek")) return "deepseek";
        if (lower.contains("gpt") || lower.contains("o1") || lower.contains("o3")) return "openai";
        if (lower.contains("claude")) return "anthropic";
        if (lower.contains("qwen")) return "dashscope";
        return "openai";
    }
}
