---
name: Agent 架构核心要点
description: 策略树装配链、AgentRuntime 主循环四阶段、GraphExecutor 三种工作流模式的架构说明
type: reference
---

# Agent 架构核心

## 启动装配链（ApplicationReadyEvent 触发）

```
AiAgentAutoConfig → ArmoryService.acceptArmoryAgents()
  → RootNode (入口)
    → AiApiNode (构建 OpenAiApi，配置 baseUrl/apiKey)
      → ChatModelNode (构建 ChatModel + ToolCallback + 注册 ToolRegistry + 动态注册 Spring Bean)
        → AgentNode (收集 agent 名称列表)
          → AgentWorkflowNode (按 type 路由到工作流节点)
            → [LoopAgentNode | ParallelAgentNode | SequentialAgentNode]
              → CompilerNode (AgentGraphCompiler 编译为 AgentGraph IR，注册到 AgentRegistry)
```

策略树基于 `AbstractArmorySupport`（继承自 `AbstractMultiThreadStrategyRouter`），每个节点实现 `doApply()` 和 `get()` 两个方法。

`AbstractArmorySupport.registerBean()` 通过 `DefaultListableBeanFactory` 在运行时动态注册/更新 Spring Bean。

## 请求时路由（ChatService）

```
POST /api/v1/chat → ChatService.handleMessage()
  ├── graph.getEdges() 非空 → GraphExecutor (多Agent 工作流)
  └── graph.getEdges() 为空 → AgentRuntime (单Agent 主循环)
```

## AgentRuntime 主循环（四阶段）

```
while (turnCount < 100):
  Phase 1 — 上下文管理:
    ContextManager.applyToolResultBudget()  // >50000字符 → 500字符截断
    ContextManager.microCompact()           // 两遍扫描去重冗余编辑
    ContextManager.autoCompactIfNeeded()    // token超阈值 → LLM摘要压缩
  Phase 2 — 模型调用:
    ModelInvoker.callWithStream()  // 流式调用 + 指数退避重试(3次,1s/2s/4s)
  Phase 3 — 退出判断:
    无 tool_use → emit done + 退出
  Phase 4 — 工具执行:
    ToolExecutor.executeBatch()  // 按 isConcurrencySafe 分区，safe并发/safe串行
    连续3轮工具全失败 → 熔断中断
```

## GraphExecutor 三种工作流

- **SEQUENTIAL** → 串行推进 subAgents，`{outputKey}` 模板解析传递结果
- **PARALLEL** → CachedThreadPool + CountDownLatch，事件通过 synchronized(emitter) 实时转发，10 分钟超时
- **LOOP** → 循环迭代 subAgents，收敛检测（`previousOutput.equals(currentOutput)`）或达到 maxIterations 终止

## 上下文管理（三层压缩）

1. `applyToolResultBudget` — 超大工具结果截断（>50000 → 500 字符）
2. `microCompact` — 同一文件多次编辑去重，保留最后一次
3. `autoCompactIfNeeded` — token 超 `(contextWindow - 20000) * 0.9` 时 LLM 生成摘要

## 关键配置常量

| 配置 | 值 |
|---|---|
| MAX_TURNS | 100 |
| 模型调用重试 | 3 次，退避 1s/2s/4s，上限 15s |
| 并行工具超时 | 60s |
| 并行 Agent 超时 | 10 分钟 |
| 上下文压缩阈值 | `(contextWindow - 20000) * 0.9` |
