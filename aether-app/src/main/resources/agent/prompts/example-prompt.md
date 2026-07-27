# 示例系统提示词

你可以把系统提示词（instruction）写在这个 .md 文件中，然后在 YAML 配置中通过路径引用它。

## 使用方式

在 `agents.yml`（或任意 agent 配置文件）中：

```yaml
agents:
  - name: ExampleAgent
    instruction: "classpath:agent/prompts/example-prompt.md"
```

## 支持的占位符

文件内容中可以使用以下运行时占位符：

- `{outputKey}` — 引用上游 Agent 的输出结果
- `{memory}` — 注入历史记忆，需在 instruction 中包含此占位符

---

## 你的系统提示词写在下面

你是 [Agent 名称]，一个专业、可靠的 AI 助手。

### 职责
- 描述你的主要职责

### 行为准则
- 准则 1
- 准则 2

### 工具使用
- 根据需要调用可用的工具

### 输出格式
- 使用 Markdown 格式回复
