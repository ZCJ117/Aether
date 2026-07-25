# Aether Agent 架构修复设计文档

**日期**: 2026-07-25 | **状态**: 已确认 | **范围**: domain 层，5 项关键修复

---

## 背景

基于 2026-07-25 的 12 层 Agent 架构审计，识别出 5 个关键（Critical）后端架构问题。本设计文档定义每项修复的方案，借鉴了 5 个参考项目的最佳实践。

## 修复概览

| 编号 | 问题 | 严重度 | 借鉴项目 | 改动文件 | 预计行数 |
|------|------|--------|----------|----------|----------|
| C1 | 工具全局共享，无 Agent 级作用域 | Critical | AgentScope Java, cc-haha | AgentGraphCompiler, ChatModelNode, ToolRegistry, AgentNodeDef, AiAgentConfigTableVO | ~150 |
| C2 | ContextManager + EncodingFlow 两处隐藏 LLM 调用 | Critical | AgentScope Java, CrewAI | ContextManager, EncodingFlow, DefaultMemoryFacade, RuntimeEvent | ~80 |
| C3 | MemoryStore 向量搜索退化 + RecallFlow llmRerank 空桩 | Critical | CrewAI | MemoryStore, RecallFlow | ~100 |
| C4 | `{outputKey}` 模板编译时无校验 | Critical | MetaGPT | AgentGraphCompiler | ~50 |
| C5 | HTTP 400 被标记为可重试 | Critical | 自研 | ModelInvoker | ~20 |

---

## C1: Agent 级工具作用域

### 问题

`AgentGraphCompiler.java:85` 将 `AgentNodeDef.toolNames` 硬编码为 `List.of()`。所有 MCP/Skills 工具在 `ChatModelNode` 装配阶段一次性绑定到全局 `ChatModel` Bean，同一 config table 内所有 Agent 共享全部工具。

### 方案

**YAML 配置**: `Module.Agent` 新增 `toolNames` 字段：
```yaml
agents:
  - name: CodeWriterAgent
    toolNames: [write_file, read_file]  # 显式 allowlist
  - name: WebSearchAgent
    toolNames: ["*"]                     # 通配符 = 全部工具
```
语义：`"*"` = 全部工具（向后兼容），空列表 = 无工具，`null`/未配置 = 全部工具（向后兼容）。

**编译期**: AgentGraphCompiler 遍历 agent 的 toolNames，校验每个工具名在 ToolRegistry 中存在，然后写入 AgentNodeDef.toolNames。

**装配期**: ChatModelNode 为**每个 agent** 创建独立的 `chatModel-{agentName}` Bean（扩展现有 per-agent model 逻辑），按 toolNames 过滤 ToolCallback。

### 改动清单

1. `AiAgentConfigTableVO.Module.Agent` — 新增 `toolNames: List<String>` 字段
2. `AgentGraphCompiler.compileAgentDefs()` — 编译 toolNames，校验工具存在性
3. `ChatModelNode` — 扩展 `registerPerAgentChatModel()`，按 toolNames 过滤 ToolCallback 后创建 per-agent ChatModel
4. `ToolRegistry` — 新增 `containsAll(Collection<String>)` 校验方法

### 向后兼容

- 未配置 `toolNames` 时行为不变（全部工具可用）
- 已有 YAML 无需修改即可运行

---

## C2: 隐藏 LLM 调用事件化

### 问题

`ContextManager.generateSummary()` 和 `EncodingFlow.encode()` 各自独立调用 `chatModel.call()`，调用方无感知，token 消耗不透明。

### 方案

新增 `RuntimeEvent.EventType.internalLlmCall` 事件类型，包裹两次隐藏调用。事件通过已有的 `AgentEventPublisher`（可选注入）发布，无 Publisher 时静默降级。

**ContextManager**: `autoCompactIfNeeded()` 返回的 `AutoCompactResult` 新增 `InternalLlmCallEvent` 字段，由 `ReActAgent.queryLoop()` 发射。

**EncodingFlow**: `EncodeResult` 新增调用元数据字段，`DefaultMemoryFacade.remember()` 通过 `AgentEventPublisher` 发布事件。

### 改动清单

1. `RuntimeEvent` — 新增 `EventType.internalLlmCall` 及相关字段
2. `AutoCompactResult` — 新增 `internalLlmCallEvent` 字段
3. `ContextManager.autoCompactIfNeeded()` — 包裹 LLM 调用，记录事件
4. `ReActAgent.queryLoop()` — 发射 compact 相关的 `internalLlmCall` 事件
5. `EncodingFlow.encode()` — 返回结果新增调用统计
6. `DefaultMemoryFacade` — 注入可选 `AgentEventPublisher`，在 LLM 编码完成后发布事件

---

## C3: MemoryStore 向量搜索 + RecallFlow llmRerank

### 问题

`MemoryStore.search()` 对所有文件返回统一 0.1 分——实际"全量返回"。`RecallFlow.llmRerank()` 是空桩，只做 `subList(0, maxResults)` 截断。

### 方案

**MemoryStore**: 注入 `RecallFlow`（已有 `@Component`），`search()` 方法改为关键词初筛 + `RecallFlow.recallShallow()` 加权排序。语义搜索路径真正生效。

**RecallFlow.llmRerank()**: 当 `chatModel` 可用时，用精简 prompt（~200 token）对候选记忆做 LLM 重排序。chatModel 不可用时回退到截断行为。

**RecallFlow.decomposeQuery()**: 保留当前标点分割逻辑，增加 LLM 语义分解路径（Deep 召回已有此意图）。

### 改动清单

1. `MemoryStore` — 注入 `RecallFlow`，`search()` 改为委托模式
2. `RecallFlow.llmRerank()` — 实现真实 LLM 重排
3. `RecallFlow.decomposeQuery()` — 增强语义分解

---

## C4: `{outputKey}` 编译期校验

### 问题

`ExecutionState.resolveTemplate()` 做纯字符串替换。引用不存在的 outputKey → `{someKey}` 字面量保留在 system prompt 中，无任何警告。

### 方案

**AgentGraphCompiler** 新增 `validateOutputKeyReferences()` 方法：
- 用正则 `\{(\w+)\}` 提取每个 Agent instruction 中的 `{key}` 占位符
- 对每个 key，在 AgentGraph 的上下游关系中校验
- EntryPoint Agent 不应包含 `{key}` 引用（没有上游）
- 未解析的 key → 抛出 `AgentCompileException(agentName + "引用了不存在的 outputKey: " + key)`

### 改动清单

1. `AgentGraphCompiler` — 新增 `validateOutputKeyReferences()` 方法，编译完成后调用

---

## C5: Provider 级重试策略

### 问题

`ModelInvoker.isRetryable()` 将 HTTP 400 标记为全局可重试，掩盖真正的格式错误。

### 方案

```java
case 400 -> isMimoProvider(modelRef);  // 仅小觅 API 可重试 400
```

通过 `modelRef` 参数判断 provider 类型。后续可扩展为 `ModelProvider` SPI 接口方法。

### 改动清单

1. `ModelInvoker.isRetryable()` — 增加 `modelRef` 参数，400 仅小觅可重试
2. `ModelInvoker.callWithStream()` — 传 `modelRef` 到 `isRetryable()`

---

## 不变范围

- 不修改 trigger、api、infrastructure、app、types 层
- 不修改前端代码
- 不修改 YAML 配置文件的语法（仅新增可选字段）
- 不修改 ReActAgent 主循环逻辑
- 不修改 GraphExecutor 执行逻辑
