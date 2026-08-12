package cn.zcj.aether.domain.agent.service.model.failover;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;

import java.util.Optional;

/**
 * 凭据轮换端口 — 对齐 hermes agent_runtime_helpers.py recover_with_credential_pool。
 *
 * <p>当错误分类标记 shouldRotateCredential（AUTH_TRANSIENT / BILLING）时，
 * ResilientChatModelExecutor 调用本接口获取下一个可用凭据并重建 ChatModel。</p>
 */
public interface CredentialPool {

    /**
     * 为当前模型配置返回下一组可用凭据。
     *
     * @param current 当前模型配置（含当前 apiKey）
     * @param provider 当前 Provider 名称
     * @return 轮换后的配置；无可用备选凭据时返回 empty
     */
    Optional<ModelConfig> rotate(ModelConfig current, String provider);
}
