package cn.zcj.aether.domain.agent.service.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    static final String HITRATE_METRIC = "aether.cache.llm.hitrate";

    private final Cache<String, ModelInvoker.ModelCallResult> cache;
    private final ModelCacheStore secondaryStore;

    public ModelCallCache() {
        this(null, (ModelCacheStore) null);
    }

    /** Compatibility constructor for pre-P2 unit tests without a secondary store. */
    public ModelCallCache(MeterRegistry meterRegistry) {
        this(meterRegistry, (ModelCacheStore) null);
    }

    /** P0(1.5-步骤4): 命中率 Gauge 接入 Micrometer；注册表缺省时退化为纯缓存。 */
    @Autowired(required = false)
    public ModelCallCache(MeterRegistry meterRegistry,
                          org.springframework.beans.factory.ObjectProvider<ModelCacheStore> secondaryStore) {
        this(meterRegistry, secondaryStore != null ? secondaryStore.getIfAvailable() : null);
    }

    /** Test constructor: meter registry and Redis L2 are both optional. */
    public ModelCallCache(MeterRegistry meterRegistry, ModelCacheStore secondaryStore) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .recordStats()
                .build();
        this.secondaryStore = secondaryStore;
        if (meterRegistry != null) {
            Gauge.builder(HITRATE_METRIC, this, ModelCallCache::hitRate)
                    .description("LLM 响应缓存命中率")
                    .register(meterRegistry);
        }
    }

    public static String cacheKey(String modelName, List<org.springframework.ai.chat.messages.Message> messages) {
        int hash = Objects.hash(modelName, messages.stream()
                .map(m -> m.getText() != null ? m.getText() : "")
                .toList());
        return modelName + ":" + hash;
    }

    public ModelInvoker.ModelCallResult get(String key) {
        ModelInvoker.ModelCallResult result = cache.getIfPresent(key);
        if (result != null) {
            log.debug("LLM L1 缓存命中: key={}", key);
            return result;
        }

        if (secondaryStore == null) {
            return null;
        }
        try {
            result = secondaryStore.get(key).map(ModelCacheSnapshot::toResult).orElse(null);
        } catch (Exception e) {
            log.warn("LLM L2 缓存读取失败，退化为未命中: key={}", key, e);
            return null;
        }
        if (result != null) {
            cache.put(key, result);
            log.debug("LLM L2 缓存命中: key={}", key);
        }
        return result;
    }

    public void put(String key, ModelInvoker.ModelCallResult result, int ttlSeconds) {
        if (result.hasError()) return;
        cache.put(key, result);
        if (secondaryStore != null && ttlSeconds > 0) {
            try {
                secondaryStore.put(key, ModelCacheSnapshot.from(result), Duration.ofSeconds(ttlSeconds));
            } catch (Exception e) {
                log.warn("LLM L2 缓存写入失败: key={}", key, e);
            }
        }
        log.debug("LLM 缓存写入: key={}", key);
    }

    public void clear() {
        cache.invalidateAll();
        if (secondaryStore != null) {
            try {
                secondaryStore.clear();
            } catch (Exception e) {
                log.warn("LLM L2 缓存清空失败", e);
            }
        }
        log.info("LLM 缓存已清空");
    }

    public double hitRate() { return cache.stats().hitRate(); }
}
