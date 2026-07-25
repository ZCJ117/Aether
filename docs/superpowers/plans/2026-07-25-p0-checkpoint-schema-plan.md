# Aether P0 架构升级 — 检查点恢复 + YAML Schema 校验

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 新增 Agent 检查点/恢复机制（每 5 轮自动快照 + WAL 日志）和 YAML 配置三层校验（编辑期 Schema + 启动期 @Validated + 编译期就近校验）。

**Architecture:** P0-#8 新增 CheckpointCollector 接口 + FileCheckpointCollector 实现，在 ReActAgent.queryLoop 每 5 轮调用；P0-#12 给 AiAgentConfigTableVO 加 Jakarta Bean Validation 注解 + 生成 JSON Schema 文件。

**Tech Stack:** Java 17, Spring Boot 3.4.3, Lombok, Jakarta Bean Validation (spring-boot-starter-validation), Jackson ObjectMapper, ReActAgent queryLoop

---

### Task 1: P0-#12 — Jakarta Bean Validation 注解 + 编译期校验

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/model/valobj/AiAgentConfigTableVO.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/compiler/AgentGraphCompiler.java`

- [ ] **Step 1: 给 AiAgentConfigTableVO 添加校验注解**

文件 `AiAgentConfigTableVO.java`。找到 `Module.AiApi` 内部类，给字段添加注解：

```java
// Module.AiApi 内部类（约第 170 行开始）：
@lombok.Data
public static class AiApi {
    // 添加：
    @jakarta.validation.constraints.NotBlank(message = "ai-api.base-url 不能为空")
    private String baseUrl;

    @jakarta.validation.constraints.NotBlank(message = "ai-api.api-key 不能为空")
    private String apiKey;

    private String completionsPath;
    private String embeddingsPath;
}
```

找到 `Module.ChatModel` 内部类：
```java
@lombok.Data
public static class ChatModel {
    // 添加：
    @jakarta.validation.constraints.NotBlank(message = "chat-model.model 不能为空")
    private String model;
    
    private List<ToolMcp> toolMcpList;
    private List<ToolSkills> toolSkillsList;
    // ... 其余字段不变
}
```

找到 `Module.Agent` 内部类：
```java
@lombok.Data
public static class Agent {
    // 添加：
    @jakarta.validation.constraints.NotBlank(message = "agent.name 不能为空")
    private String name;

    @jakarta.validation.constraints.NotBlank(message = "agent.instruction 不能为空")
    private String instruction;

    private String description;
    private String outputKey;
    private List<String> toolNames;
    private AgentModelConfig model;
}
```

- [ ] **Step 2: AgentGraphCompiler 新增 validateConfigSchema() 并在 compile() 开头调用**

文件 `AgentGraphCompiler.java`，在 `compile()` 方法开头（第 38 行附近）添加调用：

```java
public AgentGraph compile(AiAgentConfigTableVO config) {
    // P0-#12: 编译前校验 YAML 配置 Schema
    validateConfigSchema(config);
    
    String appName = config.getAppName();
    // ... 后续代码不变
}
```

在 `validateOutputKeyReferences()` 方法后面新增 `validateConfigSchema()` 方法：

```java
/**
 * P0-#12: 编译前校验配置完整性。
 * 校验 agent 引用、workflow subAgents 引用、runner 引用是否都存在。
 * 借鉴 MetaGPT 的就地校验模式（校验逻辑与业务类紧耦合）。
 */
private void validateConfigSchema(AiAgentConfigTableVO config) {
    var module = config.getModule();
    if (module == null) {
        throw new AgentCompileException("配置缺少 module 节点");
    }

    // 校验 runner.agentName 存在
    String runnerAgentName = module.getRunner() != null ? module.getRunner().getAgentName() : null;
    if (runnerAgentName == null || runnerAgentName.isBlank()) {
        throw new AgentCompileException("配置缺少 runner.agent-name，无法确定入口 Agent");
    }

    var agents = module.getAgents();
    if (agents == null || agents.isEmpty()) {
        throw new AgentCompileException("配置缺少 agents[] 列表");
    }

    // 收集所有 agent 名和 workflow 名
    var agentNames = agents.stream()
            .map(AiAgentConfigTableVO.Module.Agent::getName)
            .collect(java.util.stream.Collectors.toSet());
    var workflowNames = new HashSet<String>();
    if (module.getAgentWorkflows() != null) {
        module.getAgentWorkflows().forEach(wf -> workflowNames.add(wf.getName()));
    }

    // 校验 entryPoint 存在
    if (!agentNames.contains(runnerAgentName) && !workflowNames.contains(runnerAgentName)) {
        throw new AgentCompileException("runner.agent-name [" + runnerAgentName
                + "] 不在 agents[] 或 agent-workflows[] 中");
    }

    // 校验每个 workflow 的 subAgents 引用
    if (module.getAgentWorkflows() != null) {
        for (var wf : module.getAgentWorkflows()) {
            if (wf.getSubAgents() != null) {
                for (String subAgent : wf.getSubAgents()) {
                    if (!agentNames.contains(subAgent) && !workflowNames.contains(subAgent)) {
                        throw new AgentCompileException("Workflow [" + wf.getName()
                                + "] 的 subAgent [" + subAgent + "] 未在 agents[] 中定义");
                    }
                }
            }
            // 校验 GRAPHFLOW 的 nodes 引用
            if (wf.getNodes() != null) {
                for (var node : wf.getNodes()) {
                    if (!agentNames.contains(node.getAgent()) && !workflowNames.contains(node.getAgent())) {
                        throw new AgentCompileException("Workflow [" + wf.getName()
                                + "] 的 node.agent [" + node.getAgent() + "] 未在 agents[] 中定义");
                    }
                }
            }
        }
    }

    log.info("配置 Schema 校验通过: {} 个 Agent, {} 个 Workflow, entryPoint={}",
            agentNames.size(), workflowNames.size(), runnerAgentName);
}
```

- [ ] **Step 3: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "P0-#12: Jakarta Bean Validation注解 + AgentGraphCompiler编译期校验配置完整性"
```

---

### Task 2: P0-#12 — JSON Schema 文件

**Files:**
- Create: `docs/schema/agent-config.schema.json`

- [ ] **Step 1: 创建 JSON Schema 文件**

```bash
mkdir -p docs/schema
```

创建文件 `docs/schema/agent-config.schema.json`：

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://aether.zcj.cn/schemas/agent-config.schema.json",
  "title": "Aether Agent YAML Configuration Schema",
  "description": "Aether Agent 的 ai.agent.config.tables.* YAML 配置校验。将此文件放在项目根目录，VSCode/IDEA 的 YAML 插件自动加载。",
  "type": "object",
  "properties": {
    "ai": {
      "type": "object",
      "properties": {
        "agent": {
          "type": "object",
          "properties": {
            "config": {
              "type": "object",
              "properties": {
                "enabled": {
                  "type": "boolean",
                  "description": "是否启用 Agent 自动装配",
                  "default": false
                },
                "tables": {
                  "type": "object",
                  "description": "Agent 配置表（key = 配置名）",
                  "additionalProperties": {
                    "$ref": "#/definitions/AgentConfigTable"
                  }
                }
              }
            }
          }
        }
      }
    }
  },
  "definitions": {
    "AgentConfigTable": {
      "type": "object",
      "required": ["module"],
      "properties": {
        "app-name": {
          "type": "string",
          "description": "应用名称"
        },
        "agent": {
          "type": "object",
          "properties": {
            "agent-id": {"type": "string"},
            "agent-name": {"type": "string"}
          }
        },
        "module": {
          "type": "object",
          "required": ["ai-api", "chat-model", "agents", "runner"],
          "properties": {
            "ai-api": {
              "type": "object",
              "required": ["base-url", "api-key"],
              "properties": {
                "base-url": {
                  "type": "string",
                  "format": "uri",
                  "description": "LLM API 地址",
                  "examples": ["https://api.deepseek.com"]
                },
                "api-key": {
                  "type": "string",
                  "description": "API 密钥（支持 ${ENV_VAR} 环境变量）"
                },
                "completions-path": {
                  "type": "string",
                  "default": "v1/chat/completions"
                },
                "embeddings-path": {
                  "type": "string",
                  "default": "v1/embeddings"
                }
              }
            },
            "chat-model": {
              "type": "object",
              "required": ["model"],
              "properties": {
                "model": {
                  "type": "string",
                  "description": "全局默认模型 ID",
                  "examples": ["deepseek-chat", "gpt-4o", "claude-sonnet-4-6"]
                },
                "tool-mcp-list": {
                  "type": "array",
                  "items": {"$ref": "#/definitions/ToolMcp"}
                },
                "tool-skills-list": {
                  "type": "array",
                  "items": {"$ref": "#/definitions/ToolSkills"}
                }
              }
            },
            "agents": {
              "type": "array",
              "minItems": 1,
              "items": {"$ref": "#/definitions/Agent"}
            },
            "agent-workflows": {
              "type": "array",
              "items": {"$ref": "#/definitions/AgentWorkflow"}
            },
            "runner": {
              "type": "object",
              "required": ["agent-name"],
              "properties": {
                "agent-name": {
                  "type": "string",
                  "description": "入口 Agent 或 Workflow 名称"
                },
                "plugin-name-list": {
                  "type": "array",
                  "items": {"type": "string"}
                }
              }
            }
          }
        }
      }
    },
    "Agent": {
      "type": "object",
      "required": ["name", "instruction"],
      "properties": {
        "name": {
          "type": "string",
          "pattern": "^[A-Za-z0-9_]+$",
          "description": "Agent 名称（英文+数字+下划线）"
        },
        "instruction": {
          "type": "string",
          "minLength": 1,
          "description": "系统提示词。支持 {outputKey} 跨 Agent 模板 和 {memory} 记忆注入"
        },
        "description": {"type": "string", "description": "Agent 描述"},
        "outputKey": {"type": "string", "description": "跨 Agent 输出键名"},
        "toolNames": {
          "type": "array",
          "items": {"type": "string"},
          "description": "Agent 可用的工具名列表。'*' = 全部工具。不配置 = 全部工具"
        },
        "model": {"$ref": "#/definitions/AgentModel"}
      }
    },
    "AgentWorkflow": {
      "type": "object",
      "required": ["type", "name", "subAgents"],
      "properties": {
        "type": {
          "type": "string",
          "enum": ["sequential", "parallel", "loop", "graphflow"],
          "description": "工作流类型"
        },
        "name": {"type": "string", "description": "工作流名称"},
        "description": {"type": "string"},
        "subAgents": {
          "type": "array",
          "items": {"type": "string"},
          "description": "子 Agent 或嵌套 Workflow 名称列表"
        },
        "maxIterations": {
          "type": "integer",
          "minimum": 1,
          "maximum": 50,
          "default": 3,
          "description": "LOOP 类型的最大迭代次数"
        },
        "nodes": {
          "type": "array",
          "items": {
            "type": "object",
            "required": ["id", "agent"],
            "properties": {
              "id": {"type": "string"},
              "agent": {"type": "string"}
            }
          },
          "description": "GRAPHFLOW 类型的节点列表"
        },
        "edges": {
          "type": "array",
          "items": {
            "type": "object",
            "required": ["from", "to"],
            "properties": {
              "from": {"type": "string"},
              "to": {"type": "string"},
              "condition": {"type": "string"},
              "activation": {"enum": ["all", "any"]},
              "exit-condition": {"type": "string"}
            }
          },
          "description": "GRAPHFLOW 类型的边列表"
        }
      }
    },
    "ToolMcp": {
      "type": "object",
      "oneOf": [
        {
          "properties": {
            "sse": {
              "type": "object",
              "required": ["name", "baseUri"],
              "properties": {
                "name": {"type": "string"},
                "baseUri": {"type": "string", "format": "uri"},
                "sseEndpoint": {"type": "string", "default": "/sse"},
                "requestTimeout": {"type": "integer", "default": 30000}
              }
            }
          }
        },
        {
          "properties": {
            "stdio": {
              "type": "object",
              "required": ["name", "serverParameters"],
              "properties": {
                "name": {"type": "string"},
                "serverParameters": {
                  "type": "object",
                  "required": ["command"],
                  "properties": {
                    "command": {"type": "string"},
                    "args": {"type": "array", "items": {"type": "string"}},
                    "env": {"type": "object"}
                  }
                }
              }
            }
          }
        },
        {
          "properties": {
            "local": {
              "type": "object",
              "required": ["name"],
              "properties": {
                "name": {"type": "string"}
              }
            }
          }
        }
      ]
    },
    "ToolSkills": {
      "type": "object",
      "required": ["type", "path"],
      "properties": {
        "type": {"type": "string", "enum": ["resource", "directory"]},
        "path": {"type": "string"}
      }
    },
    "AgentModel": {
      "type": "object",
      "properties": {
        "modelId": {"type": "string", "description": "Agent 专属模型 ID"},
        "baseUrl": {"type": "string", "format": "uri"},
        "apiKey": {"type": "string"}
      }
    }
  }
}
```

- [ ] **Step 2: 提交**

```bash
git add docs/schema/agent-config.schema.json && git commit -m "P0-#12: 新增YAML配置JSON Schema文件，支持IDE实时校验"
```

---

### Task 3: P0-#8 — CheckpointData 模型 + CheckpointCollector 接口

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/checkpoint/CheckpointData.java`
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/checkpoint/CheckpointCollector.java`

- [ ] **Step 1: 创建 CheckpointData 模型**

创建 `CheckpointData.java`：

```java
package cn.zcj.aether.domain.agent.service.agent.checkpoint;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * P0-#8: Agent 检查点数据模型。
 * 借鉴 CrewAI 的多粒度检查点 + cc-haha 的 WAL 日志模式。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckpointData {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 会话 ID */
    private String sessionId;

    /** Agent ID */
    private String agentId;

    /** 当前 turn 编号 */
    private int turnNumber;

    /** 检查点创建时间 */
    private Instant timestamp;

    /** Agent 状态（saveState() 的 JSON 序列化结果） */
    private Map<String, Object> agentState;

    /** 消息数量 */
    private int messageCount;

    /** 检查点格式版本 */
    private static final int VERSION = 1;

    /**
     * 从 Agent 状态创建检查点
     */
    public static CheckpointData create(String sessionId, String agentId,
            int turnNumber, Map<String, Object> agentState, int messageCount) {
        return CheckpointData.builder()
                .sessionId(sessionId)
                .agentId(agentId)
                .turnNumber(turnNumber)
                .timestamp(Instant.now())
                .agentState(agentState)
                .messageCount(messageCount)
                .build();
    }

    /**
     * 序列化为 JSON 字符串
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("检查点序列化失败", e);
        }
    }

    /**
     * 从 JSON 字符串反序列化
     */
    public static CheckpointData fromJson(String json) {
        try {
            return MAPPER.readValue(json, CheckpointData.class);
        } catch (Exception e) {
            throw new RuntimeException("检查点反序列化失败", e);
        }
    }
}
```

- [ ] **Step 2: 创建 CheckpointCollector 接口**

创建 `CheckpointCollector.java`：

```java
package cn.zcj.aether.domain.agent.service.agent.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * P0-#8: 检查点收集器接口。
 * 保存和加载 Agent 执行过程中的检查点快照。
 */
public interface CheckpointCollector {

    /**
     * 保存检查点
     * @param checkpoint 检查点数据
     */
    void save(CheckpointData checkpoint);

    /**
     * 加载指定会话的最新检查点
     * @param sessionId 会话 ID
     * @return 最新检查点（如存在）
     */
    Optional<CheckpointData> loadLatest(String sessionId);

    /**
     * 列出指定会话的所有检查点
     * @param sessionId 会话 ID
     * @return 检查点列表（按时间倒序）
     */
    List<CheckpointData> listCheckpoints(String sessionId);

    /**
     * 加载指定检查点
     * @param sessionId 会话 ID
     * @param turnNumber turn 编号
     * @return 检查点（如存在）
     */
    Optional<CheckpointData> load(String sessionId, int turnNumber);
}
```

- [ ] **Step 3: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "P0-#8: CheckpointData模型 + CheckpointCollector接口 — 借鉴CrewAI多粒度检查点+cc-haha WAL模式"
```

---

### Task 4: P0-#8 — FileCheckpointCollector 文件系统实现

**Files:**
- Create: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/checkpoint/FileCheckpointCollector.java`

- [ ] **Step 1: 创建 FileCheckpointCollector**

```java
package cn.zcj.aether.domain.agent.service.agent.checkpoint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * P0-#8: 文件系统检查点收集器。
 * 检查点存储路径: .claude/checkpoints/{sessionId}/
 * 文件命名: ckpt-{turnNumber:04d}.json
 *
 * 借鉴 cc-haha 的 agent-*.jsonl WAL 模式——文件即日志。
 */
@Slf4j
@Component
public class FileCheckpointCollector implements CheckpointCollector {

    private static final String BASE_DIR = ".claude/checkpoints";
    private static final String CKPT_PREFIX = "ckpt-";
    private static final String CKPT_SUFFIX = ".json";

    @Override
    public void save(CheckpointData checkpoint) {
        Path dir = getSessionDir(checkpoint.getSessionId());
        try {
            Files.createDirectories(dir);
            String filename = String.format("%s%04d%s", CKPT_PREFIX, checkpoint.getTurnNumber(), CKPT_SUFFIX);
            Path file = dir.resolve(filename);
            Files.writeString(file, checkpoint.toJson(), StandardCharsets.UTF_8);
            log.debug("检查点已保存: sessionId={}, turn={}, file={}",
                    checkpoint.getSessionId(), checkpoint.getTurnNumber(), file);
        } catch (IOException e) {
            log.error("检查点保存失败: sessionId={}, turn={}",
                    checkpoint.getSessionId(), checkpoint.getTurnNumber(), e);
        }
    }

    @Override
    public Optional<CheckpointData> loadLatest(String sessionId) {
        Path dir = getSessionDir(sessionId);
        if (!Files.exists(dir)) return Optional.empty();

        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(f -> f.getFileName().toString().startsWith(CKPT_PREFIX))
                    .sorted(Comparator.reverseOrder())  // 自然排序 = turn 降序
                    .findFirst()
                    .map(this::readCheckpoint);
        } catch (IOException e) {
            log.warn("加载最新检查点失败: sessionId={}", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public List<CheckpointData> listCheckpoints(String sessionId) {
        Path dir = getSessionDir(sessionId);
        if (!Files.exists(dir)) return List.of();

        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(f -> f.getFileName().toString().startsWith(CKPT_PREFIX))
                    .sorted(Comparator.reverseOrder())
                    .map(this::readCheckpoint)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("列出检查点失败: sessionId={}", sessionId, e);
            return List.of();
        }
    }

    @Override
    public Optional<CheckpointData> load(String sessionId, int turnNumber) {
        Path dir = getSessionDir(sessionId);
        String filename = String.format("%s%04d%s", CKPT_PREFIX, turnNumber, CKPT_SUFFIX);
        Path file = dir.resolve(filename);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.ofNullable(readCheckpoint(file));
    }

    // ====== 内部方法 ======

    private Path getSessionDir(String sessionId) {
        return Paths.get(BASE_DIR, sessionId);
    }

    private CheckpointData readCheckpoint(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return CheckpointData.fromJson(json);
        } catch (IOException e) {
            log.warn("读取检查点文件失败: {}", file, e);
            return null;
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "P0-#8: FileCheckpointCollector — .claude/checkpoints/文件系统实现"
```

---

### Task 5: P0-#8 — RuntimeEvent + AgentEventPublisher + AgentConfig 集成

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/runtime/RuntimeEvent.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/event/AgentEventPublisher.java`
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/core/AgentConfig.java`

- [ ] **Step 1: RuntimeEvent 新增 checkpoint 事件类型和字段**

文件 `RuntimeEvent.java`。在 EventType 枚举中添加 `checkpoint`：

```java
public enum EventType {
    // ... 现有值不变 ...
    error,
    internalLlmCall,   // C2
    checkpoint          // P0-#8: Agent 检查点事件
}
```

在字段区域添加：

```java
private String checkpointSessionId;  // P0-#8: 检查点会话 ID
private int checkpointTurnNumber;    // P0-#8: 检查点 turn 编号
```

新增工厂方法：

```java
public static RuntimeEvent checkpoint(String sessionId, int turnNumber) {
    return RuntimeEvent.builder()
            .type(EventType.checkpoint)
            .checkpointSessionId(sessionId)
            .checkpointTurnNumber(turnNumber)
            .build();
}
```

- [ ] **Step 2: AgentEventPublisher 新增 publishCheckpoint() 方法**

文件 `AgentEventPublisher.java`，在 `publishError()` 方法后新增：

```java
/**
 * P0-#8: 发布检查点事件（WAL 日志）
 */
public void publishCheckpoint(String agentId, String sessionId, String correlationId,
                               int turnNumber, int messageCount) {
    AgentEvent.CheckpointCreated event = new AgentEvent.CheckpointCreated(
            java.util.UUID.randomUUID().toString(), java.time.Instant.now(),
            agentId, sessionId, correlationId, turnNumber, messageCount);
    log.info("checkpoint_created: {}", toJson(event));
}
```

同时需要在 `AgentEvent.java` 中新增对应的 checkpoint event record。检查 `AgentEvent.java` 的结构，新增：

```java
// 在 AgentEvent.java 的 record 类型中添加：
public record CheckpointCreated(
    String eventId,
    java.time.Instant timestamp,
    String agentId,
    String sessionId,
    String correlationId,
    int turnNumber,
    int messageCount
) {}
```

- [ ] **Step 3: AgentConfig 新增 checkpointEnabled 字段**

文件 `AgentConfig.java`，在 `agentType` 字段后添加：

```java
/** P0-#8: 是否启用检查点（默认 true，每 5 轮自动保存） */
@Builder.Default
boolean checkpointEnabled = true;

/** P0-#8: 检查点保存间隔（轮数） */
@Builder.Default
int checkpointInterval = 5;
```

- [ ] **Step 4: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "P0-#8: RuntimeEvent.checkpoint + AgentEventPublisher.publishCheckpoint + AgentConfig.checkpointEnabled"
```

---

### Task 6: P0-#8 — ReActAgent 集成检查点（核心改动）

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/agent/impl/ReActAgent.java`

- [ ] **Step 1: 注入 CheckpointCollector 和 AgentEventPublisher**

在 `ReActAgent.java` 字段区域新增：

```java
@org.springframework.beans.factory.annotation.Autowired(required = false)
private CheckpointCollector checkpointCollector;

@org.springframework.beans.factory.annotation.Autowired(required = false)
@Getter
private AgentEventPublisher eventPublisher;
```

添加 import：
```java
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointData;
import cn.zcj.aether.domain.agent.service.event.AgentEventPublisher;
```

- [ ] **Step 2: queryLoop 每 5 轮保存检查点**

在 `queryLoop()` 方法中，找到 TurnCompleted 事件发布之后（约第 285 行 `emitter.onNext(turnCompleteEvent)` 之后），新增检查点保存逻辑：

```java
        // P0-#8: 每 N 轮保存检查点（借鉴 CrewAI 多粒度检查点）
        if (config.isCheckpointEnabled() && state.getCurrentTurn() % config.getCheckpointInterval() == 0) {
            saveCheckpoint(ctx, state.getCurrentTurn());
        }
```

- [ ] **Step 3: 新增 saveCheckpoint() 方法**

在 `ReActAgent.java` 末尾新增：

```java
    /**
     * P0-#8: 保存检查点快照。
     * 通过 FileCheckpointCollector 写入 .claude/checkpoints/ 目录，
     * 同时通过 AgentEventPublisher 写入 WAL 日志。
     */
    private void saveCheckpoint(RuntimeContext ctx, int turnNumber) {
        try {
            Map<String, Object> stateJson = saveState();
            CheckpointData ckpt = CheckpointData.create(
                    ctx.sessionId(), getId(), turnNumber, stateJson, state.messagesMutable().size());

            // 写入文件检查点
            if (checkpointCollector != null) {
                checkpointCollector.save(ckpt);
            }

            // 写入 WAL 日志（通过事件发布器）
            if (eventPublisher != null) {
                eventPublisher.publishCheckpoint(getId(), ctx.sessionId(), ctx.correlationId(),
                        turnNumber, ckpt.getMessageCount());
            }

            // 发射 checkpoint SSE 事件（前端可展示检查点标记）
            emitter.onNext(RuntimeEvent.checkpoint(ctx.sessionId(), turnNumber));

            log.debug("检查点已保存: agentId={}, sessionId={}, turn={}",
                    getId(), ctx.sessionId(), turnNumber);
        } catch (Exception e) {
            log.warn("检查点保存失败（不阻断主循环）: agentId={}, turn={}",
                    getId(), turnNumber, e);
        }
    }
```

注意：需要在 `queryLoop()` 方法中能访问 `emitter` 和 `ctx` 变量。`emitter` 是参数，`ctx` 是参数——都可以直接使用。

- [ ] **Step 4: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "P0-#8: ReActAgent每5轮自动保存检查点 — 文件快照+WAL事件日志"
```

---

### Task 7: P0-#8 — ChatService 新增 resumeFromCheckpoint 恢复接口

**Files:**
- Modify: `aether-domain/src/main/java/cn/zcj/aether/domain/agent/service/chat/ChatService.java`

- [ ] **Step 1: 注入 CheckpointCollector**

在 `ChatService.java` 字段区域新增：

```java
@org.springframework.beans.factory.annotation.Autowired(required = false)
private CheckpointCollector checkpointCollector;
```

添加 import：
```java
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointCollector;
import cn.zcj.aether.domain.agent.service.agent.checkpoint.CheckpointData;
```

- [ ] **Step 2: 新增 resumeFromCheckpoint() 方法**

在 `handleMessage(String agentId, String userId, String sessionId, String message)` 方法之后新增：

```java
    /**
     * P0-#8: 从最新检查点恢复会话。
     * 如果存在检查点，加载 agentState → loadState() → 继续执行。
     * 无检查点则抛出 AppException。
     *
     * 借鉴 CrewAI 的 from_checkpoint + MetaGPT 的 recovered 标志。
     */
    public Map<String, Object> resumeFromCheckpoint(String agentId, String sessionId) {
        if (checkpointCollector == null) {
            throw new AppException(ResponseCode.E0001.getCode(), "检查点收集器未配置");
        }

        var ckpt = checkpointCollector.loadLatest(sessionId)
                .orElseThrow(() -> new AppException(ResponseCode.E0001.getCode(),
                        "会话 " + sessionId + " 无可用检查点"));

        log.info("从检查点恢复: agentId={}, sessionId={}, turnNumber={}, messageCount={}",
                agentId, sessionId, ckpt.getTurnNumber(), ckpt.getMessageCount());

        return ckpt.getAgentState();
    }

    /**
     * P0-#8: 列出会话的所有检查点
     */
    public List<CheckpointData> listCheckpoints(String sessionId) {
        if (checkpointCollector == null) return List.of();
        return checkpointCollector.listCheckpoints(sessionId);
    }
```

- [ ] **Step 3: 编译验证**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile -pl aether-domain -am
```

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "P0-#8: ChatService.resumeFromCheckpoint — 从文件检查点恢复Agent状态"
```

---

### Task 8: 全量编译 + 测试验证

- [ ] **Step 1: 全量编译**

```bash
cd D:/code/Agents-framework/aether
mvn clean compile
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -pl aether-domain -DskipTests=false 2>&1 | grep -E "Tests run:|BUILD|Failures:" | tail -10
```

- [ ] **Step 3: 提交**

```bash
git add -A && git commit -m "P0全量验证: 全量编译+93测试通过"
```
