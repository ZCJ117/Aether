<script setup lang="ts">
const milestones = [
  {
    version: 'v1.0 — 核心引擎',
    status: 'completed',
    statusLabel: '已完成',
    items: 'ReActAgent 主循环 · ChatModel 延迟注入 · MCP/Skills 工具集成 · DDD 6 模块分层 · SSE 流式对话',
  },
  {
    version: 'v1.1 — 多 Agent 编排',
    status: 'completed',
    statusLabel: '已完成',
    items: 'YAML 配置驱动 AgentGraph · SEQUENTIAL / PARALLEL / LOOP 工作流 · 上下文压缩与熔断 · Vue 3 前端',
  },
  {
    version: 'v1.2 — 工具生态 & 安全',
    status: 'in-progress',
    statusLabel: '进行中',
    items: 'Agent 级工具作用域 · 工具调用权限控制 · Skills 本地执行沙箱 · 审计日志 · 多 Provider 路由',
  },
  {
    version: 'v1.3 — 记忆 & 知识图谱',
    status: 'planned',
    statusLabel: '计划中',
    items: '向量化长期记忆 · 知识图谱自动构建 · 跨会话上下文继承 · RAG 检索增强 · 用户级记忆隔离',
  },
  {
    version: 'v2.0 — 团队协作',
    status: 'planned',
    statusLabel: '计划中',
    items: '多用户工作空间 · Agent 共享与权限 · 团队知识库 · Webhook 触发器 · 定时任务调度 · 企业 SSO',
  },
]

function statusClass(status: string) {
  switch (status) {
    case 'completed': return { dot: 'bg-[#30D158]', badge: 'bg-[#30D158]/20 text-[#30D158]' }
    case 'in-progress': return { dot: 'bg-[#5AC8FA]', badge: 'bg-[#5AC8FA]/20 text-[#5AC8FA]' }
    default: return { dot: 'bg-white/20', badge: 'bg-white/10 text-white/40' }
  }
}
</script>

<template>
  <section id="roadmap" class="max-w-6xl mx-auto px-6 py-20 md:py-32">
    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
    >
      <h2 class="text-3xl md:text-5xl font-semibold tracking-tight text-white text-center mb-4">
        Aether 路线图
      </h2>
      <p class="text-white/60 text-center max-w-lg mx-auto mb-16 text-sm leading-relaxed">
        企业级多智能体 AI 运行时演进路径
      </p>
    </div>

    <div class="max-w-2xl mx-auto border-l-2 border-white/10 pl-8 space-y-12">
      <div
        v-for="(m, i) in milestones"
        :key="m.version"
        v-motion
        :initial="{ opacity: 0, x: -20 }"
        :visible="{ opacity: 1, x: 0, transition: { delay: i * 0.15, duration: 600, ease: [0.22, 1, 0.36, 1] } }"
        class="relative"
      >
        <div
          class="absolute -left-[calc(2rem+5px)] top-1.5 w-3 h-3 rounded-full border-2 border-[#0c0c0c]"
          :class="statusClass(m.status).dot"
        />
        <span
          class="inline-block text-xs font-semibold px-2.5 py-0.5 rounded-full mb-3"
          :class="statusClass(m.status).badge"
        >
          {{ m.statusLabel }}
        </span>
        <h3 class="text-xl font-semibold text-white mb-2">{{ m.version }}</h3>
        <p class="text-white/50 text-sm leading-relaxed">{{ m.items }}</p>
      </div>
    </div>
  </section>
</template>
