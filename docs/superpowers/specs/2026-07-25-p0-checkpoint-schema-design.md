# Aether P0 架构升级 — 检查点恢复 + YAML Schema 校验

**日期**: 2026-07-25 | **状态**: 已确认 | **范围**: domain 层 + JSON Schema 文件

---

## P0-#8：检查点/恢复机制

### 融合来源

| 框架 | 借鉴点 | 在 Aether 中的落地 |
|------|--------|-------------------|
| **CrewAI** | 多粒度检查点（每步骤）+ `from_checkpoint`/`resume`/`fork` | ReActAgent 每 5 轮自动 `saveCheckpoint()` + `resumeFromCheckpoint()` |
| **cc-haha** | WAL 日志模式（jsonl append-only） | 复用已有 `AgentEventPublisher` 的 JSON 事件日志作为 WAL |
| **MetaGPT** | 增量持久化（只写变更） | `CheckpointData` 只存 turnNumber + state 摘要 + 消息数 |

### 数据流

```
ReActAgent.queryLoop() 每5轮
  → saveCheckpoint(state) → FileCheckpointCollector
    → 写入 .claude/checkpoints/{sessionId}/ckpt-{turnNumber}.json
    → AgentEventPublisher.publishCheckpoint() → 日志 WAL

ChatService.resumeFromCheckpoint(sessionId) 恢复
  → FileCheckpointCollector.loadLatest(sessionId)
    → 反序列化 CheckpointData → AgentState
    → state.loadState() → ReActAgent 继续执行
```

### 文件清单

| 文件 | 操作 | 职责 |
|------|------|------|
| `CheckpointData.java` | 新增 | 检查点数据模型（sessionId, turnNumber, agentState JSON, timestamp） |
| `CheckpointCollector.java` | 新增 | 检查点收集器接口（save, loadLatest, listCheckpoints） |
| `FileCheckpointCollector.java` | 新增 | 文件系统实现（.claude/checkpoints/ 目录） |
| `RuntimeEvent.java` | 修改 | 新增 EventType.checkpoint + checkpointData 字段 |
| `AgentEventPublisher.java` | 修改 | 新增 publishCheckpoint() |
| `ReActAgent.java` | 修改 | queryLoop 每 5 轮调用 saveCheckpoint；新增 loadState() |
| `AgentConfig.java` | 修改 | 新增 checkpointEnabled 字段 |
| `ChatService.java` | 修改 | 新增 resumeFromCheckpoint(sessionId) 方法 |

---

## P0-#12：YAML Schema 校验

### 融合来源

| 框架 | 借鉴点 | 在 Aether 中的落地 |
|------|--------|-------------------|
| **AgentScope Java** | Spring `@Validated` + Jakarta Bean Validation | `AiAgentConfigTableVO` 字段添加 `@NotBlank` / `@NotNull` / `@Pattern` |
| **MetaGPT** | 就地校验（`__init__` 中校验，策略与类紧耦合） | `AgentGraphCompiler.validateConfigSchema()` — 编译前校验 |
| **AutoGen** | 类型即文档 | JSON Schema 文件手写，与 VO 字段同步 |

### 校验层级

```
Layer 1: 编辑期 — docs/schema/agent-config.schema.json
          IDE (VSCode/IDEA YAML 插件) 自动加载，实时提示字段类型 + 必填 + 描述

Layer 2: 启动期 — @Validated Jakarta Bean Validation
          字段:@NotBlank baseUrl, @NotBlank apiKey, @NotBlank model, @NotBlank agentName
          错误:ConstraintViolationException → 启动失败 + 精确到字段的错误消息

Layer 3: 编译期 — AgentGraphCompiler.validateConfigSchema()
          校验:agent 引用存在性、workflow subAgents 存在性、outputKey 引用存在性(C4已有)
          错误:AgentCompileException → 启动失败 + 精确到配置路径的错误消息
```

### 文件清单

| 文件 | 操作 | 职责 |
|------|------|------|
| `AiAgentConfigTableVO.java` | 修改 | 字段添加 Jakarta Bean Validation 注解 |
| `AgentGraphCompiler.java` | 修改 | 新增 validateConfigSchema()；在 compile() 开头调用 |
| `AgentCompileException.java` | 不修改 | 已被 C4 修复使用 |
| `docs/schema/agent-config.schema.json` | 新增 | 完整的 YAML 配置 JSON Schema（~120 行） |
| `pom.xml` (aether-domain) | 修改 | 确认 jakarta.validation-api 依赖存在 |

### JSON Schema 覆盖范围

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Aether Agent Configuration",
  "properties": {
    "ai.agent.config.tables": {
      "patternProperties": {
        "^[A-Za-z0-9_]+$": {
          "properties": {
            "module.ai-api.base-url": {"type": "string", "format": "uri"},
            "module.ai-api.api-key": {"type": "string"},
            "module.chat-model.model": {"type": "string"},
            "module.agents[].name": {"type": "string", "pattern": "^[A-Za-z0-9_]+$"},
            "module.agents[].instruction": {"type": "string", "minLength": 1},
            "module.agents[].toolNames": {"type": "array", "items": {"type": "string"}},
            "module.agent-workflows[].type": {"enum": ["sequential", "parallel", "loop", "graphflow"]}
          },
          "required": ["module.ai-api.base-url", "module.ai-api.api-key", "module.chat-model.model"]
        }
      }
    }
  }
}
```

---

## 不变范围

- 不修改 ReActAgent 主循环逻辑（仅新增 saveCheckpoint 调用点）
- 不修改 GraphExecutor（检查点是 Agent 级别，不是 Graph 级别）
- 不修改前端
- 不修改 MCP/Skills 工具集成
- Jakarta Bean Validation 依赖已在 Spring Boot 3.4.3 中内置（`spring-boot-starter-validation`）
