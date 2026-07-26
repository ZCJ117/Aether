package cn.zcj.aether.domain.agent.service.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * P1-#2: LLM 响应缓存。
 * Key = modelName + ":" + messages.contentHashCode()
 * Value = ModelInvoker.ModelCallResult
 * TTL = 60s (默认), 最大 1000 条, LRU 淘汰
 */
@Slf4j
@Component
public class ModelCallCache {

    private final Cache<String, ModelInvoker.ModelCallResult> cache;

    public ModelCallCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

    public static String cacheKey(String modelName, List<org.springframework.ai.chat.messages.Message> messages) {
        int hash = Objects.hash(modelName, messages.stream()
                .map(m -> m.getText() != null ? m.getText() : "")
                .toList());
        return modelName + ":" + hash;
    }

    public ModelInvoker.ModelCallResult get(String key) {
        ModelInvoker.ModelCallResult result = cache.getIfPresent(key);
        if (result != null) log.debug("LLM 缓存命中: key={}", key);
        return result;
    }

    public void put(String key, ModelInvoker.ModelCallResult result, int ttlSeconds) {
        if (result.hasError()) return;
        cache.put(key, result);
        log.debug("LLM 缓存写入: key={}", key);
    }

    public void clear() {
        cache.invalidateAll();
        log.info("LLM 缓存已清空");
    }

    public double hitRate() { return cache.stats().hitRate(); }
}
