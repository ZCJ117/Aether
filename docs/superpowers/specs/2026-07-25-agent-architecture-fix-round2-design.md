# Aether Agent 架构修复（第二轮）— H2/M2/M4 设计文档

**日期**: 2026-07-25 | **状态**: 已确认 | **范围**: domain 层

---

## 背景

基于第一轮修复（C1-C5）后，剩余 3 项中/高优先级问题需要修复。

## 修复概览

| 编号 | 问题 | 严重度 | 改动文件 | 预计行数 |
|------|------|--------|----------|----------|
| H2 | `{memory}` 占位符缺失时静默失败 | High | ChatService.java | ~10 |
| M2 | 每用户仅一个会话 | Medium | ChatService.java | ~20 |
| M4 | 同一 MCP 端点创建重复连接 | Medium | ChatModelNode.java | ~30 |

---

## H2: {memory} 占位符缺失时 WARN + 不注入

### 问题

`ChatService.injectMemory()` 使用 `instruction.replace("{memory}", memoryBlock)`。若 instruction 不含 `{memory}`，replace() 是 no-op，记忆被静默丢弃。

### 方案

在 `injectMemory()` 方法中，replace 前后比较 instruction 是否变化。未变化 → WARN 日志，提示用户 instruction 缺少 `{memory}` 占位符。不自动追加内容。

### 改动

```java
// ChatService.java injectMemory() 方法中
String enriched = agentInstruction.replace("{memory}", memoryBlock);
if (enriched.equals(agentInstruction)) {
    log.warn("Agent [{}] 的 instruction 缺少 {{memory}} 占位符，记忆内容未被注入。" +
             "请在 instruction 中添加 {{memory}} 以启用记忆功能。", agentName);
}
```

### 向后兼容

- 已有 YAML（无 `{memory}` 占位符）行为不变，仅多一条 WARN 日志
- 已有 YAML（有 `{memory}` 占位符）行为不变

---

## M2: 每用户单会话 → 复合策略

### 问题

`ConcurrentHashMap.computeIfAbsent(userId, ...)` 限制一个用户只有一个活跃 sessionId。多客户端/标签页场景下共享 session → 消息历史混乱。

### 方案

废弃 `userSessions` 缓存 Map。改为：
- 前端传了有效 `sessionId` → 复用（会话恢复）
- 前端传 null/空 → `UUID.randomUUID()` 新建

删除 `createSession()` 中 `computeIfAbsent` 逻辑，改为始终返回调用方传入的 sessionId 或新建的 UUID。

### 改动

```java
// ChatService.createSession()
// 修改前：
public String createSession(String userId, String agentId) {
    return userSessions.computeIfAbsent(userId,
            k -> UUID.randomUUID().toString().replace("-", ""));
}

// 修改后：
public String createSession(String userId, String agentId) {
    String sessionId = UUID.randomUUID().toString().replace("-", "");
    log.info("创建会话: userId={}, agentId={}, sessionId={}", userId, agentId, sessionId);
    return sessionId;
}
```

删除 `private final Map<String, String> userSessions = new ConcurrentHashMap<>();` 字段。

### 向后兼容

- 调用方行为不变（createSession 仍返回 sessionId 字符串）
- API 签名不变
- 会话恢复路径不变（通过 sessionId 从 SessionRepository 加载状态）

---

## M4: MCP 连接去重缓存

### 问题

`ChatModelNode.doApply()` 中每次遍历 `toolMcpList` 都 new `McpSyncClient`，同一 MCP 端点（如 `baidu-search`）被多次创建独立 SSE 连接。

### 方案

在 `ChatModelNode` 中添加一个 `Map<String, ToolCallback[]>` 缓存（key = dedupKey）。dedupKey = `mcpName + "@" + baseUri`。

```java
// 缓存已创建的 MCP 连接
private final Map<String, ToolCallback[]> mcpCallbackCache = new ConcurrentHashMap<>();

// 构建 dedup key
private String dedupKey(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
    if (toolMcp.getSse() != null) {
        return "sse:" + toolMcp.getSse().getName() + "@" + toolMcp.getSse().getBaseUri();
    }
    if (toolMcp.getStdio() != null) {
        return "stdio:" + toolMcp.getStdio().getName() + "@"
                + toolMcp.getStdio().getServerParameters().getCommand();
    }
    if (toolMcp.getLocal() != null) {
        return "local:" + toolMcp.getLocal().getName();
    }
    return "unknown:" + System.identityHashCode(toolMcp);
}
```

在 `doApply()` 构建 toolCallbackList 前先查缓存：
- 命中 → 复用缓存中的 ToolCallback[]
- 未命中 → 正常创建 → 存入缓存

### 改动

```java
// ChatModelNode.doApply() 中 toolCallbackList 构建循环：
if (null != toolMcpList && !toolMcpList.isEmpty()) {
    for (ToolMcp toolMcp : toolMcpList) {
        String key = dedupKey(toolMcp);
        ToolCallback[] callbacks = mcpCallbackCache.computeIfAbsent(key, k -> {
            TooMcpCreateService service = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
            ToolCallback[] built = service.buildToolCallback(toolMcp);
            log.info("创建 MCP 连接: key={}", key);
            return built;
        });
        toolCallbackList.addAll(List.of(callbacks));
    }
}
```

### 向后兼容

- 首次遇到新 MCP 节点 → 行为不变（创建连接）
- 再次遇到相同 MCP 节点 → 复用连接
- 缓存生命周期 = ChatModelNode Bean 生命周期（启动到关闭）
