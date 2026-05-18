---
name: Agent YAML 配置速查
description: YAML 声明式配置的必填项、agents/workflows 写法和完整配置模板
type: reference
---

# Agent YAML 配置指南

配置文件位于 `aether-app/src/main/resources/agent/*.yml`，通过 `application-*.yml` 的 `spring.config.import` 激活。

## 多 Agent 配置方式

在 YAML 的 `ai.agent.config.tables` 下定义多个条目，每个条目对应一个独立的 Agent：

```yaml
ai:
  agent:
    config:
      tables:
        myAgent01:    # Agent 1
          app-name: ...
          agent: ...
          module: ...
        myAgent02:    # Agent 2
          app-name: ...
          agent: ...
          module: ...
```

## 必填项

- `ai-api`: base-url, api-key
- `chat-model`: model
- `agents[]`: name, instruction
- `runner.agent-name`: 指定运行的入口 Agent

## agents 配置

```yaml
agents:
  - name: researcher          # Agent 名称（英文）
    description: 研究助手      # 用途描述
    instruction: |            # 系统提示词
      你是信息检索专家...
    output-key: research_result  # 输出键，供下游 Agent 通过 {research_result} 引用
```

## agent-workflows 配置

支持三种工作流类型：`sequential`、`parallel`、`loop`。

```yaml
agent-workflows:
  - type: parallel
    name: ParallelResearch
    sub-agents: [AgentA, AgentB, AgentC]   # 并行执行的 Agent 列表

  - type: loop
    name: RefinementLoop
    max-iterations: 5                       # 最大循环次数，默认 3
    sub-agents: [CriticAgent, RefinerAgent]

  - type: sequential
    name: MainPipeline
    sub-agents: [ParallelResearch, MergerAgent]  # 可引用 workflow 名称和 agent 名称
```

- `sequential` 和 `loop` 的 sub-agents 中既可引用 `agents` 下的单一智能体名，也可引用 `agent-workflows` 下的工作流名
- `parallel` 的 sub-agents 仅引用 `agents` 下的单一智能体名
- 下游 Agent 的 instruction 中使用 `{output-key}` 引用上游输出

## 可选配置

- `tool-mcp-list[]`: MCP 工具（SSE/Stdio/Local 三种传输方式）
- `tool-skills-list[]`: Skills 技能书（`agent/skills/` 目录）

## 记忆目录配置

可通过 `ai.agent.config.memory-dir` 配置自定义记忆目录路径（可选，默认自动探测项目根目录下的 `memory/`）：

```yaml
ai:
  agent:
    config:
      memory-dir: /absolute/path/to/memory   # 可选
```

## runner 入口

```yaml
runner:
  agent-name: MainPipeline   # 可以是 agents 下的单一智能体名或 agent-workflows 下的工作流名
```
