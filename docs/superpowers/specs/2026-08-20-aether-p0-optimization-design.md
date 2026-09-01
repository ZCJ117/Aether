# aether P0 优化设计（O7 / O12 / O14 / O16）

> **日期**：2026-08-20
> **分支**：`feat/p0-fixes`
> **依据**：`docs/analysis/03-aether-优化方案.md` 的 **P0（必须修复）** 部分（O7、O12、O14、O16）。
> **参考实现**：`hermes-agent-main/`（harness-agent），仅作代码对照，**不可修改**。

## 1. 背景与范围

《03-aether-优化方案》将缺陷 D1–D19 映射为优化项 O1–O19，其中 P0（必须修复）为四项：

| 编号 | 主题 | 核心问题 |
|------|------|----------|
| O7 | 上下文压缩 LLM 调用绕过重试/成本/预算管线 | `ContextManager.generateSummary` / `ChunkSummarizer.callLlm` 直调 `ChatModel.call()`，不计入 `TokenBudget` 成本熔断、无冷却 |
| O12 | Anthropic/DashScope 超时配置绕过 | 两 Provider 直接 `new OpenAiApi.builder()`，跳过统一超时工厂 `buildOpenAiApi` |
| O14 | 权限引擎 BYPASS 跳过硬封锁 | `PermissionEngine.check` BYPASS 分支直接返回 ALLOW，`DangerousToolRule` 12 条硬封锁失效；规则异常静默吞 |
| O16 | 生产配置不完整 + 硬编码默认凭据 | prod yml 无 DB/JWT/session/ssrf/otel；`DataInitializer` 建 admin/admin；`CorsConfig` 默认 `*`+credentials；基座 yml 硬编码 JWT 默认密钥 |

**范围约束**：
- 仅实现上述四项 P0，**不**实现 O19（SpEL，P1）等 P0 之外条目。
- 代码结构与设计模式对齐 hermes-agent-main 对应实现（`context_compressor.py` / `chat_completion_helpers.py` / `tool_guardrails.py` / `config.py`+`.env`）。
- 不得偏离优化方案自行设计替代方案。本设计中标注的"适配说明"均为技术可行性下的最小偏差，意图与文档一致。

## 2. 实施路径

采用 **路径 A（文档批次顺序，逐项 TDD）**：

```
O14 → O12 → O7 → O16
```

每项：先写/补 JUnit 测试 → 改生产代码 → 定向 mvn 跑通 → 进入下一项。

**测试命令**（surefire 坑，见记忆 `[[aether-memory-alignment-branch]]`）：

```bash
mvn -pl aether-domain -am test -Dtest=<TestClass> -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

涉及 infrastructure/app 的批次用 `-pl aether-infrastructure -am test` / `-pl aether-app -am test`。

## 3. O14 权限引擎 BYPASS 修复（先做）

### 3.1 设计

1. **`PermissionEngine.check`（BYPASS 分支）**：不再直接返回 ALLOW。改为：
   ```
   if (mode == BYPASS) {
       PermissionDecision deny = evaluateGroup(denyRules, ctx);
       return deny != null ? deny : ALLOW;
   }
   ```
   对齐 hermes `tool_guardrails.py`：护栏与授权正交，工具执行前无条件执行。

2. **`evaluateGroup` fail-closed**：catch 规则异常改为 `log.error` + 返回 `PermissionDecision.DENY`（替代当前 `log.warn` 后跳过）。

3. **`DangerousToolRule`**：
   - 抽出 `public boolean isHardblocked(PermissionContext ctx)`：仅遍历 HARDBLOCK 组，命中返回 true。
   - `evaluate` 内 Layer 1 复用 `isHardblocked`，保持既有行为。
   - 改为 Spring `@Component`；`PermissionEngine` 构造器注入（替代 `new DangerousToolRule()`）。

4. **`ToolExecutor.executeOne` 关卡 2（L248）二次强制校验**：注入 `DangerousToolRule`，在 `tool.checkPermissions(request.input())` **之前**构造最小 `PermissionContext`（toolName/toolInput/userId）调 `isHardblocked`，命中即返回 `ToolResult.error(toolCallId, toolName, "Tool hard-blocked", PERMISSION)`（deny-first，顺序不依赖工具自身实现）。

### 3.2 涉及文件

- `agent/permission/PermissionEngine.java`
- `agent/permission/DangerousToolRule.java`
- `tool/ToolExecutor.java`
- 新增测试：`agent/permission/PermissionEngineBypassTest.java`

### 3.3 验收标准

- BYPASS 模式下 `rm -rf /`、`mkfs.`、`dd if=`、fork bomb 等硬封锁命令仍返回 DENY。
- 规则 evaluate 抛异常 → DENY（fail-closed），不再静默放行。
- `PermissionEngineBypassTest` 全绿；`PermissionMiddleware`/`ReActAgent` 既有测试无回归。

## 4. O12 超时配置统一

### 4.1 设计

1. **`ModelProvider`**：
   - 新增 `default OpenAiApi buildOpenAiApi(ModelConfig config, int connectTimeoutMs, int readTimeoutMs)`：现有方法体参数化（两条 HTTP 通道 RestClient + WebClient 均带显式超时）。
   - 保留 `default OpenAiApi buildOpenAiApi(ModelConfig config)`：委托 `(config, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)`，向后兼容（`MemoryEmbeddingConfig`/既有测试）。

2. **三个 Provider（Anthropic / DashScope / OpenAI）**：
   - 构造器注入 `@Value("${aether.model.invoker.connect-timeout-ms:30000}")` 与 `@Value("${aether.model.invoker.read-timeout-ms:120000}")`（字段 final）。
   - `createChatModel` 与 `createChatModelWithTools` 均改走 `buildOpenAiApi(config, connect, read)`，删除各自 `new OpenAiApi.builder()`。
   - 对齐 hermes `chat_completion_helpers.py:536 interruptible_api_call`：超时为传输层统一约束。

3. **`MemoryEmbeddingConfig`（同源复用）**：`memoryEmbeddingModel` bean 方法注入超时参数，复用 `provider.buildOpenAiApi(cfg, connect, read)`。

4. **`application.yml`**：`aether.model.invoker` 段补 `read-timeout-ms: 120000`，更新 connect-timeout-ms 的 TODO 注释（原"接入点见 ModelProvider.CONNECT_TIMEOUT_MS"已落实）。

### 4.2 涉及文件

- `model/ModelProvider.java`
- `model/impl/AnthropicProvider.java`
- `model/impl/DashScopeProvider.java`
- `model/impl/OpenAIProvider.java`
- `memory/core/MemoryEmbeddingConfig.java`
- `aether-app/src/main/resources/application.yml`
- 测试：`model/ModelProviderTest.java`（扩展）

### 4.3 验收标准

- `ModelProviderTest` 断言：`buildOpenAiApi(config, c, r)` 返回的 OpenAiApi 底层 `ClientHttpRequestFactory` 为 `SimpleClientHttpRequestFactory` 且 `getConnectTimeout()==c` / `getReadTimeout()==r`（反射 `RestClient.requestFactory.clientHttpRequestFactory`）。
- 三个 Provider 的 `createChatModel` 返回的 ChatModel 底层 client 携带配置超时（非默认常量）。
- OpenAI/DashScope 兼容协议回归无破坏。

## 5. O7 压缩调用纳入重试/成本/预算管线

### 5.1 设计

1. **`ContextManager`**：
   - **构造器注入** `ModelInvoker`、`ModelPricingRegistry`（均为 Spring bean，静态可用）。
   - `chatModel` 字段改为 **显式** `@Resource(name = "chatModel") @Lazy ChatModel`。
     > **适配说明**：文档要求"构造注入替代 @Lazy ChatModel 字段"。但该 bean 由 `ChatModelNode` 在请求装配期**运行时注册**（`registerBean("chatModel", ...)`），ContextManager 为启动期单例，真构造注入在启动时 bean 不存在会失败。改为显式定名 `@Resource(name="chatModel")`，达成同一意图——**多 ChatModel bean 下选取确定**（恒选全局默认 resilient 包装模型）。
   - `generateSummary(toCompact, modelName, tokenBudget)` 改经 `modelInvoker.callWithStream(summaryChatModel, llmMessages, systemPrompt, modelName)`：
     - 摘要指令作为 systemPrompt，对话历史作为 UserMessage；
     - 成功时 `tokenBudget.accumulateCost(inputTokens, outputTokens, pricingRegistry.lookup(modelName))`；**`tokenBudget` 为 null 时跳过成本累计**（兼容旧 3 参路径，避免 NPE）；
     - 失败返回 null（沿用现有 fallback 降级路径）。
   - 新增 `autoCompactIfNeeded(messages, modelName, sessionId, TokenBudget tokenBudget)` 重载；旧 3 参方法委托 `null`（向后兼容）。
   - **600s 冷却熔断**（对齐 hermes `context_compressor.py` summary-LLM 冷却）：
     - 新增配置 `aether.context.compaction.summary-cooldown-ms`（默认 600000）；
     - 新增会话级 `ConcurrentHashMap<String, Long> compactCooldownUntil`；
     - LLM 摘要失败时置 `now + cooldownMs`；冷却期内跳过 LLM 调用直接走 fallback。

2. **`ChunkSummarizer`**：
   - 注入 `ModelInvoker`；`callLlm(prompt, modelName, tokenBudget)` 走 `modelInvoker.callWithStream(...)` 并累计成本。
   - `summarize(prefix, modelName, tokenBudget)` 透传签名。

3. **`CompactionPipeline`**：
   - 不直接调用 LLM（实际调 LLM 的是 `ChunkSummarizer`），**不注入 `ModelInvoker`**；`compactIfNeeded(messages, modelName, sessionId, startTurn, TokenBudget)` 透传给 `chunkSummarizer.summarize(prefix, modelName, tokenBudget)`。
   - **删除未使用的 `@Lazy ChatModel` 字段**（当前注入但无调用点，属 D7 歧义注入源）。

4. **`ReActAgent`**：两处压缩调用点（`autoCompactIfNeeded` L191、`runCompactionPipeline` L209）传入自身 `tokenBudget`。

### 5.2 涉及文件

- `context/ContextManager.java`
- `context/compaction/ChunkSummarizer.java`
- `context/compaction/CompactionPipeline.java`
- `agent/impl/ReActAgent.java`
- `aether-app/src/main/resources/application.yml`（新增 cooldown 配置）
- 测试：`context/ContextManagerTest.java`（补充）

### 5.3 验收标准

- mock `ModelInvoker`：断言 `autoCompactIfNeeded` 触发压缩时走 `ModelInvoker.callWithStream`，且 input/output tokens 计入传入的 `TokenBudget`。
- 冷却窗口内再次触发压缩不重复调用 LLM（直接 fallback）。
- `aether.context.compaction.summary-cooldown-ms` 可配置生效。

## 6. O16 生产配置 + 移除硬编码默认凭据

### 6.1 设计

1. **`application-prod.yml`** 补全：
   - `spring.datasource`：`url: ${DB_URL}`、`username: ${DB_USERNAME}`、`password: ${DB_PASSWORD}`（**无默认，fail-fast**）+ hikari + driver。
   - `aether.security.jwt.secret: ${JWT_SECRET}`（无默认）+ `access-token-expiration` / `refresh-token-expiration`。
   - `aether.session.store: postgres`、`aether.session.persistence: true`。
   - `aether.ssrf.allow-private-urls: false`（显式，prod 安全默认）。
   - `otel.exporter.otlp.endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}`。

2. **`application.yml`（基座）**：`aether.security.jwt.secret` 去掉硬编码 dev 回退 → `${JWT_SECRET}`；dev 回退密钥移入 `application-dev.yml`。

3. **`DataInitializer`**：
   - 新增 `@Value("${aether.security.bootstrap-admin:false}") boolean bootstrapAdmin`。
   - 仅当 `bootstrapAdmin==true` **且** `ADMIN_PASSWORD` 环境变量非空时才创建初始管理员，密码取自环境变量。
   - 否则 `log.warn` 跳过。删除 `admin/admin` 硬编码。

4. **`CorsConfig`**：
   - 默认 `aether.cors.allowed-origins` 改为空（无匹配即拒绝跨域）。
   - `*` 时 `addAllowedOriginPattern("*")` 且 `setAllowCredentials(false)`（`*`+credentials 非法/危险）。
   - 具体白名单时 `setAllowedOrigins(list)` 且 `setAllowCredentials(true)`。

5. **`JwtService`**：复核 `@Value("${aether.security.jwt.secret}")` 无默认回退（prod fail-fast），不修改。

### 6.2 涉及文件

- `aether-app/src/main/resources/application-prod.yml`
- `aether-app/src/main/resources/application-dev.yml`
- `aether-app/src/main/resources/application.yml`
- `aether-app/src/main/java/cn/zcj/aether/config/DataInitializer.java`
- `aether-app/src/main/java/cn/zcj/aether/config/CorsConfig.java`

### 6.3 验收标准

- prod profile 缺 `DB_URL`/`DB_PASSWORD`/`JWT_SECRET` 时启动 fail-fast（预期行为）。
- 默认启动不再创建 admin/admin（`aether.security.bootstrap-admin` 缺省 false）。
- CORS 缺省拒绝跨域；`*` 不携带 credentials。
- 基座 yml 无硬编码 JWT 默认密钥。

## 7. 风险与回滚

| 项 | 风险 | 回滚 |
|----|------|------|
| O14 | 权限语义收紧可能影响既有 DEFAULT 路径（deny 组已先执行，无回归面） | 还原 BYPASS 分支与 evaluateGroup 捕获逻辑 |
| O12 | 变更所有 Provider 构造需回归 OpenAI/DashScope 兼容协议调用 | 恢复各 Provider 原构造；保留旧 1 参 `buildOpenAiApi` |
| O7 | 摘要调用延迟受重试/超时影响；TokenBudget 累计可能使成本熔断更早触发 | 保留 3 参旧方法 + `summary-cooldown-ms` 可调 |
| O16 | prod 缺环境变量启动 fail-fast（预期）；dev 回退密钥迁移到 dev yml | 恢复旧 yml 与旧初始化逻辑 |

## 8. 明确不做（P0 之外）

- O19 SpEL 表达式安全（P1）、O1–O6/O8–O11/O13/O15/O17–O18 等一律不实现。
- 不动 hermes-agent-main/ 任何文件。
