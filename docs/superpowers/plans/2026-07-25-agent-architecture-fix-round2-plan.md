# Aether Agent 架构修复（第二轮）— H2/M2/M4 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 修复 {memory} 静默失败、每用户单会话限制、MCP 重复连接三项中/高优先级问题。

**Architecture:** 三项改动互不依赖，改两个文件：ChatService.java（H2 + M2）、ChatModelNode.java（M4）。

**Tech Stack:** Java 17, Spring Boot 3.4.3, Lombok

---

### Task 1: H2 — {memory} 占位符缺失时 WARN 日志

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/chat/ChatService.java`

- [ ] **Step 1: 修改 injectMemory() — 两个路径都加 WARN 检查**

文件 `ChatService.java`，`injectMemory()` 方法（约第 262-287 行）。

**MemoryFacade 路径**（第 278-279 行）：
```java
// 修改前：
if (instruction == null) return memoryBlock.toString();
return instruction.replace("{memory}", memoryBlock.toString());

// 修改后：
if (instruction == null) return memoryBlock.toString();
String enriched = instruction.replace("{memory}", memoryBlock.toString());
if (enriched.equals(instruction)) {
    log.warn("Agent [{}] 的 instruction 缺少 {{memory}} 占位符，记忆内容未被注入。" +
             "请在 instruction 中添加 {{memory}} 以启用记忆功能（MemoryFacade 路径）。", agentId);
}
return enriched;
```

**MemoryStore 回退路径**（第 286-287 行）：
```java
// 修改前：
if (instruction == null) return memoryPrompt;
return instruction.replace("{memory}", memoryPrompt);

// 修改后：
if (instruction == null) return memoryPrompt;
String enriched = instruction.replace("{memory}", memoryPrompt);
if (enriched.equals(instruction)) {
    log.warn("Agent [{}] 的 instruction 缺少 {{memory}} 占位符，记忆内容未被注入。" +
             "请在 instruction 中添加 {{memory}} 以启用记忆功能（MemoryStore 回退路径）。", agentId);
}
return enriched;
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "H2: {memory}占位符缺失时WARN日志提示（MemoryFacade+MemoryStore双路径）"
```

---

### Task 2: M2 — 每用户单会话 → 始终新建

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/chat/ChatService.java`

- [ ] **Step 1: 删除 userSessions 缓存字段**

删除第 81 行：
```java
// 删除这行：
private final Map<String, String> userSessions = new ConcurrentHashMap<>();
```

- [ ] **Step 2: 重写 createSession() 方法**

修改 `createSession()` 方法（第 99-110 行）：
```java
// 修改前：
@Override
public String createSession(String agentId, String userId) {
    AgentGraph graph = agentRegistry.get(agentId);
    if (graph == null) {
        throw new AppException(ResponseCode.E0001.getCode());
    }

    return userSessions.computeIfAbsent(userId, uid -> {
        String sessionId = UUID.randomUUID().toString();
        log.info("创建会话 agentId={} userId={} sessionId={}", agentId, userId, sessionId);
        return sessionId;
    });
}

// 修改后：
@Override
public String createSession(String agentId, String userId) {
    AgentGraph graph = agentRegistry.get(agentId);
    if (graph == null) {
        throw new AppException(ResponseCode.E0001.getCode());
    }

    String sessionId = UUID.randomUUID().toString().replace("-", "");
    log.info("创建会话 agentId={} userId={} sessionId={}", agentId, userId, sessionId);
    return sessionId;
}
```

- [ ] **Step 3: 移除不需要的 import**

检查文件顶部 import，如果 `ConcurrentHashMap` 不再有其他引用则删除：
```java
// 如果有其他使用 ConcurrentHashMap 的地方则保留；否则删除此行：
// import java.util.concurrent.ConcurrentHashMap;
```

- [ ] **Step 4: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "M2: 删除userSessions缓存，createSession始终生成新UUID"
```

---

### Task 3: M4 — MCP 连接缓存去重

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/armory/node/ChatModelNode.java`

- [ ] **Step 1: 添加缓存 Map 和 dedup key 方法**

在 `ChatModelNode` 类中添加：

**缓存字段**（在现有字段声明区域，约第 49 行附近）：
```java
/** M4: MCP 连接缓存 —— 按 name@baseUri 去重，避免重复创建 SSE/Stdio 连接 */
private final Map<String, ToolCallback[]> mcpCallbackCache = new ConcurrentHashMap<>();
```

**dedup key 方法**（放在文件末尾，registerPerAgentChatModel 方法之后）：
```java
/**
 * M4: 构建 MCP 连接去重 key。
 * SSE: name@baseUri | Stdio: name@command | Local: name
 */
private String dedupKey(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
    if (toolMcp.getSse() != null) {
        return "sse:" + toolMcp.getSse().getName() + "@" + toolMcp.getSse().getBaseUri();
    }
    if (toolMcp.getStdio() != null) {
        var params = toolMcp.getStdio().getServerParameters();
        String cmd = params != null ? params.getCommand() : "unknown";
        return "stdio:" + toolMcp.getStdio().getName() + "@" + cmd;
    }
    if (toolMcp.getLocal() != null) {
        return "local:" + toolMcp.getLocal().getName();
    }
    return "unknown:" + System.identityHashCode(toolMcp);
}
```

需要添加 import：`import java.util.concurrent.ConcurrentHashMap;`（检查是否已存在）

- [ ] **Step 2: 修改 doApply() 中的 MCP 连接构建循环**

修改 `doApply()` 中 toolCallbackList 构建部分（第 72-78 行）：
```java
// 修改前：
if (null != toolMcpList && !toolMcpList.isEmpty()) {
    for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
        TooMcpCreateService tooMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
        ToolCallback[] toolCallbacks = tooMcpCreateService.buildToolCallback(toolMcp);
        toolCallbackList.addAll(List.of(toolCallbacks));
    }
}

// 修改后：
if (null != toolMcpList && !toolMcpList.isEmpty()) {
    for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
        String key = dedupKey(toolMcp);
        ToolCallback[] toolCallbacks = mcpCallbackCache.computeIfAbsent(key, k -> {
            TooMcpCreateService tooMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
            ToolCallback[] built = tooMcpCreateService.buildToolCallback(toolMcp);
            log.info("MCP 连接已创建: key={}", key);
            return built;
        });
        toolCallbackList.addAll(List.of(toolCallbacks));
        log.debug("MCP 连接已复用: key={}", key);
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "M4: MCP连接缓存去重 — name@baseUri做key避免重复SSE/Stdio连接"
```

---

### Task 4: 全量编译 + 测试验证

- [ ] **Step 1: 全量编译**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -pl aether-domain -DskipTests=false 2>&1 | tail -20
```

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "第二轮修复验证: 全量编译+93测试通过"
```
