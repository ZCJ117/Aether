package cn.zcj.aether.domain.agent.service.model.failover;

import cn.zcj.aether.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialPoolSeederTest {

    @Test
    void skipsWhenCredentialsNull() {
        CredentialPool pool = mock(CredentialPool.class);
        AiAgentConfigTableVO.Module.AiApi aiApi = new AiAgentConfigTableVO.Module.AiApi();
        aiApi.setApiKey("primary");
        aiApi.setBaseUrl("https://a");
        aiApi.setCredentials(null);

        CredentialPoolSeeder.seed(pool, "openai", aiApi);

        verify(pool, never()).seed(any(), any());
    }

    @Test
    void prependsPrimaryAndDedups() {
        CredentialPool pool = mock(CredentialPool.class);
        AiAgentConfigTableVO.Module.AiApi aiApi = new AiAgentConfigTableVO.Module.AiApi();
        aiApi.setApiKey("primary");
        aiApi.setBaseUrl("https://a");
        aiApi.setCompletionsPath("/v1/chat/completions");

        AiAgentConfigTableVO.Module.AiApi.Credential dup = new AiAgentConfigTableVO.Module.AiApi.Credential();
        dup.setApiKey("primary"); // 与主 key 重复
        AiAgentConfigTableVO.Module.AiApi.Credential backup = new AiAgentConfigTableVO.Module.AiApi.Credential();
        backup.setApiKey("backup");
        backup.setBaseUrl("https://b");
        aiApi.setCredentials(List.of(dup, backup));

        CredentialPoolSeeder.seed(pool, "openai", aiApi);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CredentialPool.CredentialEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(pool).seed(eq("openai"), captor.capture());
        List<CredentialPool.CredentialEntry> entries = captor.getValue();
        assertEquals(2, entries.size());
        assertEquals("primary", entries.get(0).apiKey());
        assertEquals("https://a", entries.get(0).baseUrl());
        assertEquals("backup", entries.get(1).apiKey());
        assertEquals("https://b", entries.get(1).baseUrl());
    }

    @Test
    void nullPoolIsNoop() {
        AiAgentConfigTableVO.Module.AiApi aiApi = new AiAgentConfigTableVO.Module.AiApi();
        aiApi.setApiKey("primary");
        CredentialPoolSeeder.seed(null, "openai", aiApi); // 不应抛异常
    }
}
