package cn.zcj.aether.domain.agent.service.model.failover;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 凭据池播种器 — 把 ai-api 配置映射为 CredentialPool 的凭据组并播种。
 * 主 api-key 恒为首项，保证 rotate 按 apiKey 命中当前凭据；credentials 列表按 apiKey 去重追加。
 */
public final class CredentialPoolSeeder {

    private CredentialPoolSeeder() {
    }

    /** 从 ai-api 配置播种凭据池。pool/provider/aiApi 为空或 credentials 未配置时静默跳过。 */
    public static void seed(CredentialPool pool, String provider, AiAgentConfigTableVO.Module.AiApi aiApi) {
        if (pool == null || provider == null || aiApi == null || aiApi.getCredentials() == null) {
            return;
        }
        List<CredentialPool.CredentialEntry> entries = buildEntries(aiApi);
        if (entries.isEmpty()) {
            return;
        }
        pool.seed(provider, entries);
    }

    private static List<CredentialPool.CredentialEntry> buildEntries(AiAgentConfigTableVO.Module.AiApi aiApi) {
        List<CredentialPool.CredentialEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (aiApi.getApiKey() != null && !aiApi.getApiKey().isBlank()) {
            seen.add(aiApi.getApiKey());
            entries.add(new CredentialPool.CredentialEntry(
                    aiApi.getApiKey(), aiApi.getBaseUrl(), aiApi.getCompletionsPath()));
        }
        if (aiApi.getCredentials() != null) {
            for (AiAgentConfigTableVO.Module.AiApi.Credential c : aiApi.getCredentials()) {
                if (c == null || c.getApiKey() == null || c.getApiKey().isBlank()) {
                    continue;
                }
                if (!seen.add(c.getApiKey())) {
                    continue; // 与主 key 或已追加项重复，跳过
                }
                entries.add(new CredentialPool.CredentialEntry(
                        c.getApiKey(),
                        c.getBaseUrl() != null ? c.getBaseUrl() : aiApi.getBaseUrl(),
                        c.getCompletionsPath() != null ? c.getCompletionsPath() : aiApi.getCompletionsPath()));
            }
        }
        return entries;
    }
}
