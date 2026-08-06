# Aether 记忆系统对齐 hermes 设计

**日期**：2026-08-06
**状态**：已评审（brainstorming 5 节全部确认）
**目标**：在 Aether Java 后端重构记忆系统，使其运行时行为、生命周期钩子与配置项对齐 `hermes-agent-main` 的记忆系统设计；复用现有 `MemoryFacade` 体系作为 builtin provider 内部实现。

---

## 0. 结论摘要

- **对齐方式**：Java 端重构对齐（非 Python 服务、非仅行为对齐）。
- **现有代码处置**：复用现有 `MemoryFacade`/`EncodingFlow`/`RecallFlow`/`VectorStore` 作为 builtin provider 内部实现，保留不动。
- **新增层**：`memory.core` 包 —— `MemoryProvider` SPI + `MemoryManager` 编排 + `BuiltinMemoryProvider` + `MemoryContextScrubber` + `MemoryProperties` + `MemoryLifecycleHooks`。
- **方案**：方案 A（完整生命周期对齐 + Provider SPI），不实现外部 provider 插件注册（YAGNI）。

---

## 1. hermes-agent-main 记忆系统分析（参考实现）

### 1.1 存储数据结构

hermes 记忆系统由**两层**组成（均为 Python）：

1. **内置记忆**（`tools/memory_tool.py` 的 `MemoryStore`）：
   - 两个存储文件：`MEMORY.md`（agent 笔记）与 `USER.md`（用户画像）。
   - 条目以 Markdown 项目符号列表存储，通过 `add` / `replace` / `remove` / `apply_batch` 操作维护。
   - **字符预算**约束：`memory_char_limit: 2200`、`user_char_limit: 1375`；超限由 agent 自行 consolidate/replace（"agent manages pruning"）。
   - 带**外部漂移检测**（`_detect_external_drift`）：用户手工编辑文件后与内存快照不一致时告警。

2. **外部语义 provider**（`plugins/memory/supermemory/` 等）：
   - 通过 `memory.provider` 配置键激活，语义长期记忆存于 Postgres 等外部后端。
   - 配置（`$HERMES_HOME/supermemory.json`）：`auto_recall`、`auto_capture`、`max_recall_results: 10`、`capture_mode: all`、`search_mode: hybrid`、`profile_frequency: 50`。

核心抽象 `MemoryProvider`（`agent/memory_provider.py`）与 `MemoryManager`（`agent/memory_manager.py`）不自行定义"短期/长期/工作记忆"数据模型，而是将短期记忆=会话上下文、长期记忆=provider 后端、工作记忆=prefetch 注入的系统提示块，通过生命周期接口统一。

### 1.2 检索与召回

- **turn 前** `prefetch(query, session_id)`：召回相关上下文，注入系统提示。返回文本经 `build_memory_context_block()` 包装为 `<memory-context>` 围栏 + 系统注记（"[System note: ...NOT new user input...]"）。
- **turn 后** `queue_prefetch(query, session_id)`：为下一轮入队后台召回。
- **净化**：`sanitize_context()`（正则剥除围栏/注记）+ `StreamingContextScrubber`（跨流式分片的标签状态机，防模型把记忆上下文回显为助手回复）。
- 上下文预算由 char limit 兜底，无显式 token 级预算机制。

### 1.3 更新时机与策略

| 触发点 | 钩子 | 说明 |
|---|---|---|
| 每轮对话完成 | `sync_turn(user, asst, messages)` | 异步持久化本轮（对齐 auto_capture） |
| 会话结束（CLI 退出 / /reset / gateway 过期） | `on_session_end(messages)` | 提取事实/总结 |
| 会话切换（/resume /branch /new） | `on_session_switch(new_session_id, ...)` | 更新 provider 缓存会话态 |
| 上下文压缩前 | `on_pre_compress(messages) -> str` | 提取将丢弃消息的洞察，注入压缩 prompt |
| 每 N 轮（nudge） | MemoryManager 计数 | `nudge_interval: 10`，注入"考虑保存记忆"提醒 |
| 上下文丢失前（压缩 /new /reset /exit） | flush | `flush_min_turns: 6`，至少 N 轮才触发 |
| 内置记忆工具写入时 | `on_memory_write(action, target, content)` | 镜像到外部后端 |

### 1.4 配置项

| 键（cli-config.yaml） | 默认值 | 说明 |
|---|---|---|
| `memory.memory_enabled` | `true` | 记忆开关 |
| `memory.user_profile_enabled` | `true` | 用户画像开关 |
| `memory.memory_char_limit` | `2200` | 记忆上下文预算（≈800 token） |
| `memory.user_char_limit` | `1375` | 用户画像预算（≈500 token） |
| `memory.nudge_interval` | `10` | nudge 轮次间隔（0=禁用） |
| `memory.flush_min_turns` | `6` | flush 最小轮次（0=禁用） |
| `memory.provider` | （内置） | 选择外部 provider |
| supermemory `max_recall_results` | `10` | 召回 Top-K |

---

## 2. Aether 现有记忆系统审计

### 2.1 模块边界

```
aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/memory/
├── MemoryFacade.java          (接口: remember/rememberMany/recall/search/drain/clear/stats)
├── DefaultMemoryFacade.java   (整合 EncodingFlow + RecallFlow + VectorStore)
├── EncodingFlow.java          (LLM 推断 categories/importance/shouldConsolidate)
├── RecallFlow.java            (shallow 加权召回 / deep LLM 查询分解+重排)
├── VectorStore.java           (接口: upsert/search/upsertBatch/delete/deleteByScope/dimension)
├── MemoryStore.java           (文件后端: JSON+MD, 关键词匹配)
├── MemoryRecord.java / MemoryScope.java / MemorySearchResult.java / MemorySlice.java
├── SessionMemoryExtractor.java
└── package-info.java

aether-infrastructure/.../repository/PgvectorVectorStore.java   (PostgreSQL+pgvector 后端)
```

依赖：spring-ai（`ChatModel`/`EmbeddingModel`）、Jackson、Spring JDBC。

### 2.2 接口审查（对照 hermes）

| hermes `MemoryProvider` 生命周期 | Aether 现状 | 差异 |
|---|---|---|
| `initialize(session_id)` | 无 | 缺失 |
| `prefetch(query)`（turn 前召回） | `search(query, scope, 5)` 仅存在于 `ChatService.injectMemory` | 语义近似但无围栏包装、无 nudge |
| `queue_prefetch` | 无 | 缺失 |
| `sync_turn`（turn 后写入） | **无** | **记忆从不写盘** |
| `on_session_end` / `on_session_switch` / `on_pre_compress` | 无 | 缺失 |
| `get_tool_schemas` / `handle_tool_call` | 无 | 缺失 |
| `shutdown` / `drain` | `DefaultMemoryFacade.drain()`（仅 shutdown executor） | 部分存在 |

### 2.3 不足点清单（附证据）

1. **数据结构完整性**：`MemoryRecord` 字段完整，但无 char 预算模型、无 user profile 专用存储（仅 `MemoryScope.user()`）、无 nudge 计数。
2. **检索精确度**：
   - `MemoryStore.search` 相关性评分是伪随机：`MemoryStore.java:294-297` `return 0.3 + Math.min(0.5, content.length() / 10000.0) + Math.random() * 0.2;` —— 与查询内容无关。
   - `PgvectorVectorStore.search` 回退为按 `last_accessed_at` 排序、相似度硬编码 `0.5`：`PgvectorVectorStore.java:139,142`。两套后端均未实现真实向量相似度检索。
3. **更新实时性**：`ChatService.injectMemory()`（L567-616）只读不写；对话内容从不持久化为记忆。
4. **配置灵活性**：仅 `aether.memory.pgvector.enabled` 一项；无 char limit / nudge / flush / Top-K / 权重配置。

---

## 3. 目标设计

### 3.1 模块拆分

```
cn.zcj.aether.domain.agent.service.memory.core           (新增)
├── MemoryProvider.java          — SPI（§3.2 签名）
├── MemoryManager.java           — 编排器：builtin + 最多一个外部 provider；turn 计数/nudge/drain/shutdown
├── BuiltinMemoryProvider.java   — name()="builtin"，委托现有 MemoryFacade
├── MemoryContextScrubber.java   — 单次+流式围栏净化
├── MemoryProperties.java        — @ConfigurationProperties("aether.memory")
├── MemoryLifecycleHooks.java    — ChatService 门面（prefetch/syncTurn/onSessionEnd/onPreCompress）
└── MemoryInitContext.java       — initialize 上下文 record

cn.zcj.aether.domain.agent.service.memory                (现有，保留)
└── MemoryFacade / EncodingFlow / RecallFlow / VectorStore (MemoryStore / PgvectorVectorStore)
```

**职责边界**：`MemoryManager` 管生命周期与策略；`MemoryFacade` 管存储与检索执行；`ChatService` 只依赖 `MemoryLifecycleHooks`。

### 3.2 接口签名

```java
public interface MemoryProvider {
    String name();
    boolean isAvailable();
    void initialize(String sessionId, MemoryInitContext ctx);
    default String systemPromptBlock() { return ""; }
    default String prefetch(String query, String sessionId) { return ""; }
    default void queuePrefetch(String query, String sessionId) {}
    default void syncTurn(String userContent, String assistantContent,
                          String sessionId, List<Map<String,Object>> messages) {}
    List<Map<String,Object>> getToolSchemas();
    default String handleToolCall(String toolName, Map<String,Object> args) {
        throw new UnsupportedOperationException(name() + " 不处理工具 " + toolName);
    }
    default void shutdown() {}
    // 可选钩子
    default void onTurnStart(int turnNumber, String message, Map<String,Object> kwargs) {}
    default void onSessionEnd(List<Map<String,Object>> messages) {}
    default void onSessionSwitch(String newSessionId, String parentSessionId,
                                 boolean reset, boolean rewound, Map<String,Object> kwargs) {}
    default String onPreCompress(List<Map<String,Object>> messages) { return ""; }
    default void onMemoryWrite(String action, String target, String content,
                               Map<String,Object> metadata) {}
}
```

```java
public final class MemoryManager {
    public static final String BUILTIN_NAME = "builtin";
    public void addProvider(MemoryProvider provider);            // builtin 恒接受；外部只许一个
    public String buildSystemPrompt();                            // systemPromptBlock + nudge
    public String prefetchAll(String query, String sessionId);
    public void syncAll(String userMsg, String assistantResponse,
                        String sessionId, List<Map<String,Object>> messages);
    public void queuePrefetchAll(String query, String sessionId);
    public List<Map<String,Object>> getAllToolSchemas();
    public void onSessionEnd(List<Map<String,Object>> messages);
    public void onSessionSwitch(String newSessionId, String parentSessionId, boolean reset);
    public void drain();                                          // 等所有后台写完成
    public void shutdown();
}
```

### 3.3 数据流

**读路径（prefetch）**：`query` → `BuiltinMemoryProvider.prefetch` → `memoryFacade.search(query, scope, topK)` → 格式化为 `<memory-context>` 围栏 → 注入 instruction `{memory}` 占位符 → 按 `memory-char-limit` 截断。

**写路径（syncTurn）**：`user+assistant` 文本 → 判定 scope → `memoryFacade.remember(content, scope, options)`（复用 `EncodingFlow` 编码 + `DefaultMemoryFacade` 相似度≥0.85 合并）→ 异步落盘（MemoryManager 单线程后台 executor，保证 turn 顺序）。

**scope 判定规则（确定性启发式，不依赖 LLM）**：文本命中用户画像关键词表（如"我喜欢/我偏好/请记住我喜欢/用户是/偏好/习惯"等，清单见实现）→ `MemoryScope.user()`；否则 → `MemoryScope.global().subscope("agent").subscope(agentId)`（agent 事实/约定）。`user-profile-enabled=false` 时恒走 agent 作用域。

**nudge / flush**：累计轮次达 `nudge-interval` 倍数 → system prompt 注入保存提醒；会话结束/压缩前，轮次 ≥ `flush-min-turns` → `onSessionEnd`。

**净化**：`MemoryContextScrubber` 应用在 `handleMessageStream` 的 `textDelta` 事件上。

### 3.4 配置对齐

见下表（写入 `application.yml` 的 `aether.memory` 段）：

| Aether 键 | hermes 键 | 默认值 | 语义 |
|---|---|---|---|
| `enabled` | `memory.memory_enabled` | `true` | 记忆总开关 |
| `user-profile-enabled` | `memory.user_profile_enabled` | `true` | 用户画像开关 |
| `memory-char-limit` | `memory.memory_char_limit` | `2200` | 注入预算（≈800 token） |
| `user-char-limit` | `memory.user_char_limit` | `1375` | 用户画像预算 |
| `nudge-interval` | `memory.nudge_interval` | `10` | nudge 间隔（0=禁用） |
| `flush-min-turns` | `memory.flush_min_turns` | `6` | flush 最小轮次（0=禁用） |
| `recall.max-results` | supermemory `max_recall_results` | `10` | prefetch Top-K |
| `recall.semantic-weight` | — | `0.6` | 语义权重 |
| `recall.recency-weight` | — | `0.3` | 时间衰减权重 |
| `recall.importance-weight` | — | `0.1` | 重要性权重 |
| `recall.consolidation-threshold` | — | `0.85` | 合并阈值 |
| `recall.vector-dimension` | — | `1280` | 向量维度 |
| `pgvector.enabled` | — | `true`（dev） | 现有，保留 |

### 3.5 集成点

| 文件 | 锚点 | 注入逻辑 |
|---|---|---|
| `ChatService.java` | `injectMemory()` L567 | 现有 `memoryFacade.search` 分支包装为 `hooks.prefetch(message, sessionId)`，走 `{memory}` 占位符 |
| `ChatService.java` | `handleMessage()` L207 | `agent.execute(...).blockingForEach` 完成后 `hooks.syncTurn(message, outputs.join, sessionId)` |
| `ChatService.java` | `handleMessageStream()` L281 | Flowable `doOnComplete` 累积 textDelta 后 `hooks.syncTurn(...)`；textDelta 经 scrubber |
| `ChatService.java` | `deleteSession()` L186 | 归档前 `hooks.onSessionEnd(sessionId)` |
| `application.yml` | `aether.memory` 段 | 新增 §3.4 全部键 |
| `pom.xml`（aether-domain） | — | 无新依赖 |

`MemoryLifecycleHooks` 以 `@Autowired(required=false)` 注入（与现有 `memoryFacade` 一致），无 EmbeddingModel/向量库时不影响启动。

### 3.6 错误处理

- 任一 provider 失败不阻塞其他 provider（对齐 hermes `MemoryManager`）。
- `syncTurn` 异步执行，异常仅告警不抛出。
- `drain()` 超时（5s）后 `shutdownNow`，中断信号上抛（对齐 hermes `_SYNC_DRAIN_TIMEOUT_S`）。
- `MemoryProperties` 绑定失败回退默认值。

---

## 4. 测试验收

1. `MemoryManagerTest`：builtin 恒接受；第二个外部 provider 被拒。
2. `MemoryContextScrubberTest`：单次剥除围栏/注记；流式跨分片标签状态机（feed/flush）。
3. `BuiltinMemoryProviderTest`（核心验收）：用内存版 VectorStore（真实余弦相似度）替换后端 —— 写入 10 条不同主题记忆 → `prefetch(query)` 检索 → 断言主题匹配的记忆被召回且排位靠前；验证存取关键路径逻辑完备性，CI 无需 Postgres。

---

## 5. 明确不做（YAGNI / 手术式修改）

- 不实现外部 provider（Honcho/Mem0/supermemory）的 Java 插件注册（方案 C 内容）。
- 不改动现有 `MemoryFacade`/`EncodingFlow`/`RecallFlow`/`VectorStore` 实现（含其检索缺陷，仅在本文档审计章节记录）。
- 不新增前端记忆类型（现有 `LongTermMemoryEntry` 保留）。
- 不引入 Python 记忆服务（对齐方式已确认走 Java 端重构）。

## 5.1 已知边界（本特性明确范围）

- **builtin `onSessionEnd` 为 no-op**：`flush-min-turns` 门控已接线并测试，但 builtin provider 不覆写 `onSessionEnd` —— 会话结束时的 LLM 事实抽取/总结属未来工作；该钩子为未来外部 provider 预留，会话结束不持久化额外内容。
- **`recall.vector-dimension` 为预留配置**：当前内置召回不直接消费向量维度，pgvector 后端按需使用。
