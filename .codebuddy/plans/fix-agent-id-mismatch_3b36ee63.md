---
name: fix-agent-id-mismatch
overview: 修复 YAML 中 agent-id 被 SnakeYAML 错误解析为八进制数字导致的智能体会话数据无法显示的问题。将 agents.yml 中的 agent-id 从八进制形式改为普通数字字符串，并检查数据库中的历史数据一致性。
todos:
  - id: fix-agent-ids
    content: 修改 agents.yml 中两处 agent-id：将第 8 行 "000001" 改为 "1"，第 70 行 "000002" 改为 "2"
    status: completed
---

## 问题描述

数据库中存在对话数据，但前端每个智能体的会话列表均显示为空。控制台日志显示 `list_sessions` API 返回 `count: 0, data: Array(0)`。

## 根因

`agents.yml` 中 `agent-id: "000001"` 和 `agent-id: "000002"` 被 SnakeYAML（遵循 YAML 1.1 规范）的隐式类型解析器识别为八进制整数（`000001` 八进制 = `1` 十进制），导致后端内部实际使用的 agentId 为 `"1"` / `"2"`，而前端通过 `queryAiAgentConfigList` API 获取到的 agentId 与数据库存储值不一致，造成 `WHERE agent_id = ?` 查询条件无法匹配到任何会话。

后端日志已证实：

- `查询会话列表 agentId=1 userId=admin`
- `查询会话列表 agentId=2 userId=admin`

## 修复目标

将 `agents.yml` 中的 `agent-id` 值改为不含前导零的形式 `"1"` / `"2"`，使 YAML 解析结果与数据库存储值、前端请求参数三者完全一致。

## 技术方案

### 修改策略

**直接在 `agents.yml` 中将 `agent-id` 值从 `"000001"` / `"000002"` 改为 `"1"` / `"2"`**

### 选择理由

1. **无需数据库迁移**：后端内部一直使用 `"1"` / `"2"`，数据库中已有会话的 `agent_id` 字段即为此值
2. **无需代码改动**：所有 Java 类型（`AiAgentConfigTableVO.Agent.agentId` 为 `String`）无需修改
3. **最小改动范围**：仅修改 2 行 YAML 配置，影响面极小
4. **彻底消除歧义**：`"1"` 不会被任何 YAML 解析器误判为八进制数

### 影响范围

- **`aether-app/src/main/resources/agent/agents.yml`**：第 8 行和第 70 行，修改 `agent-id` 值
- 前端代码**无需修改**：`queryAiAgentConfigList` API 返回新值后前端自动适配

### 验证方式

1. 重启后端应用，检查日志中 `Ai Agent 智能体装配` 输出确认 `agentId` 为 `"1"` / `"2"`
2. 前端刷新页面，观察控制台 `list_sessions` API 返回正确的会话数据
3. 确认会话列表正常展示历史对话