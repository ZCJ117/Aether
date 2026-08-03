<script setup lang="ts">
import { ref } from 'vue'
import LandingNavbar from '@/components/landing/LandingNavbar.vue'

interface DocSection {
  label: string
  children?: { label: string }[]
}

const sections: DocSection[] = [
  {
    label: '快速开始',
    children: [
      { label: '环境要求' },
      { label: '克隆与构建' },
      { label: '最小配置模板' },
      { label: '验证 Agent 运行' },
    ],
  },
  {
    label: '核心概念',
    children: [
      { label: 'Agent 生命周期' },
      { label: 'ReActAgent 引擎' },
      { label: 'YAML 配置参考' },
      { label: 'AgentGraph 工作流' },
      { label: '工具系统' },
      { label: '上下文管理' },
      { label: '记忆系统' },
      { label: 'Human-in-the-Loop' },
      { label: '设计参考来源' },
    ],
  },
  {
    label: '开发指南',
    children: [
      { label: '模块结构' },
      { label: '自定义 Agent' },
      { label: 'MCP 工具接入' },
      { label: 'Skills 工具' },
      { label: '前端开发' },
      { label: 'Docker 部署' },
      { label: '故障排查' },
    ],
  },
  {
    label: 'API 参考',
    children: [
      { label: 'Chat API' },
      { label: 'SSE 流式协议' },
      { label: 'Agent 管理 API' },
      { label: '模型管理 API' },
    ],
  },
]

const activeSection = ref('环境要求')
const expandedGroups = ref<string[]>(['快速开始'])
</script>

<template>
  <div class="min-h-screen bg-[#0c0c0c] text-white">
    <LandingNavbar />
    <div class="flex pt-16">
      <!-- Sidebar -->
      <aside class="hidden md:block w-56 lg:w-64 flex-shrink-0 border-r border-white/10 h-[calc(100vh-4rem)] overflow-y-auto sticky top-16">
        <nav class="px-5 py-6">
          <h2 class="text-sm font-bold text-white mb-6">📚 Aether 文档</h2>
          <ul class="space-y-5">
            <li v-for="group in sections" :key="group.label">
              <button
                class="w-full text-left text-sm font-semibold mb-2 flex items-center gap-1.5 transition-colors"
                :class="expandedGroups.includes(group.label) ? 'text-white' : 'text-white/60 hover:text-white'"
                @click="expandedGroups.includes(group.label)
                  ? expandedGroups = expandedGroups.filter(g => g !== group.label)
                  : expandedGroups.push(group.label)"
              >
                <span class="text-[10px] transition-transform" :class="{ 'rotate-90': expandedGroups.includes(group.label) }">▶</span>
                {{ group.label }}
              </button>
              <ul
                v-if="expandedGroups.includes(group.label) && group.children"
                class="ml-4 space-y-1"
              >
                <li v-for="child in group.children" :key="child.label">
                  <button
                    class="w-full text-left text-xs py-1.5 px-2 rounded transition-colors"
                    :class="activeSection === child.label
                      ? 'text-[#5AC8FA] bg-[#5AC8FA]/10'
                      : 'text-white/50 hover:text-white/80'"
                    @click="activeSection = child.label"
                  >
                    {{ child.label }}
                  </button>
                </li>
              </ul>
            </li>
          </ul>
        </nav>
      </aside>

      <!-- Content -->
      <main class="flex-1 px-6 md:px-12 py-10 max-w-3xl">
        <div
          v-motion
          :initial="{ opacity: 0, y: 24 }"
          :enter="{ opacity: 1, y: 0, transition: { duration: 600, ease: [0.22, 1, 0.36, 1] } }"
        >
          <!-- ========== 快速开始 ========== -->
          <template v-if="activeSection === '环境要求'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">环境要求</h1>
            <p class="text-white/50 text-sm mb-10">运行 Aether 所需的最低环境配置</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">后端</h2>
              <ul class="list-disc list-inside text-white/60 text-sm space-y-1">
                <li>JDK 17+</li>
                <li>Maven 3.8+</li>
                <li>Spring Boot 3.4.3</li>
              </ul>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">前端（可选）</h2>
              <ul class="list-disc list-inside text-white/60 text-sm space-y-1">
                <li>Node.js 18+</li>
                <li>npm / pnpm</li>
              </ul>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">LLM API</h2>
              <ul class="list-disc list-inside text-white/60 text-sm space-y-1">
                <li>OpenAI 兼容 API（支持多 Provider 路由）</li>
                <li>推荐模型：GPT-4o / Claude 4.x / 通义千问 / DeepSeek</li>
              </ul>
            </section>
          </template>

          <template v-if="activeSection === '克隆与构建'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">克隆与构建</h1>
            <p class="text-white/50 text-sm mb-10">从源码启动 Aether 的完整步骤</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">1. 克隆仓库</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>git clone https://github.com/your-org/aether.git
cd aether</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">2. 编译项目</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>mvn clean install -DskipTests</code></pre>
              <p class="text-white/50 text-xs mt-2">项目包含 6 个 Maven 模块（aether-app / api / domain / infrastructure / trigger / types），Maven 会自动处理模块间依赖。</p>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">3. 启动应用</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>cd aether-app
mvn spring-boot:run</code></pre>
              <p class="text-white/50 text-xs mt-2">应用默认监听 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">8080</code> 端口。启动日志中出现 "Aether Agent Runtime started" 即表示成功。</p>
            </section>
          </template>

          <template v-if="activeSection === '最小配置模板'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">最小配置模板</h1>
            <p class="text-white/50 text-sm mb-10">在 aether-app/src/main/resources/agent/ 下创建 YAML 配置文件</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">单 Agent 最小配置</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>ai-api:
  base-url: https://api.openai.com
  api-key: ${OPENAI_API_KEY}
  completions-path: v1/chat/completions

chat-model:
  model: gpt-4o

agents:
  - name: assistant
    instruction: 你是一个有用的 AI 助手。

runner:
  agent-name: assistant</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">配置说明</h2>
              <table class="w-full text-sm border-collapse">
                <thead>
                  <tr class="border-b border-white/10">
                    <th class="text-left py-2 text-white/60 font-medium">字段</th>
                    <th class="text-left py-2 text-white/60 font-medium">说明</th>
                  </tr>
                </thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2"><code>ai-api.base-url</code></td><td class="py-2">LLM API 基础地址（OpenAI 兼容）</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2"><code>ai-api.api-key</code></td><td class="py-2">API 密钥（支持 <code>${ENV_VAR}</code> 环境变量引用）</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2"><code>chat-model.model</code></td><td class="py-2">模型名称</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2"><code>agents[].name</code></td><td class="py-2">Agent 唯一标识</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2"><code>agents[].instruction</code></td><td class="py-2">系统指令 / System Prompt</td></tr>
                  <tr><td class="py-2"><code>runner.agent-name</code></td><td class="py-2">启动入口 Agent 名称</td></tr>
                </tbody>
              </table>
            </section>
          </template>

          <template v-if="activeSection === '验证 Agent 运行'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">验证 Agent 运行</h1>
            <p class="text-white/50 text-sm mb-10">通过 API 调用验证 Agent 正常工作</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">直接 API 调用</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "agentId": "assistant",
    "userId": "user-001",
    "message": "你好，请介绍一下你自己"
  }'</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">SSE 流式调用</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>curl -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "agentId": "assistant",
    "userId": "user-001",
    "message": "写一段快速排序的 Java 代码"
  }'</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">预期响应</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>{
  "code": 200,
  "message": "success",
  "data": {
    "sessionId": "uuid-xxxx",
    "responses": ["你好！我是 Aether AI 助手..."]
  }
}</code></pre>
            </section>
          </template>

          <!-- ========== 核心概念 ========== -->
          <template v-if="activeSection === 'Agent 生命周期'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">Agent 生命周期</h1>
            <p class="text-white/50 text-sm mb-10">Aether Agent 从配置到运行的完整流程</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">装配阶段（启动时）</h2>
              <p class="text-white/50 text-sm mb-4">应用启动后，<code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">ApplicationReadyEvent</code> 触发装配流水线：</p>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>AiAgentAutoConfig
  → ArmoryService.acceptArmoryAgents()
    → RootNode → AiApiNode（构建 OpenAiApi）
      → ChatModelNode（构建 ChatModel + ToolCallback + Agent 级工具过滤）
        → AgentNode（收集 Agent 名列表）
          → AgentWorkflowNode（按 type 路由到 Loop/Parallel/Sequential）
            → CompilerNode（编译 AgentGraph + 注册到 AgentRegistry）</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">执行阶段（请求时）</h2>
              <p class="text-white/50 text-sm mb-4">收到 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">POST /api/v1/chat</code> 请求后：</p>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>ChatService.handleMessage(agentId, userId, sessionId, message)
  ├── graph.getEdges() 非空
  │     → GraphExecutor.execute(fullGraph)
  │         ├── SEQUENTIAL → 串行执行 subAgents，{outputKey} 模板解析
  │         ├── PARALLEL   → 并发执行 subAgents
  │         └── LOOP       → 循环迭代，收敛检测
  └── graph.getEdges() 为空
        → DefaultAgentFactory.create(agentConfig)
          → ReActAgent.execute(ctx)</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">销毁阶段</h2>
              <p class="text-white/50 text-sm">Agent 执行完毕后自动释放资源。会话状态通过 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">SessionRepository</code> 持久化，支持跨请求恢复。</p>
            </section>
          </template>

          <template v-if="activeSection === 'ReActAgent 引擎'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">ReActAgent 引擎</h1>
            <p class="text-white/50 text-sm mb-10">自研 Agent 主循环引擎的核心设计</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">主循环架构</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>while (turnCount &lt; MAX_TURNS) {  // MAX_TURNS = 100
  state.incrementTurn()

  // Phase 1: 上下文管理
  contextManager.applyToolResultBudget(messages)   // >50000 字符 → 截断
  contextManager.microCompact(messages)            // 去重冗余编辑
  contextManager.autoCompactIfNeeded(messages)     // 超阈值 → LLM 摘要

  // Phase 2: 模型调用（重试 3 次，指数退避 1s/2s/4s）
  modelInvoker.callWithStreamCachedAsync(chatModel, messages, ...)

  // Phase 3: 无 tool_use → 退出
  if (modelResult.getToolCalls().isEmpty()) {
    emit done + complete
  }

  // Phase 4: 工具执行
  toolExecutor.executeBatch(requests, userId, sessionId)
    // safe 组 → 并发（CompletableFuture, 60s 超时）
    // unsafe 组 → 串行

  // 熔断：连续 3 轮全部工具失败 → 强制退出
}
</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">关键配置</h2>
              <table class="w-full text-sm border-collapse">
                <thead><tr class="border-b border-white/10"><th class="text-left py-2 text-white/60">参数</th><th class="text-left py-2 text-white/60">值</th></tr></thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2">最大轮次 (MAX_TURNS)</td><td class="py-2">100</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">重试次数</td><td class="py-2">3 次，指数退避 1s → 2s → 4s，上限 15s</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">可重试错误</td><td class="py-2">Connection reset / Broken pipe / Timeout / 503 / 502 / 429</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">不可重试错误</td><td class="py-2">401 / 403 / 404</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">工具并发线程池</td><td class="py-2">core=4, max=16, queue=200, CallerRunsPolicy</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">并行工具超时</td><td class="py-2">60s</td></tr>
                  <tr><td class="py-2">上下文压缩阈值</td><td class="py-2">(contextWindow - 20000) × 0.9</td></tr>
                </tbody>
              </table>
            </section>
          </template>

          <template v-if="activeSection === 'YAML 配置参考'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">YAML 配置参考</h1>
            <p class="text-white/50 text-sm mb-10">完整的 Agent YAML 配置结构说明</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">多 Agent 顺序工作流示例</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>ai-api:
  base-url: https://api.openai.com
  api-key: ${OPENAI_API_KEY}

chat-model:
  model: gpt-4o
  tool-mcp-list:              # MCP 工具配置
    - sse:
        name: web-search
        base-uri: https://search.mcp.server/
        request-timeout: 5000

agents:
  - name: CodeWriter
    instruction: 你是一个 Java 代码生成器。根据需求编写代码。
    output-key: generated_code      # 输出键，供下游引用

  - name: CodeReviewer
    instruction: |
      审查以下代码：
      ```java
      {generated_code}              # 引用上游输出
      ```
    output-key: review_comments

agent-workflows:
  - type: sequential                # 工作流类型
    name: CodePipeline
    sub-agents:
      - CodeWriter
      - CodeReviewer

runner:
  agent-name: CodePipeline          # 启动入口</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">工作流类型</h2>
              <table class="w-full text-sm border-collapse">
                <thead><tr class="border-b border-white/10"><th class="text-left py-2 text-white/60">类型</th><th class="text-left py-2 text-white/60">说明</th></tr></thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2"><code>sequential</code></td><td class="py-2">串行执行 subAgents，下游通过 <code>{outputKey}</code> 引用上游输出</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2"><code>parallel</code></td><td class="py-2">并发执行 subAgents，CountDownLatch + 事件同步转发</td></tr>
                  <tr><td class="py-2"><code>loop</code></td><td class="py-2">循环迭代 subAgents，收敛检测自动退出</td></tr>
                </tbody>
              </table>
            </section>
          </template>

          <template v-if="activeSection === 'AgentGraph 工作流'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">AgentGraph 工作流</h1>
            <p class="text-white/50 text-sm mb-10">多 Agent 编排的 IR 模型与执行引擎</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">IR 模型</h2>
              <p class="text-white/50 text-sm mb-3"><code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">AgentGraph</code> 是 YAML 配置编译后的中间表示，包含：</p>
              <ul class="list-disc list-inside text-white/50 text-sm space-y-1">
                <li><code>AgentNodeDef</code> — Agent 节点定义（name / instruction / outputKey / toolNames / modelRef）</li>
                <li><code>AgentEdge</code> — 边的连接关系（source / target / edgeType）</li>
                <li><code>AgentEdgeType</code> — 边类型（SEQUENTIAL / PARALLEL / LOOP / CONDITIONAL）</li>
              </ul>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">GraphExecutor</h2>
              <p class="text-white/50 text-sm">负责按 IR 模型调度执行。请求进入 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">ChatService.handleMessage()</code> 时，若 graph.edges 非空则路由到 GraphExecutor，否则创建单个 ReActAgent 执行。</p>
            </section>
          </template>

          <template v-if="activeSection === '工具系统'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">工具系统</h1>
            <p class="text-white/50 text-sm mb-10">MCP / Skills 工具接入与执行机制</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">工具接入方式</h2>
              <table class="w-full text-sm border-collapse">
                <thead><tr class="border-b border-white/10"><th class="text-left py-2 text-white/60">方式</th><th class="text-left py-2 text-white/60">传输</th><th class="text-left py-2 text-white/60">说明</th></tr></thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2">MCP SSE</td><td class="py-2">HTTP SSE</td><td class="py-2">远程 MCP 服务，Server-Sent Events</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">MCP Stdio</td><td class="py-2">标准输入输出</td><td class="py-2">本地进程通信</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">MCP Local</td><td class="py-2">本地调用</td><td class="py-2">进程内直接调用</td></tr>
                  <tr><td class="py-2">Skills</td><td class="py-2">文件 / 资源</td><td class="py-2">基于 spring-ai-agent-utils 的 SkillsTool</td></tr>
                </tbody>
              </table>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">Agent 级工具作用域</h2>
              <p class="text-white/50 text-sm">每个 Agent 可配置 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">toolNames</code> 字段精确控制可见的工具列表。<code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">"*"</code> 或未配置表示暴露全部工具。ChatModelNode 为每个 Agent 创建独立的 ChatModel Bean，按 toolNames 过滤 ToolCallback。</p>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">执行策略</h2>
              <p class="text-white/50 text-sm">工具按 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">isConcurrencySafe()</code> 分为 safe / unsafe 两组：</p>
              <ul class="list-disc list-inside text-white/50 text-sm space-y-1">
                <li>safe 组 → 并发执行（CompletableFuture，60s 超时）</li>
                <li>unsafe 组 → 串行执行</li>
                <li>异常降级 → 检测并发安全性失败时降级为串行</li>
              </ul>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">两阶段校验</h2>
              <ol class="list-decimal list-inside text-white/50 text-sm space-y-1">
                <li>JSON Schema 校验 → 失败返回 VALIDATION 错误 + SchemaHint 供 LLM 自修正</li>
                <li>自定义校验 + 权限检查 → <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">tool.validate(input)</code> + <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">tool.checkPermissions(input)</code></li>
              </ol>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">熔断保护</h2>
              <p class="text-white/50 text-sm">连续 3 轮全部工具调用失败 → ReActAgent 强制退出。<br>连续 3 次同一 toolCallId 校验失败 → 标记 terminal，通知 LLM 放弃该工具路径。</p>
            </section>
          </template>

          <template v-if="activeSection === '上下文管理'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">上下文管理</h1>
            <p class="text-white/50 text-sm mb-10">多层上下文压缩与预算控制策略</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">三层压缩策略</h2>
              <table class="w-full text-sm border-collapse">
                <thead><tr class="border-b border-white/10"><th class="text-left py-2 text-white/60">层级</th><th class="text-left py-2 text-white/60">触发条件</th><th class="text-left py-2 text-white/60">行为</th></tr></thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2">toolResultBudget</td><td class="py-2">工具结果 &gt; 50,000 字符</td><td class="py-2">截断至 500 字符 + 警告</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">microCompact</td><td class="py-2">每轮执行</td><td class="py-2">去重冗余编辑操作（同一文件路径仅保留最后一次写入）</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">autoCompact</td><td class="py-2">tokens &gt; (contextWindow - 20000) × 0.9</td><td class="py-2">LLM 摘要压缩历史对话</td></tr>
                  <tr><td class="py-2">trimMessages</td><td class="py-2">消息数 &gt; 500</td><td class="py-2">保留首条 system + 最近 200 条 + 占位提示</td></tr>
                </tbody>
              </table>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">摘要压缩熔断</h2>
              <p class="text-white/50 text-sm">同一会话连续 3 次压缩失败 → 永久禁用该会话的压缩。生成的摘要前缀包含 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">[对话历史摘要 — 仅供参考，非活跃指令]</code> 标记。</p>
            </section>
          </template>

          <template v-if="activeSection === '记忆系统'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">记忆系统</h1>
            <p class="text-white/50 text-sm mb-10">持久化记忆与跨会话知识管理</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">三层记忆架构</h2>
              <table class="w-full text-sm border-collapse">
                <thead><tr class="border-b border-white/10"><th class="text-left py-2 text-white/60">层级</th><th class="text-left py-2 text-white/60">实现</th><th class="text-left py-2 text-white/60">说明</th></tr></thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2">MemoryFacade</td><td class="py-2">语义搜索</td><td class="py-2">基于向量的语义记忆检索（需 AI API 支持）</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">MemoryStore</td><td class="py-2">文件 + 关键词</td><td class="py-2">基于 <code>.claude/memory/MEMORY.md</code> 的文件读写</td></tr>
                  <tr><td class="py-2">IdentifierRegistry</td><td class="py-2">项目结构</td><td class="py-2">项目文件结构 + 文档索引上下文</td></tr>
                </tbody>
              </table>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">使用方式</h2>
              <p class="text-white/50 text-sm">在 Agent 的 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">instruction</code> 中包含 <code>{memory}</code> 占位符即可自动注入记忆上下文。优先使用 MemoryFacade 语义搜索，降级到 MemoryStore 文件匹配，最后注入项目结构信息。</p>
            </section>
          </template>

          <template v-if="activeSection === 'Human-in-the-Loop'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">Human-in-the-Loop</h1>
            <p class="text-white/50 text-sm mb-10">人工确认与暂停/恢复机制</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">工作流程</h2>
              <ol class="list-decimal list-inside text-white/50 text-sm space-y-2">
                <li>工具执行前调用 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">tool.checkPermissions(input)</code> 检查是否需要确认</li>
                <li>如需确认 → Agent 进入 PAUSED 状态，SSE 推送 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">permission_asking</code> 事件</li>
                <li>用户通过前端确认/拒绝 → 调用 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">handleConfirm()</code> API</li>
                <li>系统恢复 Agent 状态，注入 ConfirmResult，继续执行</li>
              </ol>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">PAUSED 状态保护</h2>
              <p class="text-white/50 text-sm">Agent 处于 PAUSED 状态时，发送新消息会抛出 AppException，防止状态冲突。必须先确认或取消待处理操作。</p>
            </section>
          </template>

          <template v-if="activeSection === '设计参考来源'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">设计参考来源</h1>
            <p class="text-white/50 text-sm mb-10">Aether 参考了以下 6 个开源 Agent 框架的核心设计</p>

            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">1. AutoGen（Microsoft Research · Python）</h2>
              <p class="text-white/50 text-sm mb-2">参考内容：</p>
              <ul class="list-disc list-inside text-white/50 text-sm space-y-1">
                <li>Agent 协议 / 消息传递机制 → Aether 内部消息通信</li>
                <li>DiGraph + GraphFlowManager 图执行 → Aether GRAPHFLOW DAG 执行模式</li>
                <li>AssistantAgent 独立模型配置 → Aether 异构模型混用</li>
                <li>OTel Span 追踪层级 → Aether 3 级 AgentTracer</li>
                <li>MagenticOne 编排器（Plan → Delegate → Synthesize）→ Aether PlanActAgent</li>
                <li>_head_and_tail 工具配对保护 → Aether ContextManager.alignToolPairBoundaries()</li>
              </ul>
            </section>

            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">2. AgentScope Java（Alibaba · Java）</h2>
              <p class="text-white/50 text-sm mb-2">参考内容：</p>
              <ul class="list-disc list-inside text-white/50 text-sm space-y-1">
                <li>AgentState 双模状态（快照+增量）→ Aether 状态序列化设计</li>
                <li>Hook 系统 7 个拦截点 → 直接架构移植为 Aether AgentHook</li>
                <li>MiddlewareBase 五层洋葱模型 → Aether 同结构中间件系统</li>
                <li>AgentEvent 多态事件（Jackson 类型化）→ Aether 10 类型事件系统</li>
                <li>Per-Agent Toolkit 深拷贝隔离 → Aether Agent 级 toolNames 工具作用域</li>
                <li>PermissionEngine 5 条优先级规则链 → Aether 权限引擎</li>
              </ul>
            </section>

            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">3. CrewAI（CrewAI Inc. · Python）</h2>
              <p class="text-white/50 text-sm mb-2">参考内容：</p>
              <ul class="list-disc list-inside text-white/50 text-sm space-y-1">
                <li>BaseAgent 可序列化实体 → Aether Agent.state() / loadState()</li>
                <li>BaseLLM 类层次结构 → Aether ModelProvider SPI</li>
                <li>EncodingFlow / RecallFlow 记忆管线 → Aether MemoryFacade + llmRerank</li>
                <li>CheckpointConfig 多粒度检查点 → Aether 检查点/恢复机制</li>
                <li>EventBus 事件总线 → Aether AgentEventPublisher</li>
                <li>build_schema_hint 工具校验反馈 → Aether SchemaHintBuilder</li>
              </ul>
            </section>

            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">4. MetaGPT（DeepWisdom · Python）</h2>
              <p class="text-white/50 text-sm mb-2">参考内容：</p>
              <ul class="list-disc list-inside text-white/50 text-sm space-y-1">
                <li>RoleContext.llm 角色级模型配置 → Aether Per-Agent 模型</li>
                <li>Working / Long-Term Memory 分层 → Aether 多层记忆系统</li>
                <li>ProjectRepo 持久化 → Aether 会话状态仓储</li>
                <li>ActionNode 编译时 {key} 校验 → Aether AgentGraphCompiler {outputKey} 校验</li>
                <li>PLAN_AND_ACT 执行模式 → Aether PlanActAgent 三阶段</li>
                <li>消息级去重 → Aether Caffeine LRU 响应缓存</li>
              </ul>
            </section>

            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">5. cc-haha（Claude Code Fork · TypeScript）</h2>
              <p class="text-white/50 text-sm mb-2">参考内容：</p>
              <ul class="list-disc list-inside text-white/50 text-sm space-y-1">
                <li>cost-tracker Token 核算 → Aether TokenBudget / CostTracker</li>
                <li>SessionMemory 后台 Fork Agent 异步提取 → Aether SessionMemoryExtractor</li>
                <li>显式工具 allow/deny 列表 → Aether YAML toolNames 工具白名单</li>
                <li>WAL JSONL 日志模式 → Aether 检查点 WAL 事件日志</li>
                <li>PermissionMode 4 级权限层级 → Aether 权限模式（DEFAULT→PLAN→ACCEPT_EDITS→BYPASS）</li>
                <li>autoCompact 熔断（MAX_CONSECUTIVE_FAILURES=3）→ Aether P2 压缩保护</li>
                <li>isConcurrencySafe 安全/不安全分组 → Aether 工具执行器并发策略</li>
              </ul>
            </section>

            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">6. Hermes Agent（Nous Research · Python）</h2>
              <p class="text-white/50 text-sm mb-2">参考内容：</p>
              <ul class="list-disc list-inside text-white/50 text-sm space-y-1">
                <li>FailoverReason 21 类型错误分类 → Aether P1 14 类型 FailoverReason 枚举</li>
                <li>去相关抖动退避算法（base=2s, max=30s, jitterRatio=0.5）→ Aether P1 ResilientChatModelExecutor</li>
                <li>try_activate_fallback 故障转移模型链 + 60s 冷却 → Aether ModelRoute 路由切换</li>
                <li>ClassifiedError 内联 action hints（retryable / shouldCompress / shouldFallback）→ Aether P1 分级错误设计</li>
                <li>禁用 SDK 内置重试、集中化重试所有权 → Aether P1 统一重试入口</li>
                <li>摘要前缀防污染标注（SUMMARY_PREFIX）→ Aether P2 摘要隔离标注</li>
              </ul>
            </section>
          </template>

          <!-- ========== 开发指南 ========== -->
          <template v-if="activeSection === '模块结构'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">模块结构</h1>
            <p class="text-white/50 text-sm mb-10">Aether 6 模块 DDD 分层架构详解</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">模块依赖（禁止反向）</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>aether-trigger  ──→  aether-api  ──→  aether-domain
                                   ↑
                          aether-infrastructure
                                   ↑
                              aether-types</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">各模块职责</h2>
              <table class="w-full text-sm border-collapse">
                <thead><tr class="border-b border-white/10"><th class="text-left py-2 text-white/60">模块</th><th class="text-left py-2 text-white/60">职责</th></tr></thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2">aether-app</td><td class="py-2">启动引导、YAML 配置、HttpClientConfig</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">aether-api</td><td class="py-2">服务接口、DTO 定义</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">aether-domain</td><td class="py-2">核心：Agent 运行时、编译器、上下文、工具、执行器、记忆</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">aether-infrastructure</td><td class="py-2">会话存储、MCP 客户端适配器</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">aether-trigger</td><td class="py-2">REST 控制器</td></tr>
                  <tr><td class="py-2">aether-types</td><td class="py-2">枚举、异常、常量</td></tr>
                </tbody>
              </table>
            </section>
          </template>

          <template v-if="activeSection === '自定义 Agent'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">自定义 Agent</h1>
            <p class="text-white/50 text-sm mb-10">开发自定义 Agent 类型的完整指南</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">实现 BaseAgent</h2>
              <p class="text-white/50 text-sm mb-3">继承 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">cn.zcj.aether.domain.agent.service.agent.BaseAgent</code>，实现 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">execute(RuntimeContext)</code> 方法，返回 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">Flowable&lt;RuntimeEvent&gt;</code>。</p>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>@Slf4j
public class MyCustomAgent extends BaseAgent {

    public MyCustomAgent(AgentConfig config, ...) {
        super(config);
        // 注入依赖
    }

    @Override
    public Flowable&lt;RuntimeEvent&gt; execute(RuntimeContext ctx) {
        return Flowable.create(emitter -> {
            // 自定义逻辑
            emitter.onNext(RuntimeEvent.textDelta("Hello"));
            emitter.onNext(RuntimeEvent.done());
            emitter.onComplete();
        }, BackpressureStrategy.BUFFER);
    }
}</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">注册 Agent</h2>
              <p class="text-white/50 text-sm">在 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">AgentRegistry</code> 中注册新的 Agent 类型后，即可在 YAML 配置中通过 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">agentType</code> 字段引用。</p>
            </section>
          </template>

          <template v-if="activeSection === 'MCP 工具接入'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">MCP 工具接入</h1>
            <p class="text-white/50 text-sm mb-10">通过 MCP 协议接入外部工具服务</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">策略模式工厂</h2>
              <p class="text-white/50 text-sm mb-3"><code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">DefaultMcpClientFactory</code> 根据 YAML 配置中 ToolMcp 的传输字段自动分发：</p>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>public TooMcpCreateService getTooMcpCreateService(ToolMcp toolMcp) {
    if (null != toolMcp.getLocal())  return localToolMcpCreateService;
    if (null != toolMcp.getSse())    return sseToolMcpCreateService;
    if (null != toolMcp.getStdio())  return stdioToolMcpCreateService;
    throw new AppException(ResponseCode.NOT_FOUND_METHOD);
}</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">配置示例</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>chat-model:
  tool-mcp-list:
    - sse:                          # SSE 传输
        name: baidu-search
        base-uri: http://appbuilder.baidu.com/v2/ai_search/mcp/
        request-timeout: 5000
    - stdio:                        # Stdio 传输
        name: local-python
        command: python
        args: ["-m", "my_mcp_server"]</code></pre>
            </section>
          </template>

          <template v-if="activeSection === 'Skills 工具'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">Skills 工具</h1>
            <p class="text-white/50 text-sm mb-10">基于 spring-ai-agent-utils 的 SkillsTool 集成</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">配置方式</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>chat-model:
  tool-skills-list:
    - resource: classpath:/skills/code-review.md
    - file: /home/user/skills/deployment.md</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">适配器</h2>
              <p class="text-white/50 text-sm">Skills 工具通过 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">SkillsToolAdapter</code> 适配为统一的 Tool 接口，注册到 ToolRegistry 后由 ChatModelNode 按 agent 级 toolNames 过滤。</p>
            </section>
          </template>

          <template v-if="activeSection === '前端开发'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">前端开发</h1>
            <p class="text-white/50 text-sm mb-10">Vue 3 + Vite 前端开发指南</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">技术栈</h2>
              <ul class="list-disc list-inside text-white/50 text-sm space-y-1">
                <li>Vue 3 (Composition API + <code>&lt;script setup&gt;</code>)</li>
                <li>Vite 构建工具</li>
                <li>Pinia 状态管理</li>
                <li>Vue Router (hash 模式)</li>
                <li>TailwindCSS 样式</li>
                <li>@vueuse/motion 动效</li>
                <li>lucide-vue-next 图标</li>
              </ul>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">启动开发服务器</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>cd docs/dev-ops/aether-frontend-v2
npm install
npm run dev</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">构建生产版本</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>npm run build
# 产物在 dist/ 目录</code></pre>
            </section>
          </template>

          <template v-if="activeSection === 'Docker 部署'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">Docker 部署</h1>
            <p class="text-white/50 text-sm mb-10">容器化部署 Aether</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">构建镜像</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>cd aether-app
bash build.sh</code></pre>
            </section>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">运行容器</h2>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>docker run -d \
  -p 8080:8080 \
  -e OPENAI_API_KEY=sk-xxx \
  -v $(pwd)/agent-config:/app/config \
  aether:latest</code></pre>
            </section>
          </template>

          <template v-if="activeSection === '故障排查'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">故障排查</h1>
            <p class="text-white/50 text-sm mb-10">常见问题与解决方案</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">常见问题</h2>
              <table class="w-full text-sm border-collapse">
                <thead><tr class="border-b border-white/10"><th class="text-left py-2 text-white/60">问题</th><th class="text-left py-2 text-white/60">原因</th><th class="text-left py-2 text-white/60">解决</th></tr></thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2">HTTP 400</td><td class="py-2">tool_result 格式错误</td><td class="py-2">确认为 ToolResponseMessage（非 UserMessage），含 tool_call_id</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">agent 未注册</td><td class="py-2">YAML 配置路径错误</td><td class="py-2">检查 application-dev.yml 的 spring.config.import</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">ChatModel 注入失败</td><td class="py-2">缺少 @Lazy 注解</td><td class="py-2">ChatModel 在 ChatModelNode 装配阶段动态注册，所有消费者必须加 @Lazy</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">连接超时</td><td class="py-2">API 不可达</td><td class="py-2">检查 base-url / api-key / 网络连通性。默认 connect timeout 30s, read timeout 300s</td></tr>
                  <tr><td class="py-2">Agent 提前退出</td><td class="py-2">连续工具调用失败</td><td class="py-2">连续 3 轮全部工具失败触发熔断。检查 MCP 服务可用性</td></tr>
                </tbody>
              </table>
            </section>
          </template>

          <!-- ========== API 参考 ========== -->
          <template v-if="activeSection === 'Chat API'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">Chat API</h1>
            <p class="text-white/50 text-sm mb-10">对话接口完整参考</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">POST /api/v1/chat</h2>
              <p class="text-white/50 text-sm mb-3">同步对话接口，返回完整响应。</p>
              <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>{
  "agentId": "string",     // 必填：Agent 名称或工作流名称
  "userId": "string",      // 必填：用户标识
  "sessionId": "string",   // 可选：会话 ID（不传则自动创建）
  "message": "string"      // 必填：用户消息
}</code></pre>
            </section>
          </template>

          <template v-if="activeSection === 'SSE 流式协议'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">SSE 流式协议</h1>
            <p class="text-white/50 text-sm mb-10">Server-Sent Events 事件类型参考</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">POST /api/v1/chat/stream</h2>
              <p class="text-white/50 text-sm mb-3">流式对话接口，Accept: text/event-stream。</p>
              <table class="w-full text-sm border-collapse">
                <thead><tr class="border-b border-white/10"><th class="text-left py-2 text-white/60">事件类型</th><th class="text-left py-2 text-white/60">说明</th></tr></thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2"><code>text_delta</code></td><td class="py-2">LLM 文本增量输出</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2"><code>tool_use</code></td><td class="py-2">LLM 请求调用工具（含 toolCallId / toolName / input）</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2"><code>tool_result</code></td><td class="py-2">工具执行结果</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2"><code>permission_asking</code></td><td class="py-2">需要用户确认操作</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2"><code>compact_boundary</code></td><td class="py-2">上下文压缩边界标记</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2"><code>checkpoint</code></td><td class="py-2">检查点快照</td></tr>
                  <tr><td class="py-2"><code>done</code></td><td class="py-2">Agent 执行完成</td></tr>
                </tbody>
              </table>
            </section>
          </template>

          <template v-if="activeSection === 'Agent 管理 API'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">Agent 管理 API</h1>
            <p class="text-white/50 text-sm mb-10">Agent 配置与状态管理接口</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">可用接口</h2>
              <table class="w-full text-sm border-collapse">
                <thead><tr class="border-b border-white/10"><th class="text-left py-2 text-white/60">方法</th><th class="text-left py-2 text-white/60">路径</th><th class="text-left py-2 text-white/60">说明</th></tr></thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2">GET</td><td class="py-2"><code>/api/v1/agents</code></td><td class="py-2">获取所有已注册 Agent 列表</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">GET</td><td class="py-2"><code>/api/v1/agents/:id</code></td><td class="py-2">获取单个 Agent 详情</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">POST</td><td class="py-2"><code>/api/v1/agents</code></td><td class="py-2">动态注册新 Agent</td></tr>
                  <tr><td class="py-2">DELETE</td><td class="py-2"><code>/api/v1/agents/:id</code></td><td class="py-2">注销 Agent</td></tr>
                </tbody>
              </table>
            </section>
          </template>

          <template v-if="activeSection === '模型管理 API'">
            <h1 class="text-2xl md:text-3xl font-semibold mb-2">模型管理 API</h1>
            <p class="text-white/50 text-sm mb-10">模型配置管理接口</p>
            <section class="mb-10">
              <h2 class="text-lg font-semibold text-white mb-3">可用接口</h2>
              <table class="w-full text-sm border-collapse">
                <thead><tr class="border-b border-white/10"><th class="text-left py-2 text-white/60">方法</th><th class="text-left py-2 text-white/60">路径</th><th class="text-left py-2 text-white/60">说明</th></tr></thead>
                <tbody class="text-white/50">
                  <tr class="border-b border-white/5"><td class="py-2">GET</td><td class="py-2"><code>/api/v1/models</code></td><td class="py-2">获取所有配置的模型列表</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">POST</td><td class="py-2"><code>/api/v1/models</code></td><td class="py-2">添加新模型配置</td></tr>
                  <tr class="border-b border-white/5"><td class="py-2">PUT</td><td class="py-2"><code>/api/v1/models/:id</code></td><td class="py-2">更新模型配置</td></tr>
                  <tr><td class="py-2">DELETE</td><td class="py-2"><code>/api/v1/models/:id</code></td><td class="py-2">删除模型配置</td></tr>
                </tbody>
              </table>
            </section>
          </template>

        </div>
      </main>
    </div>
  </div>
</template>
