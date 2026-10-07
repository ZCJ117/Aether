# microCompact 写操作去重修复设计

> 日期：2026-10-07
> 状态：**已实现**（2026-10-07；实现记录与三处偏差见 §11）
> 范围：`aether-domain` 上下文工程模块，落点唯一文件 `ContextManager.java`
> 关联：`docs/superpowers/specs/2026-07-27-aether-context-engineering-upgrade-design.md`（microCompact 首次引入）
> 证据口径：所有结论均附 `文件:行号`，已逐条实读核对

---

## 1. 背景与目标

`microCompact`（`ContextManager.java:283-340`）的设计意图是：同一文件被多次写入时，只有**最后一次写**对后续推理有意义，此前的写操作及其工具结果可以从上下文中剔除，从而在不损失语义的前提下压缩 token。

它的类注释（`:41`）把它列为上下文工程的链条之一；`ReActAgent` 在**每轮循环**（`:264`）和**恢复分支**（`:883`）各调用一次。

**问题是：这个方法在生产路径上从未真正生效过。** 见 §2 缺陷 A。

### 1.1 目标

1. 让 microCompact 在**真实生产消息形状**下生效；
2. 修复过程中暴露的两处正确性缺陷（配对方式、写工具白名单）；
3. **不破坏** tool_use / tool_result 配对不变式——这是本方法唯一不可让步的约束，因为配错会直接导致模型 API 报错。

### 1.2 成功标准（可验证）

1. 新增测试以生产形状（`TurnMessage.assistantWithToolCalls`）构造消息，断言被覆盖的写操作**确实被移除**；该测试在修复前**必须失败**。
2. 配对不变式测试：压缩后列表中每条 tool_result 都能按 `toolCallId` 找到对应的 tool_use，反之亦然。
3. `mvn -pl aether-domain test -Dtest=ContextManagerTest` 通过。
4. `ReActAgentCompressTest` / `ReActAgentGuardrailTest` / `EvalEngines` 等既有 mock 点不受影响（签名不变）。
5. 非写操作（Read/Bash 等）所在的消息**绝不**被移除——用测试锁定。

---

## 2. 现状实证（缺陷证据）

### 缺陷 A（P0）：判据形状不匹配 → 整个方法是空操作

Pass 1 的判据是 `msg.isToolUse()`（`:291`、`:307`），而该判据的实现是：

```java
// TurnMessage.java:39-41
public boolean isToolUse() {
    return "tool_use".equals(role);
}
```

**但生产代码从不产生 `role="tool_use"` 的消息。** 证据链：

| 环节 | 证据 |
|---|---|
| 全项目 `"tool_use"` 字面量仅一处 | `grep -rn '"tool_use"' --include=*.java` 在 main 源集只命中 `TurnMessage.java:40`（即判据自身的比较操作数） |
| 工具调用一律走这条路径 | `ReActAgent.java:385-394`：把整批 `modelResult.getToolCalls()` 映射为 `tcMeta` 后调用 `TurnMessage.assistantWithToolCalls(...)` |
| 该工厂产出的 role | `TurnMessage.java:31-34`：`new TurnMessage("assistant", text, null, null, toolCalls)` |

因此 `isToolUse()` 恒为 false → `lastWriteIndex` 恒为空 → `:299-301` 提前返回原列表。

**为什么测试是绿的**：`ContextManagerTest.java:246`、`:252`、`:288` 手工构造了 `new TurnMessage("tool_use", ...)`——一个生产**从不产生**的形状。测试锁定的是一种想象出来的消息形状，所以功能死了而测试全绿。

**旁证（说明作者知道有两种形状）**：同一文件 `:184-186` 的 `isDanglingToolUse` 写对了——

```java
private boolean isDanglingToolUse(TurnMessage msg) {
    return msg.isToolUse() || msg.hasToolCalls();
}
```

microCompact 漏掉了 `|| msg.hasToolCalls()`。

**影响**：本方法的全部收益（每轮写操作去重）为零；且由于它排在 `autoCompactIfNeeded` 之前（`ReActAgent:264-266`），本可省下的 token 全部转嫁给后续的 LLM 摘要压缩——**多花一次摘要调用的钱**。

### 缺陷 B（P1）：按位置配对，而非按 toolCallId

```java
// ContextManager.java:314-317
removeIndices.add(i);                                    // tool_use
if (i + 1 < n && messages.get(i + 1).isToolResult())     // 紧邻的下一条
    removeIndices.add(i + 1);
```

注释（`:280-281`）声称"天然配对安全……无需额外配对守卫"。该论断只在「一 tool_use 紧跟一 tool_result」的串行形状下成立。真实形状是一条 assistant 消息携带 N 个 toolCall（`ReActAgent:385-394`），其后跟 N 条连续 tool_result。此时按 `i+1` 只删一条，其余成为**孤儿 tool_result**——而 `TurnMessage` 明明就有 `toolCallId` 字段可用于精确配对。

注：缺陷 A 目前**掩盖**了缺陷 B——方法整体不生效，所以配对错误也从未触发。修好 A 会让 B 立即变成真实风险，两者必须同批修复。

### 缺陷 C（P1）：BashTool 的路径是猜出来的

```java
// ContextManager.java:113-116
private static final Set<String> EDIT_TOOL_NAMES = Set.of(
        "Edit", "FileEdit", "Write", "FileWrite",
        "FileEditTool", "FileWriteTool", "BashTool"   // ← 任意 shell 命令
);
```

BashTool 的"写目标"由 `extractPathFromCommand`（`:742-753`）从命令行分词后取**第一个含 `/` 或 `.` 且不以 `-` 开头的参数**。实测误判：

| 命令 | 抠出的路径 | 真实写目标 | 后果 |
|---|---|---|---|
| `mv a.txt b.txt` | `a.txt` | b.txt（被覆盖） | 漏判 |
| `python gen.py > report.md` | `gen.py` | report.md（被覆盖） | 漏判 |
| `rm -rf build/` | `build/` | build/ | 勉强对 |
| `cp a b` | `a` | b | 漏判 |

误判方向对**本算法是危险的**：算法的删除条件是"该路径后来又被写过"。若把 `mv a.txt b.txt` 误认为"写了 a.txt"，而 a.txt 恰好被写于更早、其后又有别的命令碰过 a.txt，就会把一个**其实没被覆盖**的写当冗余删掉。**误删比漏删危险**——漏删只是少省 token。

同理，`extractPath`（`:709-737`）里为 BashTool 服务的 `command` 分支（`:726-729`）也随白名单收紧而失去调用者。

---

## 3. 澄清结论（用户拍板）

| 问题 | 用户确认 | 理由 |
|---|---|---|
| Q1 删除粒度 | **整条消息仅在全部 call 都被覆盖时才删** | 配对绝对安全，零消息重建逻辑；含任一非写 call（如 Read）或任一"最后一次写"即整条跳过 |
| Q2 BashTool | **移出写工具白名单** | 误判方向是误删，保守侧才是安全侧 |
| Q3 占位提示 | **留一行占位** | 消除"assistant 说写了 X 却看不到写操作"的悬空引用，与仓库既有占位风格一致 |

### 我方对 Q3 预览的一处修正（需用户知悉）

Q3 选项预览里我把占位画成了 `[tool_result]` 角色。**这是错的，实施时改用 `user` 角色**：

- 一条 `role="tool_result"` 的消息若其 `toolCallId` 对应的 tool_use 已被删除，本身就是**孤儿 tool_result**——恰好违反了本方案唯一不可让步的配对不变式；
- 仓库既有先例用的就是 user 角色：`trimMessages` 的占位是 `TurnMessage.user("[系统提示] 已跳过 N 条较早消息…")`（`:230`）。

占位文本（复用 `[系统提示]` 前缀，与 `:228` 一致）：

```
[系统提示] 对 config.yml 的写入已被后续写入覆盖，已省略
```

多路径时以 `、` 连接。

---

## 4. 设计

### 4.1 判据统一（消除缺陷 A 的根因）

把 `:185` 已经写对的判据提取为方法，三处复用：

```java
/**
 * 消息是否携带工具调用。
 * 两种形状：独立 tool_use 消息（role=tool_use）/ 含 toolCalls 的 assistant 消息（生产形状）。
 */
private boolean carriesToolCalls(TurnMessage msg) {
    return msg.isToolUse() || msg.hasToolCalls();
}
```

`isDanglingToolUse`（`:184-186`）改为委托调用。这是**行为不变**的改写，目的是让"两种形状"这件事只在一处表达——缺陷 A 的根因就是同一判据在两处写法不一致。

> 说明：`:185` 本身没有 bug，改写它属于「防止同类分歧再次发生」的最小代价（1 行）。若评审认为超出手术式修改边界，可只改 microCompact 内部，保留 `:185` 原样——两者行为等价。

### 4.2 写调用提取

统一从消息中提取「写调用」列表。注意生产形状的 `input` 是 **`Map<String,Object>`**，不是 JSON 字符串（证据：`ReActAgent:390` 放入 `tc.getInput()`，而 `ToolExecutor.ToolCallRequest` 的 `input` 参数类型为 `Map<String, Object>`，`:455`）：

```java
private record WriteCall(String path) {}   // path 已归一化，可为 null

private List<WriteCall> extractWriteCalls(TurnMessage msg) {
    List<WriteCall> out = new ArrayList<>();
    if (!msg.hasToolCalls()) return out;           // 只认生产形状
    for (Map<String, Object> tc : msg.toolCalls()) {
        Object name = tc.get("name");
        if (name == null || !isEditTool(String.valueOf(name))) continue;
        out.add(new WriteCall(normalizePathOrNull(pathFromInput(tc.get("input")))));
    }
    return out;
}
```

`pathFromInput(Object input)`：`input instanceof Map` → 依次读 `file_path` / `filePath` / `path`，命中即返回；`input instanceof String` → 委托现有 `extractPath`。**不再处理 `command` 键**（缺陷 C）。

`normalizePathOrNull`：包装既有 `normalizePath`，入参 null 时返回 null。

> **修订（2026-10-07，评审驱动，详见 §12）**：本节原设计同时支持形状 B（独立 `tool_use` 消息），并让 `WriteCall` 携带 `toolName`。两处均已收窄：形状 B 全项目无产出方，不为它保留分支；`toolName` 无消费者，去掉。

### 4.3 保守删除规则（Q1 落地）

一条消息可被整条移除，当且仅当以下全部成立：

1. `carriesToolCalls(msg)` 为真；
2. 该消息的**全部** call 都是写工具（`writes.size() == calls.size()`）；
3. 每个写调用的路径都**可提取**（无 null）——抠不出路径就无法证明它被覆盖，按不可删处理；
4. 每个写调用的路径都**已被后续写覆盖**（`lastWriteIndex.get(path) != i`）；
5. 该消息的全部 toolCallId **可解析**（见 §4.4）。

条件 2 直接实现 Q1：消息里混着 Read 之类非写 call 时整条跳过，绝不做消息重建。
条件 3 是对缺陷 C 的兜底：即使将来又加了新的写工具，抠不出路径也只是"不删"，不会"误删"。

### 4.4 配对删除（修缺陷 B）

按 `toolCallId` 精确配对，不再用 `i+1`：

```java
Set<String> ids = 该消息的全部 toolCallId（非空）;
if (ids.isEmpty()) continue;                    // 无法证明配对 → 整个消息不删（保守）
for (int j = i + 1; j < n && messages.get(j).isToolResult(); j++) {
    if (ids.contains(messages.get(j).toolCallId())) removeIndices.add(j);
    else break;                                  // 遇到不属于本消息的结果即停手
}
```

「ids 不可解析则整条不删」这条兜底直接覆盖了现有测试里 `tool_use.toolCallId = null` 而 `tool_result.toolCallId = "tc-1"` 的不一致形状——旧测试那种形状在新规则下会走"不删"分支（见 §7.2）。

### 4.5 主流程

```java
public List<TurnMessage> microCompact(List<TurnMessage> messages) {
    int n = messages.size();

    // Pass 1: normalizedPath -> 最后一次写所在的消息索引（顺序覆盖，剩余的即最后一次）
    Map<String, Integer> lastWriteIndex = new LinkedHashMap<>();
    for (int i = 0; i < n; i++)
        for (WriteCall wc : extractWriteCalls(messages.get(i)))
            if (wc.path() != null) lastWriteIndex.put(wc.path(), i);
    if (lastWriteIndex.isEmpty()) return new ArrayList<>(messages);

    // Pass 2: 判定可移除的消息 + 其配对的 tool_result
    Map<Integer, String> placeholders = new LinkedHashMap<>();   // msgIndex -> 占位文本
    Set<Integer> removeIndices = new HashSet<>();
    for (int i = 0; i < n; i++) {
        TurnMessage msg = messages.get(i);
        if (!carriesToolCalls(msg)) continue;

        List<Map<String,Object>> calls = callsOf(msg);
        List<WriteCall> writes = extractWriteCalls(msg);
        if (writes.size() != calls.size()) continue;                        // §4.3-2
        if (writes.stream().anyMatch(w -> w.path() == null)) continue;      // §4.3-3
        if (writes.stream().anyMatch(w -> lastWriteIndex.get(w.path()) == i)) continue; // §4.3-4

        Set<String> ids = callIdsOf(msg);
        if (ids.isEmpty()) continue;                                        // §4.3-5

        placeholders.put(i, placeholder(writes));
        removeIndices.add(i);
        for (int j = i + 1; j < n && messages.get(j).isToolResult(); j++) { // §4.4
            if (ids.contains(messages.get(j).toolCallId())) removeIndices.add(j);
            else break;
        }
    }
    if (placeholders.isEmpty()) return new ArrayList<>(messages);

    // Pass 3: 重建 —— 移除处替换为占位（user 角色，配对中立）
    List<TurnMessage> kept = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
        if (placeholders.containsKey(i)) kept.add(TurnMessage.user(placeholders.get(i)));
        else if (!removeIndices.contains(i)) kept.add(messages.get(i));
    }
    return kept;
}
```

`lastWriteIndex.get(w.path()) == i` 用 `Integer` 与 `int` 比较会自动拆箱，此处 `i` 是 `int`，左侧必非 null（Pass 1 已保证该 path 有记录），安全。

### 4.6 缺陷 C 落地

1. `EDIT_TOOL_NAMES`（`:113-116`）移除 `"BashTool"`，只保留 6 个结构化写工具。
2. 删除 `extractPathFromCommand`（`:742-753`）——移除 BashTool 后无调用者。
3. 删除 `extractPath` 中的 `command` 分支（`:726-729`）——同上。
4. **保留** `extractPath` 的 JSON 分支与 `extractPathFromText` 兜底（`:735-736`）：形状 B 仍可能传入非 JSON 的 `content`。

---

## 5. 改动清单

| 文件 | 位置 | 改动 | 类型 |
|---|---|---|---|
| `ContextManager.java` | `:113-116` | `EDIT_TOOL_NAMES` 移除 `BashTool` | 修改 |
| `ContextManager.java` | `:184-186` | `isDanglingToolUse` 委托新方法 | 改写（行为不变） |
| `ContextManager.java` | `:283-340` | `microCompact` 按 §4.5 重写 | 重写 |
| `ContextManager.java` | `:184-186` | 删除（Middle Man：单行转发，直接调用 `carriesToolCalls`） | 删除 |
| `ContextManager.java` | 新增 | `carriesToolCalls` / `extractWriteCalls` / `callIdsOf` / `pathFromInput` / `normalizePathOrNull` / `placeholderOf` / `WriteCall` record | 新增 |
| `ContextManager.java` | `:726-729` `:742-753` | 删除 BashTool 专用路径提取 | 删除 |
| `ContextManager.java` | `:274-282` | 更新 Javadoc：说明两种消息形状、保守规则、占位 | 文档 |
| `ContextManagerTest.java` | `:241-297` | 见 §7.2 | 测试 |

**不改动**：`ReActAgent.java`（`:264`、`:883` 调用点签名与语义不变，`:288-291` 的统一写回已覆盖）、`TurnMessage.java`、`CompactionPipeline.java`。

---

## 6. 已知限制与明确不修范围

### 6.1 接受的能力损失（Q1 的代价）

一条 assistant 消息里混着 `[Write A, Read B]` 时整条跳过。因此**并行批次里混有写操作的场景，microCompact 收益为 0**。这是为"绝不重建消息、绝不破坏配对"付出的代价，已由用户拍板接受。串行单调用场景（当前主力场景）不受影响。

### 6.2 删写不删读带来的语义缺口

microCompact 只删被覆盖的写，**不碰 Read**。因此"Read A → 据此写 B"这条因果链里的 Read A 永远保留，"B 为什么长这样"的线索不会断。这是当前设计最重要的一层自我保护——但它是**副产品，不是刻意设计**，不应作为文档承诺。

仍会出问题的是**路径相同、语义不同**：先写 `config.yml` 的 A 版 → 覆盖成 B 版 → 之后在对话里引用"A 版里配了 foo"。该引用失去依据。§4.5 的占位消息把这种情况从"静默消失"降级为"模型可见此处有一次被省略的写"，但**无法恢复内容**。

### 6.3 同类判据问题（本 spec 不修，仅记录）

以下两处使用了与缺陷 A 相同的 `msg.isToolUse()` 判据，因而在生产形状下同样不生效：

| 位置 | 意图 | 现状 |
|---|---|---|
| `CompactionPipeline.java:174` | 截断 prefix 中工具调用 args 至 200 字符 | 生产形状下 `isToolUse()`=false，且 args 也不在 `content` 而在 `toolCalls[].input` → 该步骤同样是空操作 |
| `ChunkSummarizer.java:125` | 摘要时识别工具调用 | 同上，退化为按 content 处理 |

按要求本批不动它们（手术式修改）。**建议**：修复落地后单开一批处理，判据统一为 §4.1 的 `carriesToolCalls`。

### 6.4 每轮调用而非阈值触发

`microCompact` 在 `ReActAgent:264` 每轮无条件执行，不像 `autoCompactIfNeeded` 有阈值。修复生效后这会变成**每轮都删历史写操作**——收益是 token 恒定更低，代价是信息恒定更少。本 spec 不改变这个触发策略（超出缺陷修复范围），但它是落地后最值得观察的行为变化。

---

## 7. 测试计划

### 7.1 核心教训：测试形状必须等于生产形状

缺陷 A 能存活至今，唯一原因是**测试构造了一个生产不可能产生的消息形状**。因此本批新测试的第一条纪律：

> 新增的 microCompact 测试**一律**用 `TurnMessage.assistantWithToolCalls(...)` 构造消息。禁止使用 `new TurnMessage("tool_use", ...)`。

建议在测试类注释中写明这条，防止回归。

### 7.2 现有测试的处置

| 测试 | 现状 | 处置 |
|---|---|---|
| `microCompactShouldPreservePairingIntegrity`（`:241-278`） | 用 shape B，且 `tool_use.toolCallId=null` / `tool_result.toolCallId="tc-1"` 不一致 | **改造为 shape A**（`assistantWithToolCalls`），并给 toolCalls 里每个 call 一个真实 id，tool_result 用同一 id |
| `microCompactShouldNotRemoveLastWrite`（`:284-297`） | 同上 | 同上 |

改造后这两个测试才有资格作为配对不变式的守卫。原断言（保留段里每条 tool_use 后必跟 tool_result、每条 tool_result 前必有 tool_use）**保留不动**——断言是对的，只是喂进去的数据不真实。

### 7.3 新增测试（`ContextManagerTest`）

| # | 用例 | 断言 | 修复前 |
|---|---|---|---|
| T1 | 生产形状：同一路径两次 `Write`（各一条 assistant 消息） | 第一次的消息及其 tool_result 被移除，第二次保留；占位消息存在且 role=user | **失败**（当前不删） |
| T2 | 配对不变式（生产形状，含 `[Write A, Read B]` 并行批次） | 含 Read 的消息整条不被移除（Q1 保守规则） | 失败 |
| T3 | 一条消息携带 N 个 toolCall，全部为同一路径的写 | 该消息与**全部** N 条 tool_result 一并移除，无孤儿 | 失败 |
| T4 | 路径可提取性：`Write` 的 input 无 `file_path`/`path` 键 | 整条不删（条件 3 兜底），列表不变 | 通过（空操作） |
| T5 | BashTool 已出白名单 | `BashTool` 的 toolCall 不参与去重 | 通过（空操作） |
| T6 | 配对不变式全量守卫 | 压缩结果中，每条 tool_result 的 `toolCallId` 都能在列表中按序找到对应 tool_use；反之亦然 | 通过 |
| T7 | 占位文本 | 含路径名且含"已被后续写入覆盖" | 失败 |
| T8 | ~~回归：形状 B 使用一致的 toolCallId 时仍能去重~~ → **已改为** `microCompactShouldNotTreatStandaloneToolUseAsWrite`：形状 B 不参与去重、也不被误删 | 列表不变 | 通过 |

T1 / T3 / T7 是"修复前必须失败"的用例——它们是把缺陷 A 钉死的钉子。（原表把 T2 也列为钉子，实测不成立，见 §11.1 偏差 1。）

### 7.4 运行命令

```bash
mvn -pl aether-domain test -Dtest=ContextManagerTest
mvn -pl aether-domain test -Dtest='ReActAgent*Test'
```

注意（既有坑，非本批引入）：
- `aether-app` 模块 surefire 只收 `*Test.java`，`*IT` 会被静默跳过；本批只碰 `aether-domain`。
- 若用 `-Dtest=` 精确跑单个类，需配 `-Dsurefire.failIfNoSpecifiedTests=false`，否则无匹配时报错。
- `SubagentLifecycleRetentionTest` 是既存 flake，与本批无关。

---

## 8. 验收标准

- [x] §1.2 的 5 条全部满足；
- [x] T1/T3/T7 在修复前失败、修复后通过（修复前实测失败输出：`Tests run: 44, Failures: 3`；修复后 44/44 通过）；
- [x] `grep -rn '"tool_use"' --include=*.java` 在 main 源集仍只命中 `TurnMessage.java:40`，且 microCompact 不再依赖该判据；
- [x] `EDIT_TOOL_NAMES` 不含 `BashTool`；`extractPathFromCommand` 已删除且无残留引用（`grep -rn extractPathFromCommand` 为空）；
- [x] §6.3 的两处同类问题已在文档中记录为后续批次，未在本批改动。

**回归证据**：`mvn -o -pl aether-domain test` → `Tests run: 626, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`。

---

## 9. 风险与回滚

| 风险 | 评估 | 缓解 |
|---|---|---|
| 删除了模型后续推理仍需要的写内容 | 中 | Q3 占位消息使省略对模型可见；同路径最后一次写始终保留；只删写不删读保住因果线索 |
| 配对破坏导致模型 API 报错 | 低 | §4.3 三重保守条件 + §4.4 按 id 精确配对 + id 不可解析则整条不删；T6 全量守卫 |
| 每轮删除导致长会话早期信息过度流失 | 中 | §6.4；上线后观察 token 曲线与任务成功率，必要时把触发改为阈值式 |
| `assistantWithToolCalls` 的 `input` 结构变化 | 低 | `pathFromInput` 同时接受 Map 与 String；抠不出路径即不删（条件 3） |

**回滚**：改动局限在 `ContextManager` 单文件且无接口变化，`git revert` 单个提交即可；`ReActAgent` 的调用点无需回退。

---

## 10. 待确认

1. §4.1 是否采纳对 `isDanglingToolUse`（`:184-186`）的改写？两者行为等价，改写只为消除判据分叉。
2. §6.3 的两处同类问题（`CompactionPipeline:174`、`ChunkSummarizer:125`）是否并入本批一起修？按手术式修改原则，本 spec 默认**不并入**。

**已决（2026-10-07 实施）**：① 采纳；② 不并入。

---

## 11. 实现记录

**改动文件**：`ContextManager.java`（主）、`ContextManagerTest.java`（测试）。`ReActAgent.java` / `TurnMessage.java` 未动，调用点签名与语义不变。

### 11.1 与本文档的三处偏差（均已实测验证）

**偏差 1 — T2 实际不是「修复前必须失败」的用例。**
§7.3 把 T2 标为修复前失败。实测不是：T2 断言的是「含 Read 的批次整条不被移除」，而修复前该方法整体空转、什么都不删，该断言**平凡成立**。同理 T4/T5/T6/T8 也是修复前通过。
真正的钉子只有三个：**T1、T3、T7**（修复前 `Tests run: 44, Failures: 3`）。T2/T4/T5/T6/T8 是**修复后的守卫**——它们的价值在于防止修复引入过度删除与配对破坏，而不在于暴露修复前的缺陷。

**偏差 2 — §7.2「原断言保留不动」不可能字面成立，已按同义改写。**
原 `microCompactShouldPreservePairingIntegrity` 的断言是**基于角色**的：每条 `isToolUse()` 后必跟 `isToolResult()`、每条 `isToolResult()` 前必有 `isToolUse()`。但生产形状下承载工具调用的是 `role=assistant` + `toolCalls` 的消息，`isToolUse()` 恒 false——换成 shape A 后这两段断言会**退化为永不触发**，测试变成空壳。
已改用**基于 toolCallId 的配对不变式**（`assertPairingInvariant`：列表中声明的全部 id 与 tool_result 携带的全部 id 必须一一对应）。这与 §7.3 T6 描述的是同一件事，且对两种消息形状都有效。断言意图未变，只是换成了在生产形状下真正成立的表述。

**偏差 3 — `WriteCall` 只保留 `path` 一个分量。**
§4.2 定义 `WriteCall(String toolName, String path)`。实施时 `toolName` 无任何消费者（判定、配对、占位文本都只用 path），按「不为推测性用途留字段」删除。记录于此以免后续比对文档时误判为漏实现。

### 11.2 实施中发现的额外约束（文档未预见）

`lastWriteIndex.get(w.path()) == i` **不能写成 stream lambda**：`i` 是基本 for 循环变量，不是 effectively final，lambda 捕获会编译失败。已改为普通 for 循环，并把「路径为 null」与「是该路径最后一次写」两个条件合并进同一个循环。

### 11.3 行为变化提醒（上线观察项）

修复生效后，`ReActAgent:264` **每轮**都会真实执行写去重（此前是空转）。§6.4 所述的行为变化因此从"理论"变为"实际"：长会话中早期写操作会被持续替换为占位。建议观察 token 曲线与任务成功率，必要时再讨论是否改为阈值触发——本批未改触发策略。

---

## 12. 评审驱动的修订（2026-10-07 第二轮）

对 §1–§11 的实现做了一次两轴代码评审（Standards：`CLAUDE.md` 成文标准 + Fowler 坏味道基线；Spec：本文档）。本节记录由此产生的修订，**取代前文冲突段落**。

### 12.1 移除形状 B（独立 `tool_use` 消息）的兼容层

**评审发现（Standards 轴唯一硬违规）**：§4.2 为 `role="tool_use"` 形状保留了完整分支，与 §2 自证的「生产从不产生该形状」直接冲突，违反 `CLAUDE.md` §2 简单优先「不要添加超出需求的功能」。

**新增实证（原文档没有的决定性证据）**：`BaseAgent.java:156-162` 的会话恢复路径把 `role` 从持久化 JSON **原样**读回。因此"恢复态可能出现该形状"这一猜想是否成立，等价于"是否有任何写入方产出该形状"。全仓库（所有模块的 main 源集）`"tool_use"` 字面量只出现在 `TurnMessage.java:40`——即判据自身的比较操作数。**产出方为零。**

**结论**：删除该分支。这是零风险改动——最坏情况退化为今天的行为（不去重），不会产生新故障。若将来真出现产出方，届时再加分支并配测试。

**连带塌缩**：形状 B 一走，`callCountOf` 与 `callIdsOf` 的双形状分派失去存在理由。`callCountOf` 直接内联为 `msg.toolCalls().size()`；`callIdsOf` 收窄为单循环；`extractWriteCalls` 以 `if (!msg.hasToolCalls()) return out;` 短路开头。`ContextManager.java` 净增从 +224 行降至 +142 行。

**测试同步**：T8 由「形状 B 仍能去重」改为 `microCompactShouldNotTreatStandaloneToolUseAsWrite`（形状 B 不参与去重、也不被误删——列表不变）；T6 去掉形状 B 内容，保留写/读混合与多批次配对守卫。

### 12.2 删除 `isDanglingToolUse`（Middle Man）

原 `isDanglingToolUse` 经 §4.1 改写后成为单行纯转发，无附加语义。已删除，`alignToolPairBoundaries` 与 `autoCompactIfNeeded` 两处调用点直接调 `carriesToolCalls`。说明文档随之合并进 `carriesToolCalls` 的 Javadoc，未丢失。

> 注意：`carriesToolCalls` 仍保留 `isToolUse()` 分支——边界对齐与摘要切分面对的是**任意**消息列表，那里的形状兼容是既存行为，不属本批范围。

### 12.3 采纳 `WriteCall` 单分量（原 §11.1 偏差 3 追溯批准）

评审指出 §11.1 偏差 3 是实施者自行决定、不在 §3 用户拍板的三项之内。经复核：`toolName` 确实无消费者，恢复它只会制造一个新的死字段。**本批正式采纳 `record WriteCall(String path)`**，§4.2 已同步改写。这是追溯批准，不是事后合理化——理由与时序都记录在此。

### 12.4 `placeholderOf` 可读性

原实现为四段 stream 管道 + 内联全限定 `java.util.stream.Collectors`。改为 `LinkedHashSet` 去重 + `String.join`，语义不变，去掉了对 `Collectors` 的隐式依赖。

### 12.5 T7 断言改为按内容定位

原 T7 断言 `compacted.get(1).role()`，锁的是**列表下标**而非「那条占位消息」——任何前导消息变化都会造成假失败。已改为按内容前缀 `[系统提示]` 查找后再断言角色与文本。

### 12.6 明确不修（评审提及但未采纳）

- **测试夹具重复**（8 个新测试各重复数行 `messages.add(...)`）：评审方亦标注为不强制。测试夹具的显式罗列可读性优于提取，保留。
- **`CompactionPipeline:174` / `ChunkSummarizer:125`**：仍按 §6.3 不并入。

### 12.7 修订后的验收

```
mvn -o -pl aether-domain test
Tests run: 626, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
