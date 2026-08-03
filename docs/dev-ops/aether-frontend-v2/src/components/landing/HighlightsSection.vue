<script setup lang="ts">
const highlights = [
  {
    icon: '🔄',
    title: '自研 ReActAgent 引擎',
    desc: '主循环 + 多层上下文压缩 + 指数退避重试，100 轮稳定运行，连续工具失败自动熔断',
    color: 'border-l-[#5AC8FA]',
  },
  {
    icon: '📊',
    title: 'YAML 配置驱动多 Agent 编排',
    desc: 'SEQUENTIAL / PARALLEL / LOOP 三种工作流模式，声明式定义 Agent 协作拓扑',
    color: 'border-l-[#30D158]',
  },
  {
    icon: '🔧',
    title: 'Agent 级工具作用域',
    desc: 'MCP + Skills 工具接入，per-agent 精确控制工具可见性，支持并发安全工作编排',
    color: 'border-l-[#FF9F0A]',
  },
  {
    icon: '🏗️',
    title: 'DDD 分层架构',
    desc: '6 模块清晰分层：trigger → api → domain → infrastructure，禁止反向依赖',
    color: 'border-l-[#BF5AF2]',
  },
  {
    icon: '⚡',
    title: '流式对话 & 工具编排',
    desc: 'SSE 实时推送，工具并发/串行可配，上下文自动压缩，Provider 级重试策略',
    color: 'border-l-[#FF453A]',
  },
]
</script>

<template>
  <section id="highlights" class="max-w-6xl mx-auto px-6 py-20 md:py-32">
    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
    >
      <h2 class="text-3xl md:text-5xl font-semibold tracking-tight text-white text-center mb-4">
        Aether 核心亮点
      </h2>
      <p class="text-white/60 text-center max-w-lg mx-auto mb-16 text-sm leading-relaxed">
        从单 Agent 到多 Agent 集群，Aether 提供企业级 AI 运行时所需的一切
      </p>
    </div>

    <div class="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="(item, i) in highlights"
        :key="item.title"
        v-motion
        :initial="{ opacity: 0, y: 24 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: i * 0.1, duration: 600, ease: [0.22, 1, 0.36, 1] } }"
        class="bg-white/[0.03] border border-white/10 rounded-2xl p-6 hover:bg-white/[0.06] transition-colors duration-300"
        :class="item.color"
      >
        <span class="text-2xl">{{ item.icon }}</span>
        <h3 class="text-white font-semibold mt-4 mb-2 text-base">{{ item.title }}</h3>
        <p class="text-white/50 text-sm leading-relaxed">{{ item.desc }}</p>
      </div>
    </div>
  </section>
</template>
