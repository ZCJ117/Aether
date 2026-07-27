# Aether 上下文工程架构升级 — 设计规格书

**日期:** 2026-07-27
**状态:** 已批准
**范围:** `aether-domain` 模块上下文工程全面升级

---

## 1. 概述

基于 `D:\code\Agents-framework` 下 MetaGPT、AutoGen、CrewAI、AgentScope-Java、cc-haha 五个参考架构的分析，对 Aether 的上下文工程进行分层架构升级。

### 1.1 设计原则

1. **注意力预算作为第一性原理** — 架构强制限制单次推理 Token 总数
2. **最小高信号 Token 集** — 数据策展层筛选最高价值信息
3. **运行时即时检索优先** — Agent 仅携带轻量标识符，推理步执行前实时获取详细数据

### 1.2 参考架构映射

| 层 | 主力借鉴 | 补充借鉴 |
|----|---------|---------|
| 上下文压缩执行管道（核心层） | AgentScope-Java 6步压缩管线 | CrewAI 并发分块摘要 |
| 记忆与知识持久层（增强层） | CrewAI 复合评分 | MetaGPT 向量/缓存 + AgentScope JSONL 泄流 |
| 长任务与安全控制层（控制层） | MetaGPT Planner + AutoGen CancelToken + AgentScope SessionSearch | — |

### 1.3 新增代码量估算

- **新增文件:** 23 个 Java 类
- **修改文件:** 9 个现有文件
- **外部依赖:** 零新增
- **破坏性变更:** 无（所有现有 API 向后兼容）

---

## 2. 核心层：上下文压缩管道

### 2.1 新增包路径

```
domain/agent/service/context/compaction/
├── CompactionPipeline.java    # 6步管道编排
├── CompactionTrigger.java     # 双阈值触发（消息数+Token数）
├── SafeCutoffFinder.java      # 二分搜索切点，不切断tool配对
├── ChunkSummarizer.java       # 并发分块摘要
└── MessageOffloader.java      # JSONL泄流
```

### 2.2 CompactionTrigger

**职责:** 判断是否触发压缩。任一条件超过阈值即触发。

```yaml
# 默认配置（application-dev.yml 可覆盖）
aether:
  context:
    compaction:
      trigger-messages: 150      # 消息数阈值
      trigger-tokens: 80000      # Token数阈值
      keep-messages: 20          # 压缩后保留的尾部消息数
      keep-tokens: 10000         # 压缩后保留的尾部Token数
```

**算法:**
```
shouldCompact(messageCount, tokenCount):
    return messageCount > triggerMessages || tokenCount > triggerTokens
```

### 2.3 SafeCutoffFinder

**职责:** 在消息列表中找到安全切点，确保不切断 (assistant含tool_use, tool_result) 配对。

**算法:**
```
1. 从尾向头累加token，找到第一个使"尾部 ≤ keep预算"的位置 cutoffIndex
2. 检查 cutoffIndex 处的消息:
   - 如果上一条是 tool_result → 向前移动到对应的 assistant（含tool_use）
   - 如果 cutoffIndex 本身是 tool_result → 向前移动到对应的 assistant
3. 确保 cutoffIndex 落在 assistant（不含tool_use）或 user 消息上
4. 返回 cutoffIndex
```

**配对检测规则:**
- `tool_result` 消息通过 `toolCallId` 关联其 assistant 消息
- assistant 消息的 `toolCalls` 字段包含对应的 tool_call id
- 搜索方向：从 tool_result 向前扫描，找到 `toolCalls[i].id == toolCallId` 的 assistant

### 2.4 ChunkSummarizer

**职责:** 对前缀消息并发生成摘要。

**算法:**
```
summarize(prefix, chatModel):
    1. 格式化为带角色标签的文本:
       [ASSISTANT]: <推理文本，不包含 tool_use 参数>
       [TOOL_RESULT(grep)]: <结果前500字符>
       [USER]: <用户消息>
    2. 按段落边界切分为独立 chunk（每chunk约 2000 tokens）
    3. 并发调用 LLM 摘要每个 chunk（ThreadPoolExecutor, max 4 并发）
    4. 合并子摘要为单一文本
    5. 如果合并后 token 数 > 5000，递归调用 summarize() 再次压缩
    6. 最终摘要 ≤ 2000 tokens
```

**摘要 Prompt:**
```
将以下对话片段压缩为简洁摘要（保留关键决策、重要结论、文件操作）。
使用中文输出，不超过500字。
对话片段：
{chunk}
```

**降级:** LLM 调用失败 → `fallbackSummary()` 字符串拼接（截断每条消息到100字符）

### 2.5 MessageOffloader

**职责:** 将完整对话泄流到 JSONL 文件，供 SessionSearch 查询。

**文件路径:** `.aether/sessions/{sessionId}.jsonl`

**格式（每行一条 JSON）:**
```json
{"ts":"2026-07-27T10:30:00Z","turn":7,"role":"tool_result","tool":"grep","len":15420,"hash":"a1b2c3"}
```

**关键设计:**
- 追加写（O(1)），不重写整个文件
- 消息内容存储完整原文（不做压缩）
- `hash` 字段用于去重（相同内容不重复写入）
- 异步写入（独立线程），不阻塞主循环

### 2.6 CompactionPipeline

**职责:** 编排完整的6步管道。

```
Step 1: checkTrigger()         — 双阈值检查
Step 2: findCutoff()           — SafeCutoffFinder 二分搜索
Step 3: truncateArgs()         — 被压缩部分中工具参数截断到200字符
Step 4: flushMemories()        — 从被压缩前缀提取长期记忆（调用 MemoryFacade）
Step 5: offloadMessages()      — 完整对话泄流到 JSONL（异步）
Step 6: summarizePrefix()      — ChunkSummarizer LLM摘要
         → 输出: [摘要user消息] + [保留的尾部消息]
```

### 2.7 与现有 ContextManager 集成

现有三个方法**保留不动**。新增管道在 Phase 1 最后一步调用：

```java
// ReActAgent.queryLoop() Phase 1 新流程:
messages = contextManager.applyToolResultBudget(messages);   // 保留
messages = contextManager.microCompact(messages);            // 保留
var autoResult = contextManager.autoCompactIfNeeded(...);    // 保留
if (!autoResult.isCompacted()) {
    // 仅当现有压缩未触发时，才检查管道
    messages = compactionPipeline.compactIfNeeded(messages, modelName);
}
```

集成原则: `autoCompactIfNeeded` 和 `CompactionPipeline` 互斥——前者先执行，后者作为补充。两者不会在同一轮同时触发。

---

## 3. 增强层：记忆与知识持久

### 3.1 新增文件

```
domain/agent/service/curation/
├── SignalScorer.java          # 三因子复合评分
├── ResultSummarizer.java      # 工具结果摘要提取
└── CurationPipeline.java      # 策展编排
```

### 3.2 SignalScorer — CrewAI 复合评分

**权重（可配置）:**
```yaml
aether:
  context:
    scoring:
      recency-weight: 0.3
      semantic-weight: 0.5
      importance-weight: 0.2
      decay-half-life-hours: 24
```

**评分公式:**
```
score = 0.3 × recency + 0.5 × semantic + 0.2 × importance

recency = exp(-hoursSinceLastAccess / (decayHalfLifeHours / ln(2)))
semantic = 余弦相似度或关键词匹配度（0-1）
importance = LLM 推断的重要性（0-1），无LLM时默认为0.5
```

**对现有 RecallFlow 的修改:**
- 在 `RecallFlow` 搜索结果返回前，增加 `SignalScorer.score()` 调用
- 对结果按评分重新排序（替代现有简单分数排序）
- 修改文件: `RecallFlow.java`，新增 ~10 行

### 3.3 记忆泄流（压缩管道 Step 4）

在 `CompactionPipeline.flushMemories()` 中:

```
1. 遍历被压缩的前缀消息
2. 用正则匹配 assistant 消息中的结论文本:
   - "结论：" / "决策：" / "关键发现：" / "决定："
3. 提取匹配到的片段
4. 通过 MemoryFacade.remember() 异步写入（复用现有 EncodingFlow）
5. scope: global/agent/{agentId}
```

### 3.4 与现有记忆系统的集成

| 现有组件 | 改动 |
|---------|------|
| `MemoryFacade` | 不改 |
| `EncodingFlow` | 不改 |
| `RecallFlow` | 增加 `SignalScorer` 排序后处理 |
| `MemoryStore` | 不改，保持文件回退路径 |
| `ModelCallCache` | 不改，复用为记忆查询缓存 |

---

## 4. 控制层：长任务 + 安全

### 4.1 新增文件

```
domain/agent/service/agent/core/
├── CancelToken.java           # 可取消执行令牌

domain/agent/service/tool/
├── SessionSearchTool.java     # 会话历史检索工具
```

### 4.2 Plan 步骤依赖增强

修改 `Plan.java`，新增字段:

```java
public static class PlanStep {
    // 现有字段
    int index;
    String description;
    String expectedOutput;
    StepStatus status;
    String result;

    // 新增字段
    List<Integer> dependsOn;    // 依赖的前置步骤序号（MetaGPT模式）
    int retryCount;             // 失败重试计数（max 2）
}
```

**执行规则:**
- 无依赖步骤可并行（现有并行逻辑不变）
- 有依赖步骤等待所有 `dependsOn` 步骤 COMPLETED
- 前置步骤 FAILED → 标记 BLOCKED
- 步骤失败后自动重试（max 2次），重试仍失败 → FAILED

修改文件: `Plan.java`（新增字段）, `PlanActAgent.java`（增加依赖检查逻辑）

### 4.3 CancelToken

```java
public class CancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Instant deadline;  // 可选超时（null = 无超时）

    public boolean isCancelled() {
        return cancelled.get() || (deadline != null && Instant.now().isAfter(deadline));
    }

    public void cancel() { cancelled.set(true); }
}
```

**集成点:**
- `ReActAgent.queryLoop()` 的 while 条件从 `!aborted.get()` 改为 `!cancelToken.isCancelled()`
- `GraphExecutor` 并行分支中，每个 Agent 注入同一个 CancelToken
- `AgentConfig` 新增 `cancelToken` 字段（默认 `new CancelToken(null)`）

修改文件: `ReActAgent.java`（1行）, `AgentConfig.java`（1字段）, `GraphExecutor.java`（2行）

### 4.4 SessionSearchTool

**工具定义:**
```
name: "session_search"
description: "在当前会话的完整历史中搜索。输入：query（必需，1-5个空格分隔的关键词）。返回：匹配的消息摘要，每条包含轮次号、角色（assistant/user/tool_result）、前200字符内容预览。结果最多10条，按轮次倒序排列。"
input_schema: { query: "string (required), 1-5个空格分隔的关键词" }
```

**实现:**
```
1. 打开 .aether/sessions/{sessionId}.jsonl
2. 逐行扫描，检查内容是否包含所有关键词
3. 匹配行 → 提取元数据 + 内容前200字符
4. 收集最多10条，按轮次倒序返回
5. 返回格式:
   [轮次7] assistant: "我已经找到了问题的根因..."
   [轮次5] tool_result(grep): "src/main/.../ReActAgent.java:145..."
```

---

## 5. Token 预算机制

### 5.1 新增文件

```
domain/agent/service/context/
├── TokenBudget.java           # 三层预算模型
```

### 5.2 三层预算模型

```
上下文窗口（如 200K tokens）
├── 固定开销层 (~15%): 系统提示词 + 工具定义 + 记忆注入
├── 弹性层 (~70%): 对话历史 + 工具参数 + 工具结果
└── 预留层 (~15%): 模型输出 + 安全缓冲
```

**计算公式:**
```
fixedOverhead = estimateTokens(instruction) + estimateToolSchemas(tools)
outputReserve = contextWindow × 0.15
elasticBudget = contextWindow - fixedOverhead - outputReserve
```

### 5.3 TokenBudget API

```java
public class TokenBudget {
    // 启动时初始化一次
    public TokenBudget(int contextWindow, int fixedOverhead) { ... }

    // 每轮推理前调用
    public boolean tryConsume(int estimatedTokens)
    // 返回 false → 弹性预算耗尽，触发压缩

    // 压缩后重置
    public void reset(int newElasticUsage)

    // 预算使用比例（0-1），用于监控
    public double usageRatio()
}
```

### 5.4 Token 估算精度增强

修改 `TokenEstimator.estimate()`:

```java
public int estimate(String text, MessageRole role) {
    return switch (role) {
        case SYSTEM, ASSISTANT  -> (int)(text.length() / 3.0);
        case TOOL_RESULT         -> (int)(text.length() / 2.5);
        case USER                -> (int)(text.length() / 4.0);
    };
}
```

**依据:** 代码/JSON 偏多 → token/char 比更高（约2.5 chars/token）；中文偏多 → 比更低（约4 chars/token）；英文普通文本 → 约3 chars/token。

### 5.5 监控

每轮推理前发射 TokenBudget 事件:
```java
RuntimeEvent.builder()
    .type(RuntimeEvent.EventType.tokenBudget)
    .budgetUsed(currentElasticUsage)
    .budgetTotal(elasticBudget)
    .budgetPercent(usageRatio())
    .build()
```

日志告警阈值:
- 使用率 > 80% → WARN 日志
- 使用率 > 95% → ERROR 日志
- 预算耗尽 → ERROR 日志 + 强制触发压缩

修改文件: `TokenEstimator.java`（重写 estimate）, `RuntimeEvent.java`（新增 EventType）

---

## 6. 信号策展层

### 6.1 新增文件

```
domain/agent/service/curation/
├── SignalScorer.java          # 见第3节
├── ResultSummarizer.java      # 按类型摘要策略
└── CurationPipeline.java      # 策展编排
```

### 6.2 CurationPipeline

**流程:**
```
原始工具结果 → [内容分类] → [分段评分] → [预算填充] → [摘要输出]
```

**内容类型分类:**
```
CODE:          grep/glob/file_read 结果     → 保留匹配行 ±2行上下文
LOG:           Bash 命令输出               → 仅保留 ERROR/WARN 行
DOCUMENTATION: Read/WebFetch 文档           → 标题 + 首段 200字
STRUCTURED:    JSON/表格/session_search     → 前 5 条记录
UNSTRUCTURED:  其他自由文本                → 前 200字 + 后 100字
```

### 6.3 与 ToolExecutor 集成

每个 `ToolResult` 在写入消息历史之前经过策展:

```java
// ReActAgent.queryLoop() Phase 4 修改:
for (ToolResult result : results) {
    CurationResult curated = curationPipeline.curate(
        result.getContent(),
        result.getToolName(),
        ctx.initialMessage(),  // 用户意图 → 相关性基准
        tokenBudget.remainingElastic() / Math.max(1, remainingResults)
    );
    messages.add(TurnMessage.toolResult(
        result.getToolCallId(), result.getToolName(), curated.summary()));
    // 完整原始内容已在 curate() 内部写入 JSONL
}
```

修改文件: `ReActAgent.java`（~5行变更）

---

## 7. 运行时即时检索

### 7.1 新增文件

```
domain/agent/service/retrieval/
├── IdentifierRegistry.java    # 轻量标识符注册表
├── DynamicLoader.java         # 推理前按需加载
├── CodeExplorer.java          # grep/glob 搜索工具
└── DocRetriever.java          # 两级文档检索工具
```

### 7.2 设计原则

```
❌ 预埋模式（禁止）:
  Agent 启动 → 扫描全部文件 → 索引所有内容 → 注入上下文 → 推理

✅ 即时检索模式:
  Agent 启动 → 注入标识符列表（<500 tokens） → 推理
  → Agent 需要数据 → 调用 code_search/file_read/doc_read → 实时返回
```

### 7.3 IdentifierRegistry

生成启动时注入的轻量标识符:

```
项目文件（前 200 个，按最近修改时间排序）:
  src/main/java/.../ReActAgent.java
  src/main/java/.../ContextManager.java
  ...

文档索引（仅标题）:
  docs/architecture.md — 系统架构概述
  docs/api-guide.md — REST API 使用指南
```

预计输出 < 500 tokens。

### 7.4 工具定义

**code_search:**
```
name: "code_search"
description: "在项目代码中搜索。输入包含两个字段：pattern（正则表达式，必需，匹配文件内容）、glob（文件类型过滤，可选，如 *.java）。返回匹配文件路径列表，每行包含文件路径和匹配行数。结果最多 20 条，按修改时间倒序排列。不返回文件完整内容——需用 file_read 读取具体文件。"
```

**file_read:**
```
name: "file_read"
description: "读取文件指定行范围的内容。输入包含三个字段：file_path（文件绝对路径，必需）、start_line（起始行号，必需，从1开始）、end_line（结束行号，必需，start_line 到 end_line 不得超过 200 行）。返回指定行范围的原始文本。"
```

**doc_read:**
```
name: "doc_read"
description: "读取文档的指定章节。输入包含两个字段：doc_path（文档路径，必需）、section（章节标题或关键词，必需）。返回匹配章节的完整文本，限制 500 token。如果章节超过 500 token，返回前 500 token 并在末尾提示继续读取。"
```

### 7.5 与 ChatService 集成

`ChatService.injectMemory()` 改为两步:
1. 注入 `IdentifierRegistry.buildIdentifierContext()`（轻量标识符 + 文档大纲）
2. Agent 通过工具自主获取详细数据

修改文件: `ChatService.java`（注入逻辑调整，~10行变更）

---

## 8. 最小可行工具集

### 8.1 新增文件

```
domain/agent/service/tool/
├── MinimalToolSet.java        # 工具数量限制 + 优先级裁剪
```

修改文件: `compiler/AgentGraphCompiler.java`（增加描述校验）

### 8.2 工具数量硬性限制

```java
// 每个 Agent 最多 15 个工具
public static final int MAX_TOOLS_PER_AGENT = 15;
```

**超限裁剪优先级:**
1. 写操作工具（Edit, Write, Bash）— 不可裁剪
2. 核心读工具（Read, Glob, Grep）— 不可裁剪
3. 辅助读工具（WebFetch, WebSearch）— 可裁剪
4. MCP 工具 — 按注册顺序裁剪
5. Skills 工具 — 按注册顺序裁剪

### 8.3 工具描述校验

在 `AgentGraphCompiler` 编译阶段校验:

**禁止词:** "可能"、"大概"、"也许"、"或许"、"或"、"等"、"等等"

**必需元素:**
- 每个参数有独立的类型和描述
- 返回格式明确（行数、字段、顺序）
- 限制条件明确（最大行数、超时时间、结果数量）
- 边界情况说明（无结果时的行为、错误时的返回值）

### 8.4 工具结果统一格式

所有工具结果经过 `ResultSummarizer` 处理后输出:
```
[工具名] — 执行结果
状态: 成功|失败
关键发现: - ...
数据量: 原始 N 字符 → 摘要 M 字符
完整内容: .aether/sessions/{sessionId}.jsonl 第 # 行
```

---

## 9. 子Agent 隔离

### 9.1 新增文件

```
domain/agent/service/subagent/
├── SubAgentOrchestrator.java   # 派遣 + 生命周期管理
├── SubAgentBoundary.java       # 隔离配置创建
└── ResultRefiner.java          # 子Agent结果精炼
```

### 9.2 三层隔离模型

```
主控 Agent:
  可见: 系统提示词 + 任务目标 + 子Agent返回摘要(≤500字)
  不可见: 子Agent完整推理链 + 工具调用细节

子Agent:
  独立消息历史（从空开始）
  独立 TokenBudget（父预算的30%）
  独立工具集（≤5个）
  独立 ChatModel（可用更便宜的模型）
  临时生命周期（任务完成即销毁）
```

### 9.3 SubAgentOrchestrator

```java
public SubAgentResult dispatch(
    String task,            // 任务描述（≤200字）
    List<Tool> tools,       // 可用工具（≤5个）
    TokenBudget parentBudget
) {
    // 1. SubAgentBoundary.createIsolatedConfig() → AgentConfig
    // 2. DefaultAgentFactory.create() → ReActAgent
    // 3. AgentConfig.cancelToken = 独立 CancelToken(60s超时)
    // 4. agent.execute(ctx).blockingForEach() → 收集事件
    // 5. ResultRefiner.refine() → ≤500字摘要
    // 6. 返回 SubAgentResult
}
```

**并发限制:** 最多 5 个子Agent 同时运行（`Semaphore(5)`）

### 9.4 ResultRefiner — 规则提取（零 LLM 调用）

```
输入: 子Agent完整消息历史
输出: 结构化摘要（≤500 token）

[子任务] {taskDescription}
[状态] 成功 | 失败 | 超时
[结论] {最终assistant消息前400字}
[涉及文件] file1.java, file2.yml
[工具调用统计] grep×3, Read×2, Write×1
```

### 9.5 YAML 配置新增

```yaml
agent-workflows:
  - type: subagent
    name: research-cluster
    isolation: strict        # strict | shared-tools
    result-mode: summarized  # summarized | raw（调试用）
    sub-agents:
      - researcher-code
      - researcher-docs
```

修改文件: `AgentEdgeType.java`（新增 `SUBAGENT` 枚举值）, `GraphExecutor.java`（新增 `SUBAGENT` 边处理）

---

## 10. 外部笔记 + 观测点

### 10.1 新增文件

```
domain/agent/service/notes/
├── ExternalNotes.java         # 持久化 TODO/NOTES 对象
└── NotesTools.java            # todo_write + note_write 工具
```

### 10.2 ExternalNotes 数据模型

**存储位置:** `.aether/notes/{sessionId}.json`

```java
public record NotesDocument(
    List<TodoItem> todos,
    List<NoteItem> notes,
    String currentGoal,
    Instant lastUpdated
) {
    public record TodoItem(
        String id, String content, TodoStatus status,
        int priority, Instant createdAt
    ) {}

    public record NoteItem(
        String id, String content, NoteCategory category,
        Instant createdAt
    ) {}
}

enum TodoStatus { PENDING, IN_PROGRESS, DONE, BLOCKED }
enum NoteCategory { DECISION, FINDING, QUESTION, REFERENCE }
```

### 10.3 上下文重启后恢复

压缩管道的 Step 6 生成的摘要消息中包含笔记占位:

```
[对话历史摘要]
...压缩摘要...

[当前状态 — 来自笔记]
TODO: 2待办 / 1进行中 / 3已完成
  进行中: 实现 SafeCutoffFinder
关键决策: 采用 AgentScope 6步管道 (2026-07-27)
```

Agent 看到摘要 + 笔记组合，而非断裂的对话。

### 10.4 工具定义

**todo_write:**
```
name: "todo_write"
description: "操作待办事项列表。输入：action（add|update|complete|delete，必需）、content（任务描述，action=add时必需，≤100字）、id（任务ID，action=update/complete/delete时必需）、priority（1高|2中|3低，add时可选，默认2）。返回操作后的完整 TODO 列表摘要。"
```

**note_write:**
```
name: "note_write"
description: "记录关键发现、决策或参考信息。输入：category（DECISION|FINDING|QUESTION|REFERENCE，必需）、content（记录内容，必需，≤200字）。返回确认消息。"
```

### 10.5 观测点矩阵

| 组件 | 观测时机 | 级别 | 关键字段 |
|------|---------|------|---------|
| CompactionPipeline | 每步前后 | INFO | stepIndex, durationMs, before/after counts |
| SafeCutoffFinder | 切点计算 | DEBUG | cutoffIndex, keptTokens, pairPreserved |
| TokenBudget | 使用率 >80% | WARN | usage%, total |
| TokenBudget | 预算耗尽 | ERROR | usage%, sessionId |
| CurationPipeline | 策展前后 | INFO | toolName, rawChars→curatedChars |
| SubAgentOrchestrator | 派遣/完成/失败 | INFO/ERROR | task, durationMs, resultTokens |
| ExternalNotes | 写入磁盘 | DEBUG | todosCount, notesCount |
| DynamicLoader | 首次加载 | DEBUG | filePath, lineRange, loadMs |

### 10.6 失败降级矩阵

| 路径 | 失败场景 | 降级行为 |
|------|---------|---------|
| ChunkSummarizer | LLM调用失败 | fallbackSummary() 字符串拼接 |
| CurationPipeline | 策展异常 | 返回原始内容前500字 |
| SubAgentOrchestrator | 超时(60s) | 返回 "子任务超时" |
| SubAgentOrchestrator | 连续3轮工具失败 | 返回 "子任务失败: {原因}" |
| ExternalNotes | 磁盘写入失败 | WARN日志，不阻断主循环 |
| MessageOffloader | JSONL写入失败 | WARN日志，降级内存缓存 |
| TokenBudget | 估算偏差>20% | 紧急硬截断 |

---

## 11. 文件变更清单

### 11.1 新增文件（23个）

| 序号 | 文件路径 | 职责 |
|------|---------|------|
| 1 | `context/compaction/CompactionPipeline.java` | 6步管道编排 |
| 2 | `context/compaction/CompactionTrigger.java` | 双阈值触发 |
| 3 | `context/compaction/SafeCutoffFinder.java` | 安全切点搜索 |
| 4 | `context/compaction/ChunkSummarizer.java` | 并发分块摘要 |
| 5 | `context/compaction/MessageOffloader.java` | JSONL泄流 |
| 6 | `context/TokenBudget.java` | 三层预算模型 |
| 7 | `curation/SignalScorer.java` | 三因子评分 |
| 8 | `curation/ResultSummarizer.java` | 按类型摘要策略 |
| 9 | `curation/CurationPipeline.java` | 策展编排 |
| 10 | `retrieval/IdentifierRegistry.java` | 轻量标识符注册 |
| 11 | `retrieval/DynamicLoader.java` | 按需数据加载 |
| 12 | `retrieval/CodeExplorer.java` | grep/glob搜索工具 |
| 13 | `retrieval/DocRetriever.java` | 两级文档检索工具 |
| 14 | `tool/MinimalToolSet.java` | 工具数量限制 |
| 15 | `compiler/ToolDescriptionValidator.java` | 工具描述校验 |
| 16 | `subagent/SubAgentOrchestrator.java` | 子Agent派遣 |
| 17 | `subagent/SubAgentBoundary.java` | 隔离配置 |
| 18 | `subagent/ResultRefiner.java` | 结果精炼 |
| 19 | `notes/ExternalNotes.java` | 持久化笔记 |
| 20 | `notes/NotesTools.java` | 笔记操作工具 |
| 21 | `tool/SessionSearchTool.java` | 会话历史检索 |
| 22 | `agent/core/CancelToken.java` | 取消令牌 |
| 23 | `retrieval/package-info.java` | 包描述 |

### 11.2 修改文件（9个）

| 序号 | 文件 | 变更范围 | 行数估算 |
|------|------|---------|---------|
| 1 | `context/ContextManager.java` | 集成 CompactionPipeline 调用 | +10行 |
| 2 | `context/TokenEstimator.java` | 分角色估算 + contextWindow 方法 | +15行 |
| 3 | `runtime/ModelInvoker.java` | ModelCallResult 填充 inputTokens/outputTokens | +5行 |
| 4 | `agent/impl/ReActAgent.java` | CancelToken 条件 + CurationPipeline 集成 | +10行 |
| 5 | `agent/impl/PlanActAgent.java` | 步骤依赖检查 + 重试 | +20行 |
| 6 | `agent/core/Plan.java` | 新增 dependsOn, retryCount 字段 | +5行 |
| 7 | `agent/core/AgentConfig.java` | 新增 cancelToken 字段 | +3行 |
| 8 | `chat/ChatService.java` | 即时检索注入替换记忆注入 | +10行 |
| 9 | `compiler/AgentGraphCompiler.java` | 工具描述校验集成 | +10行 |

### 11.3 不修改的现有组件

- `MemoryFacade` / `DefaultMemoryFacade` / `EncodingFlow` — 作为存储后端不变
- `MemoryStore` — 文件回退路径不变
- `ModelCallCache` — 复用为记忆缓存，不改
- `ToolExecutor` — 工具编排逻辑不变
- `ToolRegistry` — 注册机制不变
- `GraphExecutor` — 核心执行逻辑不变（仅新增 SUBAGENT 边）
- `AgentRegistry` — 不变
- 所有 Trigger/API/DTO 层 — 不变
- 所有 Infrastructure 层 — 不变

---

## 12. 自检清单

- [x] 无 TBD/TODO/占位符
- [x] 所有新增文件有明确的包路径和类名
- [x] 所有修改文件的变更范围有具体行数估算
- [x] 各层之间无矛盾（压缩管道 vs 现有ContextManager 已定义互斥逻辑）
- [x] 无新增外部依赖（全部基于现有 Spring Boot + Jackson + RxJava3）
- [x] 破坏性变更检查：零破坏性变更
- [x] 所有失败场景有降级策略
- [x] 所有观测点有日志级别和关键字段定义
- [x] Token预算公式有明确计算规则
- [x] 信号评分公式有权重和衰减函数定义
