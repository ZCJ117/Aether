# D2 凭据池生产播种 + D3 MCP 手动刷新 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 D2 凭据池在生产装配时被真实播种（同-provider 轮换），并为 D3 的 `refreshTools` 能力挂上手动 `POST /api/mcp/refresh` 触发器。

**Architecture:** 两部分残留收尾。Part A：`CredentialPool` 接口加 `seed` + `CredentialEntry`，`RotatingCredentialPool` 实现，新增 `CredentialPoolSeeder` 把 `ai-api.credentials` 配置映射成凭据组，`ChatModelNode` 装配时播种。Part B：`McpToolRegistry` 加 `register(…, rebuilder)` 重载 + `refresh(serverId)` + `serverIds()`，`ChatModelNode` 注册「重连重拉」rebuilder，新增 `McpRefreshController` 手动触发。

**Tech Stack:** Java 17 + Spring Boot + Lombok + JUnit5 + Mockito + Maven 多模块（aether-domain / aether-infrastructure / aether-trigger）。

**模块依赖约束（重要）**：domain 不能依赖 infrastructure；infrastructure 依赖 domain；trigger 依赖 domain + api。`hermes-agent-main/` 零修改。

---

## 任务依赖顺序

```
Task 1 (CredentialPool seed 契约+实现) ─┐
Task 2 (AiApi.credentials + Seeder)   ─┼─▶ Task 4 (ChatModelNode 接线)
Task 3 (McpToolRegistry 扩展)         ─┘        │
                                               ▼
                                      Task 5 (McpRefreshController)
```

---

### Task 1: 凭据池播种契约 + 实现

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/CredentialPool.java`
- Modify: `aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/credential/RotatingCredentialPool.java`
- Modify: `aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/credential/RotatingCredentialPoolTest.java`

- [ ] **Step 1: 写失败测试**

把 `RotatingCredentialPoolTest` 改为调用 `seed` 而非 `register`，并把 `RotatingCredentialPool.CredentialEntry` 换成 domain 的 `CredentialPool.CredentialEntry`。全量替换文件内容为：

```java
package cn.zcj.aether.infrastructure.credential;

import cn.zcj.aether.domain.agent.service.model.ModelConfig;
import cn.zcj.aether.domain.agent.service.model.failover.CredentialPool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotatingCredentialPoolTest {

    @Test
    void rotatesToNextCredential() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.seed("openai", List.of(
                new CredentialPool.CredentialEntry("key1", "https://a", null),
                new CredentialPool.CredentialEntry("key2", "https://b", null)));

        ModelConfig current = ModelConfig.builder()
                .modelId("gpt-4o").apiKey("key1").baseUrl("https://a").build();

        ModelConfig next = pool.rotate(current, "openai").orElseThrow();
        assertEquals("key2", next.getApiKey());
        assertEquals("gpt-4o", next.getModelId());
        assertEquals("https://b", next.getBaseUrl());
    }

    @Test
    void emptyWhenOnlyOneCredential() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.seed("openai", List.of(
                new CredentialPool.CredentialEntry("key1", "https://a", null)));

        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build();
        assertTrue(pool.rotate(current, "openai").isEmpty());
    }

    @Test
    void emptyWhenKeyUnknown() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.seed("openai", List.of(
                new CredentialPool.CredentialEntry("key1", "https://a", null)));

        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("unknown").build();
        assertTrue(pool.rotate(current, "openai").isEmpty());
    }

    @Test
    void emptyWhenProviderUnknown() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("key1").build();
        assertTrue(pool.rotate(current, "unknown-provider").isEmpty());
    }

    @Test
    void seedOverwritesPreviousPool() {
        RotatingCredentialPool pool = new RotatingCredentialPool();
        pool.seed("openai", List.of(
                new CredentialPool.CredentialEntry("key1", "https://a", null),
                new CredentialPool.CredentialEntry("key2", "https://b", null)));
        pool.seed("openai", List.of(
                new CredentialPool.CredentialEntry("key3", "https://c", null)));

        ModelConfig current = ModelConfig.builder().modelId("gpt-4o").apiKey("key3").build();
        assertTrue(pool.rotate(current, "openai").isEmpty()); // 重播种后只剩 1 组，无法轮换
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /d/code/Agents-framework/aether && mvn -pl aether-infrastructure -am test -Dtest=RotatingCredentialPoolTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL — 编译错误，`seed` 方法不存在、`CredentialPool.CredentialEntry` 不存在。

- [ ] **Step 3: 改 `CredentialPool` 接口**

全量替换 `CredentialPool.java` 内容为：

```java
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
```

- [ ] **Step 4: 改 `RotatingCredentialPool` 实现**

全量替换 `RotatingCredentialPool.java` 内容为（删除自有嵌套 `CredentialEntry`，复用 domain 版，`register` 改 `seed`）：

```java
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
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd /d/code/Agents-framework/aether && mvn -pl aether-infrastructure -am test -Dtest=RotatingCredentialPoolTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（5 个用例全绿）。

- [ ] **Step 6: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/CredentialPool.java \
        aether-infrastructure/src/main/java/cn/zcj/aether/infrastructure/credential/RotatingCredentialPool.java \
        aether-infrastructure/src/test/java/cn/zcj/aether/infrastructure/credential/RotatingCredentialPoolTest.java
git commit -m "feat(d2): CredentialPool 加 seed 契约，RotatingCredentialPool 实现播种"
```

---

### Task 2: 配置字段 + schema + 播种器

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/model/valobj/AiAgentConfigTableVO.java`（`AiApi` 类，约 82-94 行）
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/CredentialPoolSeeder.java`
- Create: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/CredentialPoolSeederTest.java`
- Modify: `docs/schema/agent-config.schema.json`（`ai-api` 节点，约 41-50 行）

- [ ] **Step 1: 写失败测试**

创建 `CredentialPoolSeederTest.java`：

```java
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /d/code/Agents-framework/aether && mvn -pl aether-domain -am test -Dtest=CredentialPoolSeederTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL — `CredentialPoolSeeder` 不存在、`AiApi.getCredentials/setCredentials` 不存在。

- [ ] **Step 3: 加 `AiApi.credentials` 字段 + 嵌套类**

在 `AiAgentConfigTableVO.java` 的 `AiApi` 类中，`embeddingsPath` 字段后、类收尾 `}` 前，插入：

```java
            /** D2: 同 provider 的备用凭据池（可选；缺省则维持空池退化 fallback） */
            private List<Credential> credentials;

            @Data
            public static class Credential {
                private String apiKey;
                private String baseUrl;
                private String completionsPath;
            }
```

（`List` 已在该文件 import，无需新增。`@Data` 为 lombok，该文件已用。）

- [ ] **Step 4: 创建 `CredentialPoolSeeder`**

```java
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
        if (pool == null || provider == null || aiApi == null) {
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
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd /d/code/Agents-framework/aether && mvn -pl aether-domain -am test -Dtest=CredentialPoolSeederTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（3 个用例全绿）。

- [ ] **Step 6: 更新 schema 文档**

在 `agent-config.schema.json` 的 `ai-api.properties` 对象里，`embeddings-path` 行后加：

```json
                "credentials": {
                  "type": "array",
                  "description": "同 provider 的备用凭据池（可选）",
                  "items": {
                    "type": "object",
                    "properties": {
                      "api-key": { "type": "string", "description": "备用 API 密钥（支持${ENV_VAR}）" },
                      "base-url": { "type": "string", "format": "uri" },
                      "completions-path": { "type": "string" }
                    }
                  }
                }
```

（注意 `embeddings-path` 行末尾原本无逗号，需在插入前给该行末尾加逗号。）

- [ ] **Step 7: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/model/valobj/AiAgentConfigTableVO.java \
        aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/model/failover/CredentialPoolSeeder.java \
        aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/model/failover/CredentialPoolSeederTest.java \
        docs/schema/agent-config.schema.json
git commit -m "feat(d2): ai-api.credentials 配置 + CredentialPoolSeeder 播种器"
```

---

### Task 3: McpToolRegistry 接口 + DefaultMcpToolRegistry 扩展

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/armory/matter/mcp/registry/McpToolRegistry.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/armory/matter/mcp/registry/DefaultMcpToolRegistry.java`
- Modify: `aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/armory/matter/mcp/registry/DefaultMcpToolRegistryTest.java`

- [ ] **Step 1: 写失败测试**

在 `DefaultMcpToolRegistryTest.java` 末尾（`refreshRebuilderFailureIsSwallowed` 之后、收尾 `}` 之前）追加 3 个用例：

```java
    @Test
    void registerWithRebuilderAndRefreshDiff() {
        registry.register("server-a", List.of(new ToolSpec("tool1", "d", false)),
                () -> List.of(new ToolSpec("tool1", "d", true), new ToolSpec("tool2", "d2", false)));

        McpToolRegistry.RefreshResult result = registry.refresh("server-a");

        assertEquals(List.of("tool2"), result.added());
        assertEquals(2, registry.getTools("server-a").size());
        assertTrue(registry.isToolParallelSafe("tool1"));
        assertFalse(registry.isToolParallelSafe("tool2"));
    }

    @Test
    void refreshUnknownServerReturnsEmpty() {
        McpToolRegistry.RefreshResult result = registry.refresh("missing");
        assertTrue(result.added().isEmpty());
        assertTrue(result.removed().isEmpty());
    }

    @Test
    void serverIdsListsRegisteredServers() {
        registry.register("a", List.of(new ToolSpec("t", "d", false)));
        registry.register("b", List.of(new ToolSpec("t2", "d", false)));
        assertEquals(2, registry.serverIds().size());
        assertTrue(registry.serverIds().contains("a"));
        assertTrue(registry.serverIds().contains("b"));
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /d/code/Agents-framework/aether && mvn -pl aether-domain -am test -Dtest=DefaultMcpToolRegistryTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL — `register(serverId, tools, rebuilder)`、`refresh`、`serverIds` 方法不存在。

- [ ] **Step 3: 改 `McpToolRegistry` 接口**

全量替换 `McpToolRegistry.java` 内容为：

```java
package cn.zcj.aether.domain.agent.service.armory.matter.mcp.registry;

import java.util.List;
import java.util.function.Supplier;

/**
 * MCP 工具运行时注册表（domain 端口）— 对齐 hermes mcp_tool.py。
 * 跟踪每 server 的工具快照 + 工具→server 精确溯源（_mcp_tool_server_names）。
 */
public interface McpToolRegistry {

    /** 登记某 MCP server 的工具快照 */
    void register(String serverId, List<ToolSpec> tools);

    /**
     * 登记某 MCP server 的工具快照，并附带运行时刷新 rebuilder（重新拉取 tools/list）。
     * 供手动 POST /api/mcp/refresh 触发 {@link #refresh(String)}。
     */
    void register(String serverId, List<ToolSpec> tools, Supplier<List<ToolSpec>> rebuilder);

    /** 查询某 MCP server 的工具快照 */
    List<ToolSpec> getTools(String serverId);

    /**
     * 并行安全判定（对齐 hermes is_mcp_tool_parallel_safe L5816）。
     * 用注册时捕获的精确溯源查集合，绝不按工具名前缀拆分 server 名。
     */
    boolean isToolParallelSafe(String toolName);

    /**
     * 刷新某 MCP server 工具集（对齐 hermes _refresh_tools L2075）。
     * 拉全量 → diff 增量更新（避免 nuke-and-repave 的 stale-handler 竞态），
     * 快照 in-place 替换，返回新增/移除的工具名。
     */
    RefreshResult refreshTools(String serverId, Supplier<List<ToolSpec>> rebuilder);

    /** 用已登记的 rebuilder 刷新某 MCP server 工具集（手动触发）。未登记 rebuilder 时返回空结果。 */
    RefreshResult refresh(String serverId);

    /** 已登记的所有 serverId（供 refresh-all） */
    List<String> serverIds();

    /** 刷新结果：新增/移除的工具名 */
    record RefreshResult(List<String> added, List<String> removed) {
    }
}
```

- [ ] **Step 4: 改 `DefaultMcpToolRegistry` 实现**

全量替换 `DefaultMcpToolRegistry.java` 内容为：

```java
package cn.zcj.aether.domain.agent.service.armory.matter.mcp.registry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 内存版 MCP 工具注册表（对齐 hermes mcp_tool.py _parallel_safe_servers / _mcp_tool_server_names）。
 */
@Slf4j
@Component
public class DefaultMcpToolRegistry implements McpToolRegistry {

    /** serverId → 工具快照 */
    private final Map<String, List<ToolSpec>> toolsByServer = new ConcurrentHashMap<>();
    /** toolName → serverId（注册时精确捕获的 provenance） */
    private final Map<String, String> serverByToolName = new ConcurrentHashMap<>();
    /** serverId → 运行时刷新 rebuilder（重新拉取 tools/list） */
    private final Map<String, Supplier<List<ToolSpec>>> rebuilders = new ConcurrentHashMap<>();

    @Override
    public void register(String serverId, List<ToolSpec> tools) {
        for (ToolSpec t : tools) {
            serverByToolName.put(t.name(), serverId);
        }
        toolsByServer.put(serverId, List.copyOf(tools));
        log.info("MCP 工具已登记: server={} tools={}", serverId, tools.size());
    }

    @Override
    public void register(String serverId, List<ToolSpec> tools, Supplier<List<ToolSpec>> rebuilder) {
        register(serverId, tools);
        if (rebuilder != null) {
            rebuilders.put(serverId, rebuilder);
        }
    }

    @Override
    public List<ToolSpec> getTools(String serverId) {
        return toolsByServer.getOrDefault(serverId, List.of());
    }

    @Override
    public boolean isToolParallelSafe(String toolName) {
        String serverId = serverByToolName.get(toolName);
        if (serverId == null) {
            return false;
        }
        return toolsByServer.getOrDefault(serverId, List.of()).stream()
                .filter(t -> t.name().equals(toolName))
                .findFirst()
                .map(ToolSpec::parallelSafe)
                .orElse(false);
    }

    @Override
    public RefreshResult refreshTools(String serverId, Supplier<List<ToolSpec>> rebuilder) {
        List<ToolSpec> oldTools = toolsByServer.getOrDefault(serverId, List.of());
        Set<String> oldNames = oldTools.stream().map(ToolSpec::name).collect(Collectors.toSet());

        List<ToolSpec> newTools;
        try {
            newTools = rebuilder.get();
        } catch (Exception e) {
            log.warn("MCP 刷新失败（保留旧快照）: server={} err={}", serverId, e.getMessage());
            return new RefreshResult(List.of(), List.of());
        }
        if (newTools == null) {
            log.warn("MCP 刷新返回空，保留旧快照: server={}", serverId);
            return new RefreshResult(List.of(), List.of());
        }

        Set<String> newNames = newTools.stream().map(ToolSpec::name).collect(Collectors.toSet());
        List<String> added = new ArrayList<>(newNames);
        added.removeAll(oldNames);
        List<String> removed = new ArrayList<>(oldNames);
        removed.removeAll(newNames);

        toolsByServer.put(serverId, List.copyOf(newTools));
        for (String name : removed) {
            serverByToolName.remove(name);
        }
        for (ToolSpec t : newTools) {
            serverByToolName.put(t.name(), serverId);
        }

        if (!added.isEmpty() || !removed.isEmpty()) {
            log.warn("MCP 工具动态变更（需人工确认）: server={} +{} -{}", serverId, added, removed);
        } else {
            log.info("MCP 工具无变更: server={}", serverId);
        }
        return new RefreshResult(added, removed);
    }

    @Override
    public RefreshResult refresh(String serverId) {
        Supplier<List<ToolSpec>> rebuilder = rebuilders.get(serverId);
        if (rebuilder == null) {
            log.warn("MCP 刷新：server 未登记 rebuilder: server={}", serverId);
            return new RefreshResult(List.of(), List.of());
        }
        return refreshTools(serverId, rebuilder);
    }

    @Override
    public List<String> serverIds() {
        return new ArrayList<>(toolsByServer.keySet());
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cd /d/code/Agents-framework/aether && mvn -pl aether-domain -am test -Dtest=DefaultMcpToolRegistryTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（8 个用例全绿，含新增 3 个）。

- [ ] **Step 6: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/armory/matter/mcp/registry/McpToolRegistry.java \
        aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/armory/matter/mcp/registry/DefaultMcpToolRegistry.java \
        aether-domain/src/test/java/cn/zcj/aether/domain/agent/service/armory/matter/mcp/registry/DefaultMcpToolRegistryTest.java
git commit -m "feat(d3): McpToolRegistry 加 register(rebuilder) 重载 + refresh + serverIds"
```

---

### Task 4: ChatModelNode 接线（seed + toSpecs 提取 + rebuilder 注册）

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/armory/node/ChatModelNode.java`

- [ ] **Step 1: 加 import**

在 import 区 `import cn.zcj.aether.domain.agent.service.model.failover.CredentialPool;`（第 16 行）之后加：

```java
import cn.zcj.aether.domain.agent.service.model.failover.CredentialPoolSeeder;
```

- [ ] **Step 2: 加播种调用**

在 `doApply` 中 `ModelConfig modelConfig = dynamicContext.getModelConfig();`（约第 172 行）之后加：

```java
        // D2: 生产播种凭据池（credentials 未配置时静默跳过，维持空池退化 fallback）
        CredentialPoolSeeder.seed(credentialPool, provider.providerName(),
                aiAgentConfigTableVO.getModule().getAiApi());
```

- [ ] **Step 3: 抽 `toSpecs` + 注册 rebuilder**

把 `doApply` 里的 MCP 登记块（约 136-151 行）整体替换为：

```java
                // D3: 登记 MCP 工具元数据到运行时注册表（供 isToolParallelSafe / refreshTools 使用）
                if (mcpToolRegistry != null) {
                    String serverId = extractMcpName(toolMcp);
                    boolean parallelSafe = Boolean.TRUE.equals(toolMcp.getParallelSafe());
                    List<ToolSpec> specs = toSpecs(toolCallbacks, parallelSafe);
                    // 运行时刷新 rebuilder：重连 MCP server 重新拉取 tools/list（手动 POST /api/mcp/refresh 触发）
                    mcpToolRegistry.register(serverId, specs, () -> {
                        try {
                            TooMcpCreateService svc = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
                            ToolCallback[] rebuilt = svc.buildToolCallback(toolMcp);
                            return toSpecs(rebuilt, parallelSafe);
                        } catch (Exception e) {
                            throw new RuntimeException("MCP 工具重拉失败: server=" + serverId, e);
                        }
                    });
                    log.info("MCP 工具已登记到运行时注册表: server={} tools={}", serverId, specs.size());
                }
```

- [ ] **Step 4: 加 `toSpecs` 辅助方法**

在 `extractMcpName` 方法（约第 370 行）之前插入：

```java
    /** 把 MCP ToolCallback[] 转为 ToolSpec 元数据快照 */
    private List<ToolSpec> toSpecs(ToolCallback[] toolCallbacks, boolean parallelSafe) {
        List<ToolSpec> specs = new ArrayList<>();
        for (ToolCallback tc : toolCallbacks) {
            String toolName = tc.getToolDefinition() != null ? tc.getToolDefinition().name() : null;
            String desc = tc.getToolDefinition() != null && tc.getToolDefinition().description() != null
                    ? tc.getToolDefinition().description() : "";
            if (toolName != null) {
                specs.add(new ToolSpec(toolName, desc, parallelSafe));
            }
        }
        return specs;
    }
```

- [ ] **Step 5: 编译 + 回归验证**

Run: `cd /d/code/Agents-framework/aether && mvn -pl aether-domain -am test -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（domain 全量测试绿，无回归）。

- [ ] **Step 6: 提交**

```bash
git add aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/armory/node/ChatModelNode.java
git commit -m "feat(d2/d3): ChatModelNode 接线凭据播种 + MCP 刷新 rebuilder"
```

---

### Task 5: McpRefreshController + 测试

**Files:**
- Create: `aether-trigger/src/main/java/cn/zcj/aether/trigger/http/McpRefreshController.java`
- Create: `aether-trigger/src/test/java/cn/zcj/aether/trigger/http/McpRefreshControllerTest.java`

- [ ] **Step 1: 写失败测试**

创建 `McpRefreshControllerTest.java`：

```java
package cn.zcj.aether.trigger.http;

import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.registry.McpToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpRefreshControllerTest {

    private McpToolRegistry registry;
    private McpRefreshController controller;

    @BeforeEach
    void setUp() {
        registry = mock(McpToolRegistry.class);
        controller = new McpRefreshController();
        ReflectionTestUtils.setField(controller, "mcpToolRegistry", registry);
    }

    @Test
    void refreshWithServerIdDelegates() {
        when(registry.refresh("srv-a")).thenReturn(new McpToolRegistry.RefreshResult(List.of("t2"), List.of()));

        Response<Map<String, McpToolRegistry.RefreshResult>> resp = controller.refresh(Map.of("serverId", "srv-a"));

        assertNotNull(resp.getData());
        assertTrue(resp.getData().containsKey("srv-a"));
        verify(registry).refresh("srv-a");
    }

    @Test
    void refreshWithNullBodyRefreshesAll() {
        when(registry.serverIds()).thenReturn(List.of("a", "b"));
        when(registry.refresh(anyString())).thenReturn(new McpToolRegistry.RefreshResult(List.of(), List.of()));

        Response<Map<String, McpToolRegistry.RefreshResult>> resp = controller.refresh(null);

        assertEquals(2, resp.getData().size());
        verify(registry, times(2)).refresh(anyString());
    }

    @Test
    void refreshSwallowsExceptionReturnsError() {
        when(registry.refresh(anyString())).thenThrow(new RuntimeException("boom"));

        Response<Map<String, McpToolRegistry.RefreshResult>> resp = controller.refresh(Map.of("serverId", "srv-a"));

        assertNull(resp.getData());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /d/code/Agents-framework/aether && mvn -pl aether-trigger -am test -Dtest=McpRefreshControllerTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: FAIL — `McpRefreshController` 不存在。

- [ ] **Step 3: 创建 `McpRefreshController`**

```java
package cn.zcj.aether.trigger.http;

import cn.zcj.aether.api.response.Response;
import cn.zcj.aether.domain.agent.service.armory.matter.mcp.registry.McpToolRegistry;
import cn.zcj.aether.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具手动刷新端点 — 对齐 hermes mcp_tool.py _refresh_tools 的降级触发路径。
 * <pre>
 * POST /api/mcp/refresh   body 可选 {"serverId": "..."}  → 刷单个；缺省刷全部
 * </pre>
 * 仅刷新 registry 的 ToolSpec 元数据快照（供 isToolParallelSafe / 溯源 / 观测），
 * 不热替换已构建 ChatModel 的工具回调。
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpRefreshController {

    @Resource
    private McpToolRegistry mcpToolRegistry;

    @PostMapping("refresh")
    public Response<Map<String, McpToolRegistry.RefreshResult>> refresh(
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            String serverId = body != null ? (String) body.get("serverId") : null;
            Map<String, McpToolRegistry.RefreshResult> results = new LinkedHashMap<>();
            if (serverId != null && !serverId.isBlank()) {
                results.put(serverId, mcpToolRegistry.refresh(serverId));
            } else {
                for (String id : mcpToolRegistry.serverIds()) {
                    results.put(id, mcpToolRegistry.refresh(id));
                }
            }
            return Response.<Map<String, McpToolRegistry.RefreshResult>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(results)
                    .build();
        } catch (Exception e) {
            log.error("MCP 工具刷新失败 serverId={}", body != null ? body.get("serverId") : null, e);
            return Response.<Map<String, McpToolRegistry.RefreshResult>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /d/code/Agents-framework/aether && mvn -pl aether-trigger -am test -Dtest=McpRefreshControllerTest -Dsurefire.failIfNoSpecifiedTests=false -q`
Expected: PASS（3 个用例全绿）。

- [ ] **Step 5: 提交**

```bash
git add aether-trigger/src/main/java/cn/zcj/aether/trigger/http/McpRefreshController.java \
        aether-trigger/src/test/java/cn/zcj/aether/trigger/http/McpRefreshControllerTest.java
git commit -m "feat(d3): McpRefreshController 手动 POST /api/mcp/refresh 端点"
```

---

## 最终验收（所有任务完成后）

```bash
cd /d/code/Agents-framework/aether
mvn -pl aether-domain -am test -Dsurefire.failIfNoSpecifiedTests=false -q
mvn -pl aether-infrastructure -am test -Dsurefire.failIfNoSpecifiedTests=false -q
mvn -pl aether-trigger -am test -Dsurefire.failIfNoSpecifiedTests=false -q
```

预期：三模块全绿。确认 `hermes-agent-main/` 无任何改动（`git status` 无该目录变更）。

## 自审记录

- **Spec 覆盖**：A1(schema+VO)→Task 2；A2(接口)→Task 1；A3(Seeder)→Task 2；A4(接线)→Task 4；B1(接口)→Task 3；B2(rebuilder)→Task 4；B3(端点)→Task 5。全覆盖。
- **占位符扫描**：无 TBD/TODO，每步含完整代码。
- **类型一致性**：`CredentialPool.CredentialEntry`（Task 1 定义）在 Task 2/4 中引用一致；`McpToolRegistry.refresh/serverIds`（Task 3 定义）在 Task 5 中引用一致；`toSpecs`（Task 4 定义）在同任务内使用一致。
