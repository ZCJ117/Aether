package cn.zcj.aether.domain.agent.service.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provider 注册与查找中心。
 * 灵感来源：CrewAI _LLM_TYPE_REGISTRY + AgentScope Model SPI。
 */
@Slf4j
@Component
public class ModelProviderRegistry {

    @Resource
    private List<ModelProvider> providers; // Spring 自动注入所有 ModelProvider Bean

    private final List<ModelProvider> orderedProviders = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        if (providers != null) {
            orderedProviders.addAll(providers);
        }
        // 将兜底 Provider 放到最后（如果有的话）
        orderedProviders.sort((a, b) -> {
            if ("openai".equals(a.providerName())) return 1;  // openai 兜底，排最后
            if ("openai".equals(b.providerName())) return -1;
            return 0;
        });
        log.info("ModelProviderRegistry 初始化完成，注册了 {} 个 Provider: {}",
            orderedProviders.size(),
            orderedProviders.stream().map(ModelProvider::providerName).toList());
    }

    /**
     * 根据 modelId 查找匹配的 Provider。
     * 按注册顺序匹配，返回第一个 supports() 返回 true 的。
     */
    public ModelProvider resolve(String modelId) {
        for (ModelProvider provider : orderedProviders) {
            if (provider.supports(modelId)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("未找到支持模型 [" + modelId + "] 的 Provider。" +
            "已注册的 Provider: " + orderedProviders.stream().map(ModelProvider::providerName).toList());
    }

    /** 注册额外的 Provider（供插件使用） */
    public void register(ModelProvider provider) {
        orderedProviders.add(provider);
        log.info("动态注册 ModelProvider: {}", provider.providerName());
    }

    /** 获取所有已注册的 Provider */
    public List<ModelProvider> getAllProviders() {
        return List.copyOf(orderedProviders);
    }
}
