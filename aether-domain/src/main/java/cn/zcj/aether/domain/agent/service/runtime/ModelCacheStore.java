package cn.zcj.aether.domain.agent.service.runtime;

import java.time.Duration;
import java.util.Optional;

/**
 * Secondary storage contract for LLM response caching.
 */
public interface ModelCacheStore {

    Optional<ModelCacheSnapshot> get(String key);

    void put(String key, ModelCacheSnapshot snapshot, Duration ttl);

    /** Optional clear; implementations may leave local invalidation as the minimum behavior. */
    default void clear() {
    }
}
