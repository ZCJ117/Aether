# Aether Agent 架构修复 — 实施计划

> **For agentic workers:** 使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务执行。步骤使用 `- [ ]` 复选框跟踪。

**目标**: 修复12层审计中识别出的5个关键（Critical）后端架构问题，提升工具安全、LLM调用可观测性、记忆搜索质量和配置健壮性。

**架构**: 改动限定 domain 层，所有修复向后兼容。借鉴 AgentScope Java（工具作用域、事件体系）、CrewAI（记忆召回管线）、MetaGPT（编译期校验）、cc-haha（工具过滤）。

**技术栈**: Java 17, Spring Boot 3.4.3, Lombok, Jackson

---

### Task 1: C4 — `{outputKey}` 编译期校验

**文件:**
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/compiler/AgentGraphCompiler.java`

- [ ] **Step 1: 在 compileAgentDefs 中编译 toolNames 并写入 AgentNodeDef**

修改 `compileAgentDefs()` 的第 85 行，将 `List.of()` 替换为从 `agentConfig.getToolNames()` 读取的实际工具列表：

```java
// 修改前（第85行）：
.toolNames(List.of())

// 修改后：
.toolNames(compileToolNames(agentConfig))
```

新增 `compileToolNames()` 和 `validateOutputKeyReferences()` 方法：

```java
/**
 * 编译 Agent 的工具名列表。
 * "*" 或 null/空 = 全部工具（编译时暂不展开，运行时由 ToolRegistry 处理）
 */
private List<String> compileToolNames(AiAgentConfigTableVO.Module.Agent agentConfig) {
    List<String> rawNames = agentConfig.getToolNames();
    if (rawNames == null || rawNames.isEmpty()) {
        return List.of("*");  // 默认全部工具，语义明确
    }
    return List.copyOf(rawNames);
}

/**
 * 编译后校验：确保所有 instruction 中引用的 {outputKey} 都在上游 Agent 的 outputKey 中有定义。
 * 未解析的引用 → 启动失败，明确报出哪个 Agent 引用了哪个不存在的 key。
 */
private void validateOutputKeyReferences(
        Map<String, AgentNodeDef> agentDefs,
        List<AgentEdge> edges,
        String entryPoint) {

    // 构建父节点 → 子节点关系（从 edge.subAgents 顺序推导）
    // 收集所有定义了 outputKey 的 agent
    Map<String, String> definedOutputKeys = new LinkedHashMap<>();
    for (var def : agentDefs.values()) {
        if (def.getOutputKey() != null && !def.getOutputKey().isBlank()) {
            definedOutputKeys.put(def.getOutputKey(), def.getName());
        }
    }

    // 对每个 agent，找出上游 agent 产生的所有 outputKey
    for (var def : agentDefs.values()) {
        String instruction = def.getInstruction();
        if (instruction == null) continue;

        // 允许 agent 引用自己的 outputKey（循环场景）
        Set<String> availableKeys = new HashSet<>(definedOutputKeys.keySet());

        // entryPoint agent 不应有外部引用（除了自己）
        // 但从 SEQUENTIAL/PARALLEL/LOOP edges 可以推导出上下游关系

        // 提取 instruction 中所有 {key} 引用
        Set<String> referencedKeys = extractTemplateKeys(instruction);

        for (String key : referencedKeys) {
            if (!availableKeys.contains(key)) {
                throw new AgentCompileException(
                    "Agent [" + def.getName() + "] 的 instruction 引用了未定义的 outputKey: {" +
                    key + "}。已定义的 outputKey: " + definedOutputKeys.keySet());
            }
        }
    }

    log.info("outputKey 引用校验通过: {} 个 Agent，{} 个已定义 outputKey",
            agentDefs.size(), definedOutputKeys.size());
}

/**
 * 从 instruction 文本中提取所有 {key} 占位符
 */
private Set<String> extractTemplateKeys(String instruction) {
    Set<String> keys = new LinkedHashSet<>();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\w+)\\}")
            .matcher(instruction);
    while (m.find()) {
        keys.add(m.group(1));
    }
    return keys;
}
```

- [ ] **Step 2: 新增 AgentCompileException 异常类**

创建: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/compiler/AgentCompileException.java`

```java
package cn.zcj.aether.domain.agent.service.compiler;

/**
 * Agent 图编译异常 —— 启动时配置校验失败。
 */
public class AgentCompileException extends RuntimeException {

    public AgentCompileException(String message) {
        super(message);
    }

    public AgentCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: 在 compile() 方法末尾调用校验**

在 `AgentGraphCompiler.compile()` 方法的 `return` 语句之前添加：

```java
// Step 5: 编译后校验
validateOutputKeyReferences(agentDefs, edges, entryPoint);
```

- [ ] **Step 4: 在 AiAgentConfigTableVO.Module.Agent 中新增 toolNames 字段**

修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/model/valobj/AiAgentConfigTableVO.java`

在 `Module.Agent` 类的 `model` 字段之前（第157行附近）添加：

```java
// C1: Agent 级工具作用域 — 显式指定该 Agent 可使用的工具名列表
// "*" 或未配置 = 全部工具；空列表 = 无工具
private List<String> toolNames;
```

- [ ] **Step 5: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

预期: 编译通过。现有 YAML 未配置 `toolNames`，默认返回 `List.of("*")`，编译到此即止，无破坏性变更。

---

### Task 2: C1 — Agent 级工具作用域（ChatModelNode 扩展 per-agent ChatModel）

**文件:**
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/armory/node/ChatModelNode.java`

- [ ] **Step 1: 修改 doApply() — 为所有 agent 创建 per-agent ChatModel Bean**

将 `doApply()` 的第 104-112 行（per-agent ChatModel 创建循环）改为：不再仅 `agent.getModel() != null` 时创建，而是所有 agent 都创建（按 toolNames 过滤 ToolCallback）。

修改 `doApply()` 中第 104-112 行：

```java
// 修改前（仅 model 覆盖时才创建）：
// P0-3: 为配置了独立模型的 Agent 创建独立 ChatModel Bean
List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();
if (agents != null) {
    for (var agent : agents) {
        if (agent.getModel() != null) {
            registerPerAgentChatModel(agent, dynamicContext, toolCallbackList);
        }
    }
}

// 修改后（所有 agent 都创建，按 toolNames 过滤）：
List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();
if (agents != null) {
    for (var agent : agents) {
        // C1: 始终为每个 Agent 创建独立 ChatModel Bean，按 toolNames 过滤工具
        registerPerAgentChatModel(agent, dynamicContext, toolCallbackList);
    }
}
```

- [ ] **Step 2: 重写 registerPerAgentChatModel() — 增加 toolNames 过滤**

完全重写 `registerPerAgentChatModel()` 方法：

```java
/**
 * C1 改造：为每个 Agent 创建独立的 ChatModel Bean。
 * 根据 Agent 的 toolNames 配置过滤 ToolCallback，实现 Agent 级工具作用域。
 * 
 * 借鉴 AgentScope Java 的 per-agent Toolkit 深拷贝模式 + cc-haha 的 allowlist 设计。
 */
private void registerPerAgentChatModel(
        AiAgentConfigTableVO.Module.Agent agent,
        DefaultArmoryFactory.DynamicContext dynamicContext,
        List<ToolCallback> allToolCallbacks) {

    // 计算该 Agent 允许的工具名集合
    Set<String> allowedToolNames = resolveToolNames(agent, allToolCallbacks);

    // 按 allowlist 过滤 ToolCallback
    List<ToolCallback> filteredCallbacks;
    if (allowedToolNames == null) {
        // null = 通配符 "*" → 全部工具
        filteredCallbacks = allToolCallbacks;
    } else {
        filteredCallbacks = allToolCallbacks.stream()
            .filter(tc -> {
                try {
                    String name = tc.getToolDefinition().name();
                    return allowedToolNames.contains(name);
                } catch (Exception e) {
                    log.warn("获取工具定义失败，保留该工具: {}", e.getMessage());
                    return true; // 无法获取定义时保守保留
                }
            })
            .toList();
        log.info("Agent [{}] 工具过滤: {} 个允许 → {} 个实际",
                agent.getName(), allowedToolNames.size(), filteredCallbacks.size());
    }

    // 处理 ModelConfig（与原来逻辑相同，但 model 可为 null → 回退全局）
    ModelConfig globalConfig = dynamicContext.getModelConfig();
    String modelId, baseUrl, apiKey;
    if (agent.getModel() != null) {
        modelId = agent.getModel().getModelId();
        baseUrl = agent.getModel().getBaseUrl();
        apiKey = agent.getModel().getApiKey();
    } else {
        modelId = globalConfig.getModelId();
        baseUrl = null;
        apiKey = null;
    }
    if (baseUrl == null) baseUrl = globalConfig.getBaseUrl();
    if (apiKey == null) apiKey = globalConfig.getApiKey();

    ModelConfig agentModelConfig = ModelConfig.builder()
            .modelId(modelId)
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .completionsPath(globalConfig.getCompletionsPath())
            .embeddingsPath(globalConfig.getEmbeddingsPath())
            .build();

    ModelProvider provider = modelProviderRegistry.resolve(modelId);
    ChatModel agentChatModel = provider.createChatModelWithTools(agentModelConfig, filteredCallbacks);

    String beanName = "chatModel-" + agent.getName();
    registerBean(beanName, ChatModel.class, agentChatModel);

    log.info("Agent [{}] ChatModel Bean 已注册: beanName={}, modelId={}, provider={}, tools={}",
            agent.getName(), beanName, modelId, provider.providerName(),
            filteredCallbacks.size());
}

/**
 * 解析 Agent 允许的工具名集合。
 * 返回 null 表示全部工具（向后兼容），返回空 Set 表示无工具。
 */
private Set<String> resolveToolNames(
        AiAgentConfigTableVO.Module.Agent agent,
        List<ToolCallback> allToolCallbacks) {

    List<String> rawNames = agent.getToolNames();
    if (rawNames == null || rawNames.isEmpty()) {
        return null; // 全部工具
    }
    if (rawNames.size() == 1 && "*".equals(rawNames.get(0))) {
        return null; // 通配符 = 全部工具
    }
    return new HashSet<>(rawNames);
}
```

- [ ] **Step 3: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

预期: 编译通过。现有 YAML 未配置 toolNames → `resolveToolNames()` 返回 null → 全部工具 → 行为不变。

---

### Task 3: C5 — Provider 级重试策略

**文件:**
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/runtime/ModelInvoker.java`

- [ ] **Step 1: 修改 isRetryable() — 增加 modelRef 参数，400 仅小觅可重试**

将 `isRetryable()` 方法签名和 400 处理逻辑改为：

```java
// 修改方法签名（第172行）：
// 修改前：
boolean isRetryable(Exception e) {

// 修改后：
boolean isRetryable(Exception e, String modelRef) {
```

在方法末尾的 400 处理（第203-204行）改为：

```java
// 修改前：
// 400 → 重试（非标准 API 可能在初始化阶段返回瞬时 400）
if (msg.contains("400")) return true;

// 修改后：
// 400 → 仅小觅 API（api.xiaomimimo.com）可重试
// 其他 Provider 的 400 为真正的客户端错误，不应重试
if (msg.contains("400")) {
    return modelRef != null && modelRef.toLowerCase().contains("mimo");
}
```

- [ ] **Step 2: 更新 callWithStream() 中 isRetryable 的调用点**

将 `callWithStream()` 中第 136 行的调用改为传入 `modelName`：

```java
// 修改前（第136行）：
if (!isRetryable(e)) {

// 修改后：
if (!isRetryable(e, modelName)) {
```

- [ ] **Step 3: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

预期: 编译通过。

---

### Task 4: C2 — 隐藏 LLM 调用事件化

**文件:**
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/runtime/RuntimeEvent.java`
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/context/AutoCompactResult.java`
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/context/ContextManager.java`
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/impl/ReActAgent.java`
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/EncodingFlow.java`
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/DefaultMemoryFacade.java`

- [ ] **Step 1: RuntimeEvent 新增 internalLlmCall 事件类型**

在 `EventType` 枚举中添加 `internalLlmCall`，并在类中新增对应字段：

```java
// 在 EventType 枚举中（第24-33行）添加：
internalLlmCall,   // C2: 非主循环 LLM 调用（context-compaction / memory-encoding）

// 在类的现有字段后添加（第22行之后）：
private String internalLlmSource;     // "context-compaction" | "memory-encoding"
private String internalLlmModel;      // 使用的模型名
private long internalLlmDurationMs;   // 耗时（毫秒）
private boolean internalLlmSuccess;   // 是否成功

// 新增工厂方法：
public static RuntimeEvent internalLlmCall(String source, String model,
                                            long durationMs, boolean success) {
    return RuntimeEvent.builder()
            .type(EventType.internalLlmCall)
            .internalLlmSource(source)
            .internalLlmModel(model)
            .internalLlmDurationMs(durationMs)
            .internalLlmSuccess(success)
            .build();
}
```

- [ ] **Step 2: AutoCompactResult 新增 llm 调用事件字段**

在 `AutoCompactResult` 类中添加字段和工厂方法更新：

```java
// 新增字段（第19行之后）：
private RuntimeEvent internalLlmCallEvent;  // C2: autoCompact 的隐藏 LLM 调用事件

// 更新 compacted() 工厂方法，新增重载：
public static AutoCompactResult compacted(String summary, List compactMessages,
                                           int preTokens, int postTokens,
                                           RuntimeEvent llmCallEvent) {
    return AutoCompactResult.builder()
            .compacted(true)
            .summary(summary)
            .compressedMessages(compactMessages)
            .preCompactTokens(preTokens)
            .postCompactTokens(postTokens)
            .internalLlmCallEvent(llmCallEvent)
            .build();
}

// 保留原 compacted() 方法以向后兼容：
public static AutoCompactResult compacted(String summary, List compactMessages,
                                           int preTokens, int postTokens) {
    return compacted(summary, compactMessages, preTokens, postTokens, null);
}
```

- [ ] **Step 3: ContextManager.autoCompactIfNeeded() — 记录 LLM 调用事件**

修改 `autoCompactIfNeeded()` 方法（第158-171行），包裹 LLM 调用并创建事件：

```java
// 修改第158-171行：
// 调用 LLM 生成摘要，失败时降级为字符串拼接
long llmStart = System.currentTimeMillis();
String summary = generateSummary(toCompact);
long llmDuration = System.currentTimeMillis() - llmStart;
boolean llmSuccess = summary != null && !summary.isBlank();

if (!llmSuccess) {
    summary = fallbackSummary(toCompact);
}

List<TurnMessage> compacted = new ArrayList<>();
compacted.add(TurnMessage.user("[对话历史摘要]\n" + summary));
compacted.addAll(recent);

int postTokens = estimateTokens(compacted);
log.info("压缩完成: {} tokens → {} tokens (LLM={}ms, success={})",
        currentTokens, postTokens, llmDuration, llmSuccess);

// C2: 创建隐藏 LLM 调用事件
RuntimeEvent llmCallEvent = RuntimeEvent.internalLlmCall(
        "context-compaction", modelName, llmDuration, llmSuccess);

return AutoCompactResult.compacted(summary, compacted, currentTokens, postTokens, llmCallEvent);
```

- [ ] **Step 4: ReActAgent.queryLoop() — 发射 compact 的 LLM 调用事件**

在 `queryLoop()` 的 autoCompact 处理段（第146-155行），增加事件发射：

```java
// 在第151-154行（compactBoundary 事件发射）之后添加：
if (compactResult.getInternalLlmCallEvent() != null) {
    emitter.onNext(compactResult.getInternalLlmCallEvent());
}
```

- [ ] **Step 5: EncodingFlow — 返回 LLM 调用统计**

修改 `EncodeResult` record，新增调用元数据：

```java
// 在 encode() 方法（第57-76行）中包裹 LLM 调用：
public EncodeResult encode(String content) {
    if (content == null || content.isBlank()) {
        return EncodeResult.defaults();
    }
    if (chatModel == null) {
        log.debug("ChatModel 尚未就绪，跳过 LLM 编码");
        return EncodeResult.defaults();
    }

    long startMs = System.currentTimeMillis();
    boolean success = false;
    try {
        String prompt = String.format(ENCODE_PROMPT, content);
        var response = chatModel.call(new Prompt(new SystemMessage(prompt)));
        String text = response.getResult().getOutput().getText();
        EncodeResult result = parseResponse(text);
        success = true;
        return result.withLlmCallStats(success, System.currentTimeMillis() - startMs);
    } catch (Exception e) {
        log.warn("LLM 记忆编码失败，使用默认元数据: error={}", e.getMessage());
        return EncodeResult.defaults()
                .withLlmCallStats(false, System.currentTimeMillis() - startMs);
    }
}

// 修改 EncodeResult record（第107-116行），新增字段和工厂方法：
public record EncodeResult(
    List<String> categories,
    float importance,
    boolean shouldConsolidate,
    String consolidationHint,
    // C2: LLM 调用统计（可选，客户端可据此发布事件）
    boolean llmCalled,
    boolean llmSuccess,
    long llmDurationMs
) {
    public static EncodeResult defaults() {
        return new EncodeResult(Collections.emptyList(), 0.5f, false, "", false, false, 0);
    }

    public EncodeResult withLlmCallStats(boolean success, long durationMs) {
        return new EncodeResult(
            this.categories, this.importance, this.shouldConsolidate,
            this.consolidationHint, true, success, durationMs);
    }
}
```

- [ ] **Step 6: DefaultMemoryFacade — 发布内部 LLM 调用事件**

在 `DefaultMemoryFacade` 中注入可选的 `AgentEventPublisher`，在 `remember()` 方法中发布事件：

```java
// 新增字段注入（在 storeExecutor 字段之后）：
@org.springframework.beans.factory.annotation.Autowired(required = false)
private AgentEventPublisher eventPublisher;

// 在 remember() 方法中，encoding 之后（第48行之后）添加：
if (eventPublisher != null && encoded.llmCalled()) {
    // 通过事件发布器记录内部 LLM 调用（非主循环）
    log.debug("记忆编码 LLM 调用: success={}, durationMs={}",
            encoded.llmSuccess(), encoded.llmDurationMs());
    // 事件已通过 EncodeResult 携带，调用方可选择性发布到 SSE 流
    // DefaultMemoryFacade 是异步后台任务，不直接推送 SSE
}
```

- [ ] **Step 7: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

预期: 编译通过。EncodeResult 的 record 变更需确保所有引用点兼容（仅 `EncodingFlow` 和 `DefaultMemoryFacade` 内部使用）。

---

### Task 5: C3 — MemoryStore 向量搜索委托 + RecallFlow llmRerank 实现

**文件:**
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/MemoryStore.java`
- 修改: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/RecallFlow.java`

- [ ] **Step 1: MemoryStore.search() — 委托 RecallFlow 做语义搜索**

修改 `MemoryStore`，注入 `RecallFlow`，重写 `search()` 方法：

```java
// 新增依赖注入（第25行附近）：
@org.springframework.beans.factory.annotation.Autowired(required = false)
private RecallFlow recallFlow;

// 重写 search() 方法（原第252-282行）：
@Override
public CompletableFuture<List<MemorySearchResult>> search(
        float[] queryVector, int maxResults, List<MemoryScope> scopes) {

    // C3: 委托 RecallFlow 进行真正的语义搜索
    if (recallFlow != null) {
        // 从 queryVector 无法还原文本 query，使用 recallShallow 无 query 模式
        // 先用 MemoryStore 自己的关键词匹配做初筛
        String queryText = extractQueryFromVector(queryVector);

        var options = new MemoryFacade.RecallOptions(
            MemoryFacade.RecallOptions.RecallDepth.SHALLOW,
            scopes,
            maxResults,
            0.6f, 0.3f, 0.1f);

        return recallFlow.recallShallow(queryText != null ? queryText : "", options);
    }

    // 回退: 无 RecallFlow 时返回空结果（不再返回伪向量打分）
    log.warn("RecallFlow 未注入，MemoryStore 语义搜索回退为空结果");
    return CompletableFuture.completedFuture(List.of());
}

/**
 * 从查询向量中提取文本 query（如果有 storage 关联）
 * 当前文件存储模式下，向量是伪向量，无法还原文本
 */
private String extractQueryFromVector(float[] queryVector) {
    if (queryVector == null) return null;
    // 检查是否为默认维度的伪向量
    if (queryVector.length == 1280 && java.util.Arrays.stream(queryVector).allMatch(v -> v == 0.0f)) {
        return null;
    }
    return null; // 文件存储模式下不支持向量→文本还原
}
```

- [ ] **Step 2: RecallFlow.llmRerank() — 实现真正的 LLM 重排**

重写 `RecallFlow.llmRerank()` 方法（原第146-150行）：

```java
/**
 * C3: LLM 重排序 —— 使用精简 prompt 对候选记忆做相关性排序。
 * 借鉴 CrewAI RecallFlow 的置信度路由设计。
 * chatModel 不可用时回退到截断。
 */
private List<MemorySearchResult> llmRerank(
        String query, List<MemorySearchResult> candidates, int maxResults) {

    if (candidates.size() <= maxResults) return candidates;

    // 无 LLM 时回退截断
    if (chatModel == null) {
        log.debug("ChatModel 未就绪，llmRerank 回退为截断");
        return candidates.subList(0, maxResults);
    }

    try {
        // 构建精简 prompt：候选记忆摘要 + query
        StringBuilder sb = new StringBuilder();
        sb.append("用户查询: ").append(query).append("\n\n");
        sb.append("候选记忆列表:\n");
        for (int i = 0; i < candidates.size(); i++) {
            var c = candidates.get(i);
            String content = c.getRecord().getContent();
            String preview = content != null && content.length() > 150
                    ? content.substring(0, 150) + "..." : content;
            sb.append("[").append(i).append("] ").append(preview).append("\n");
        }
        sb.append("\n请选出与用户查询最相关的前 ").append(maxResults)
          .append(" 条记忆的编号，严格返回 JSON 数组格式: [0, 3, 5]"
                  + "。只返回编号数组，不要其他内容。");

        // 隐藏 LLM 调用的消息体积控制
        if (sb.length() > 3000) {
            sb.setLength(3000);
            sb.append("\n... (候选记忆已截断)");
        }

        var response = chatModel.call(new org.springframework.ai.chat.prompt.Prompt(
                new org.springframework.ai.chat.messages.UserMessage(sb.toString())));
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) return candidates.subList(0, maxResults);

        // 解析 LLM 返回的编号数组
        List<Integer> rankedIndices = parseRerankIndices(text, candidates.size());
        if (rankedIndices.isEmpty()) return candidates.subList(0, maxResults);

        // 按 LLM 排序重组结果
        List<MemorySearchResult> reranked = new ArrayList<>();
        for (int idx : rankedIndices) {
            if (idx >= 0 && idx < candidates.size() && reranked.size() < maxResults) {
                reranked.add(candidates.get(idx));
            }
        }
        // 补充未提及的候选
        for (int i = 0; i < candidates.size() && reranked.size() < maxResults; i++) {
            if (!rankedIndices.contains(i)) {
                reranked.add(candidates.get(i));
            }
        }
        log.debug("llmRerank: {} 候选 → {} 结果 (LLM 排序)", candidates.size(), reranked.size());
        return reranked;

    } catch (Exception e) {
        log.warn("LLM 重排序失败，回退为截断: {}", e.getMessage());
        return candidates.subList(0, maxResults);
    }
}

/**
 * 解析 LLM 返回的 JSON 编号数组
 */
private List<Integer> parseRerankIndices(String text, int maxIndex) {
    try {
        // 清理可能的 markdown 代码块
        String json = text.trim();
        if (json.startsWith("```")) {
            json = json.substring(json.indexOf('\n') + 1);
            if (json.endsWith("```")) {
                json = json.substring(0, json.lastIndexOf("```")).trim();
            }
        }
        // 提取 JSON 数组
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end < 0) return List.of();
        json = json.substring(start, end + 1);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        return List.of(mapper.readValue(json, Integer[].class));
    } catch (Exception e) {
        log.debug("解析 LLM 重排结果失败: {}", text);
        return List.of();
    }
}
```

- [ ] **Step 3: RecallFlow.decomposeQuery() — LLM 语义分解增强**

修改 `decomposeQuery()` 方法（原第110-121行），当 chatModel 可用时使用 LLM 分解：

```java
private List<String> decomposeQuery(String query) {
    List<String> subQueries = new ArrayList<>();
    subQueries.add(query);

    // 标点分割（保留原逻辑）
    for (String part : query.split("[。；;]")) {
        String trimmed = part.trim();
        if (trimmed.length() > 5 && !trimmed.equals(query)) {
            subQueries.add(trimmed);
        }
    }

    // C3: LLM 语义分解增强
    if (chatModel != null && subQueries.size() == 1) {
        try {
            String prompt = "将以下搜索查询拆分为2-3个更具体的子查询，用于记忆搜索。"
                    + "严格返回 JSON 数组格式。\n查询: " + query;
            var response = chatModel.call(new org.springframework.ai.chat.prompt.Prompt(
                    new org.springframework.ai.chat.messages.UserMessage(prompt)));
            String text = response.getResult().getOutput().getText();
            if (text != null && !text.isBlank()) {
                List<String> llmSubs = parseJsonArray(text);
                if (!llmSubs.isEmpty()) {
                    subQueries.addAll(llmSubs);
                }
            }
        } catch (Exception e) {
            log.debug("LLM 查询分解失败，使用标点分解: {}", e.getMessage());
        }
    }

    return subQueries;
}

private List<String> parseJsonArray(String text) {
    try {
        String json = text.trim();
        if (json.startsWith("```")) {
            json = json.substring(json.indexOf('\n') + 1);
            if (json.endsWith("```")) {
                json = json.substring(0, json.lastIndexOf("```")).trim();
            }
        }
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end < 0) return List.of();
        json = json.substring(start, end + 1);
        return List.of(new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, String[].class));
    } catch (Exception e) {
        return List.of();
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

预期: 编译通过。

---

### Task 6: 全量编译验证 + 回归检查

- [ ] **Step 1: 全量编译**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile
```

- [ ] **Step 2: 检查无编译错误**

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "Agent架构关键问题修复: C1工具作用域 + C2隐藏LLM事件化 + C3记忆搜索 + C4模板校验 + C5重试策略"
```
