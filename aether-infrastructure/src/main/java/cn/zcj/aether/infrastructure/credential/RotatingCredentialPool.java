package cn.zcj.aether.infrastructure.credential;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.failover.CredentialPool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于轮询的凭据池 — 按 provider 维护一组凭据，轮换时返回下一个。
 * 语义对齐 hermes child_pool.acquire_lease + recover_with_credential_pool：
 * 每个凭据在同一时刻被唯一使用，轮换后旧凭据不会立即被复用。
 */
@Component
public class RotatingCredentialPool implements CredentialPool {

    private final Map<String, List<CredentialEntry>> credentials = new HashMap<>();

    /** 播种某 provider 的凭据组（至少 2 组才可轮换）。幂等：重复播种覆盖旧池。 */
    @Override
    public void seed(String provider, List<CredentialEntry> entries) {
        credentials.put(provider, new ArrayList<>(entries));
    }

    @Override
    public Optional<ModelConfig> rotate(ModelConfig current, String provider) {
        List<CredentialEntry> list = credentials.get(provider);
        if (list == null || list.size() < 2) {
            return Optional.empty(); // 无备选凭据，无法轮换
        }
        int idx = indexOf(current.getApiKey(), list);
        if (idx < 0) {
            return Optional.empty(); // 当前凭据不在池中（如 fallback 模型的 key），无法轮换
        }
        CredentialEntry next = list.get((idx + 1) % list.size());
        ModelConfig rotated = ModelConfig.builder()
                .modelId(current.getModelId())
                .baseUrl(next.baseUrl() != null ? next.baseUrl() : current.getBaseUrl())
                .apiKey(next.apiKey())
                .completionsPath(next.completionsPath() != null ? next.completionsPath() : current.getCompletionsPath())
                .maxAttempts(current.getMaxAttempts())
                .build();
        return Optional.of(rotated);
    }

    private static int indexOf(String apiKey, List<CredentialEntry> list) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).apiKey().equals(apiKey)) {
                return i;
            }
        }
        return -1;
    }
}
