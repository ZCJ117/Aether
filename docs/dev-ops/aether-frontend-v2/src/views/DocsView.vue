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
      { label: 'ReActAgent 引擎详解' },
      { label: 'YAML 配置完整参考' },
      { label: 'AgentGraph 与工作流' },
      { label: '工具系统 (MCP/Skills)' },
      { label: '上下文管理策略' },
      { label: '记忆系统' },
    ],
  },
  {
    label: '开发指南',
    children: [
      { label: '6 模块 DDD 架构' },
      { label: '自定义 Agent 开发' },
      { label: '添加新工具' },
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

const activeSection = ref('快速开始')
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
          <h1 class="text-2xl md:text-3xl font-semibold mb-2">快速开始</h1>
          <p class="text-white/50 text-sm mb-10">5 分钟启动第一个 Aether Agent</p>

          <section class="mb-10">
            <h2 class="text-lg font-semibold text-white mb-3">环境要求</h2>
            <ul class="list-disc list-inside text-white/60 text-sm space-y-1">
              <li>JDK 17+</li>
              <li>Maven 3.8+</li>
              <li>Node.js 18+（前端开发）</li>
            </ul>
          </section>

          <section class="mb-10">
            <h2 class="text-lg font-semibold text-white mb-3">克隆与构建</h2>
            <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>git clone https://github.com/your-org/aether.git
cd aether
mvn clean install -DskipTests
cd aether-app && mvn spring-boot:run</code></pre>
          </section>

          <section class="mb-10">
            <h2 class="text-lg font-semibold text-white mb-3">最小配置模板</h2>
            <p class="text-white/50 text-sm mb-3">在 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">aether-app/src/main/resources/agent/</code> 下创建 YAML 配置文件：</p>
            <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>ai-api:
  base-url: https://api.openai.com
  api-key: ${OPENAI_API_KEY}
chat-model:
  model: gpt-4o
agents:
  - name: assistant
    system-prompt: 你是一个有用的助手。
runner:
  agent-name: assistant</code></pre>
          </section>

          <section class="mb-10">
            <h2 class="text-lg font-semibold text-white mb-3">验证 Agent 运行</h2>
            <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello Aether!"}'</code></pre>
          </section>
        </div>
      </main>
    </div>
  </div>
</template>
