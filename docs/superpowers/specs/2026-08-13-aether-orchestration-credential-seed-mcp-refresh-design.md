# 编排残留项收尾设计（D2 凭据池生产播种 + D3 MCP 手动刷新）

日期：2026-08-13
分支：`feat/orchestration-hermes-alignment`
范围：`aether-domain` + `aether-infrastructure` + `aether-trigger` 三个模块，`hermes-agent-main/` 零修改

## 背景

四维度编排对齐（D2 异常重试 / D3 可扩展配置 / D1 多Agent协作 / D4 可视化调试）已在 Batch 1-4 全部交付。但原始总设计文档中仍有两条「能力已建、未接生产」的残留项，本设计一次性收尾：

- **D2 凭据池生产播种未做** — `CredentialPool`（domain 接口）+ `RotatingCredentialPool`（infra 实现）已建，`ChatModelNode.wrapWithFailover` 也已 `setCredentialPool(...)` 注入，但 `register()` 从未被调用，池永远为空，`rotate()` 恒返回 `empty`，`AUTH_TRANSIENT`/`BILLING` 时退化为 fallback 而非同-provider 轮换。
- **D3 MCP 运行时刷新无触发器** — `McpToolRegistry.refreshTools(serverId, rebuilder)` 能力已建，`ChatModelNode` 装配时也 `register(serverId, specs)`，但无人调 `refreshTools`。设计 §5.3⑦ 原文「先调研 SSE/stdio 是否暴露 `tools/list_changed`；可接入则自动触发，否则降级为手动 `POST /api/mcp/refresh`」。

## 关键决策（用户已确认）

1. **D2 播种源**：新增 `ai-api.credentials` 列表配置（而非复用 fallback 链、非独立 properties）。
2. **D3 触发器**：仅手动端点 `POST /api/mcp/refresh`（sync MCP 客户端不暴露 `tools/list_changed` notification，自动监听需重构异步客户端，违反 YAGNI）。
3. **D3 刷新语义**：仅刷新 `McpToolRegistry` 的 `ToolSpec` 元数据快照（供 `isToolParallelSafe`/溯源/观测），**不**热替换已构建 ChatModel 的工具回调。

## Part A — D2 凭据池生产播种

### A1 配置（schema + VO）

`agent-config.schema.json` 的 `ai-api` 节点新增 `credentials` 数组，沿用现有 kebab-case 约定：

```yaml
ai-api:
  base-url: https://api.deepseek.com
  api-key: ${DEEPSEEK_API_KEY:}
  credentials:                       # 新增：同 provider 的备用凭据池（可选，缺省则维持现「空池退化 fallback」）
    - api-key: ${DEEPSEEK_API_KEY_BACKUP:}
      base-url: https://api.deepseek.com
      completions-path: v1/chat/completions
```

`AiAgentConfigTableVO.Module.AiApi` 增加：

```java
private List<Credential> credentials;

@Data
public static class Credential {
    private String apiKey;
    private String baseUrl;
    private String completionsPath;
}
```

### A2 domain 接口

`CredentialPool`（domain `model/failover`）增加播种能力：

```java
void seed(String provider, List<CredentialEntry> entries);

record CredentialEntry(String apiKey, String baseUrl, String completionsPath) {}
```

`RotatingCredentialPool`（infra）把现有 `register` 改为 `@Override seed`，删除其自有嵌套 `CredentialEntry`，复用 domain 版。

### A3 播种器（domain，纯函数可测）

新增 `CredentialPoolSeeder`（domain `model/failover`），单静态方法：

```java
static void seed(CredentialPool pool, String provider, AiAgentConfigTableVO.Module.AiApi aiApi)
```

逻辑：
1. `pool == null` 或 `aiApi == null` 或 `credentials == null` → 静默跳过。
2. 构造 entries：**主 `api-key` 作为首项**，随后追加 `credentials` 列表（按 `apiKey` 去重，跳过与主 key 重复的项）。
3. `pool.seed(provider, entries)`。

「主 key 首项」保证 `RotatingCredentialPool.rotate()` 里的 `indexOf(current.getApiKey())` 能命中当前凭据，从而轮换到下一组。

### A4 接线

`ChatModelNode.doApply` 在 `wrapWithFailover` 前调用一次：

```java
CredentialPoolSeeder.seed(credentialPool, provider.providerName(), aiAgentConfigTableVO.getModule().getAiApi());
```

幂等（重复装配 `seed` 覆盖）。

## Part B — D3 MCP 手动刷新端点

### B1 domain 接口扩展

`McpToolRegistry`（domain）增加：

```java
void register(String serverId, List<ToolSpec> tools, Supplier<List<ToolSpec>> rebuilder); // 重载
RefreshResult refresh(String serverId);      // 用已存 rebuilder 调 refreshTools
List<String> serverIds();                    // 供 refresh-all
```

`DefaultMcpToolRegistry` 增加 `Map<serverId, Supplier<List<ToolSpec>>> rebuilders`，`refresh(serverId)` 委托 `refreshTools(serverId, rebuilder)`。现有 `refreshTools` 的 try/catch「失败保留旧快照」语义不变。

### B2 ChatModelNode 注册 rebuilder

装配时（现 `mcpToolRegistry.register(serverId, specs)` 处）改调重载，额外注册「重连重拉」lambda：

```java
() -> {
    TooMcpCreateService svc = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
    ToolCallback[] built = svc.buildToolCallback(toolMcp);
    return toSpecs(built, parallelSafe);
}
```

重连重建（而非保留 `McpSyncClient` 引用）是最小改动；手动刷新是低频操作，重连开销可接受。`serverId = extractMcpName(toolMcp)`（MCP server `name` 字段）。`toSpecs` 抽取自现有 `register` 前的一段「ToolCallback[] → List\<ToolSpec\>」转换逻辑，避免重复。

### B3 trigger 端点

新增 `McpRefreshController`（aether-trigger）：

```
POST /api/mcp/refresh   body 可选 {"serverId": "..."}  → 刷单个；缺省刷全部
```

返回 `Response<Map<String, RefreshResult>>`（serverId → added/removed），复用 `McpToolRegistry.RefreshResult`。注入 `McpToolRegistry`（domain `@Component`，trigger 已依赖 domain）。

## 错误处理

- A：`credentials` 未配置 → 跳过，维持空池退化 fallback；`rotate` 无备选仍返回 `empty`。
- B：`rebuilder.get()` 抛异常 → `refreshTools` 已 try/catch 保留旧快照并返回空 RefreshResult；未知 serverId → `refresh` 返回空 RefreshResult。

## 非目标（明确不做）

- 不做 `tools/list_changed` 自动监听（sync 客户端不支持，重构异步客户端违反 YAGNI）。
- 不热替换 ChatModel 工具回调（仅刷新 registry 元数据）。
- 不动 `ModelInvoker`/`AgentEventPublisher`/`AgentTracer` 公共签名。
- 不处理 `task.task()==null` 等既有 NPE 面。
- `hermes-agent-main/` 零修改。

## 测试（JUnit5 + Mockito）

1. **RotatingCredentialPoolTest**（改现有）：`seed` 后主 key + 备用 → `rotate` 返回备用；单组 → `empty`；当前 key 不在池中 → `empty`。
2. **CredentialPoolSeederTest**（新增，domain）：主 key 首项、按 apiKey 去重、空配置跳过。
3. **DefaultMcpToolRegistryTest**（改现有）：`register(…, rebuilder)` 后 `refresh(serverId)` 触发 diff，`added`/`removed` 正确；rebuilder 抛异常 → 保留旧快照。
4. **McpRefreshControllerTest**（新增，trigger，沿用 `OrchestrationControllerTest` 模式）：mock registry，验证 `POST /api/mcp/refresh`（带/不带 serverId）返回 added/removed。

## 验收

- domain + infrastructure + trigger 三模块相关测试全绿（`mvn -pl aether-domain -am test -Dsurefire.failIfNoSpecifiedTests=false` 及 infrastructure/trigger 对应命令）。
- `hermes-agent-main/` 零修改。
