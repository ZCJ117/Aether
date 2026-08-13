package cn.zcj.aether.domain.agent.service.model.failover;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;

import java.util.List;
import java.util.Optional;

/**
 * 凭据轮换端口 — 对齐 hermes agent_runtime_helpers.py recover_with_credential_pool。
 *
 * <p>当错误分类标记 shouldRotateCredential（AUTH_TRANSIENT / BILLING）时，
 * ResilientChatModelExecutor 调用 {@link #rotate} 获取下一个可用凭据并重建 ChatModel。</p>
 */
public interface CredentialPool {

    /** 凭据条目：一组可轮换的 apiKey/baseUrl/completionsPath */
    record CredentialEntry(String apiKey, String baseUrl, String completionsPath) {
    }

    /**
     * 播种某 provider 的凭据组（至少 2 组才可轮换）。幂等：重复播种覆盖旧池。
     *
     * @param provider Provider 名称（ModelProvider.providerName()）
     * @param entries  凭据列表（首项应为当前主凭据，保证 rotate 能按 apiKey 命中）
     */
    void seed(String provider, List<CredentialEntry> entries);

    /**
     * 为当前模型配置返回下一组可用凭据。
     *
     * @param current 当前模型配置（含当前 apiKey）
     * @param provider 当前 Provider 名称
     * @return 轮换后的配置；无可用备选凭据时返回 empty
     */
    Optional<ModelConfig> rotate(ModelConfig current, String provider);
}
