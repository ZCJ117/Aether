<script setup lang="ts">
const domainComponents = [
  { name: 'ReActAgent', desc: '主循环 · 重试 · 熔断' },
  { name: 'GraphExecutor', desc: '顺序 · 并行 · 循环' },
  { name: 'ContextManager', desc: '压缩 · 截断 · 预算' },
  { name: 'ToolExecutor', desc: '并发 · 串行 · 适配' },
  { name: 'ArmoryService', desc: 'YAML → Bean 装配' },
  { name: 'MemoryStore', desc: '持久记忆读写' },
]
</script>

<template>
  <section id="architecture" class="max-w-6xl mx-auto px-6 py-20 md:py-32">
    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
    >
      <h2 class="text-3xl md:text-5xl font-semibold tracking-tight text-white text-center mb-4">
        系统架构
      </h2>
      <p class="text-white/60 text-center max-w-lg mx-auto mb-16 text-sm leading-relaxed">
        DDD 6 模块分层，自研 ReActAgent 引擎驱动
      </p>
    </div>

    <div class="max-w-2xl mx-auto space-y-4">
      <!-- Trigger -->
      <div
        v-motion
        :initial="{ opacity: 0, x: -20 }"
        :visible="{ opacity: 1, x: 0, transition: { delay: 0.1, duration: 600 } }"
        class="bg-white/[0.03] border border-white/10 rounded-xl p-5"
      >
        <div class="flex items-center gap-3 mb-2">
          <span class="text-xs font-bold px-2 py-0.5 rounded bg-[#5AC8FA] text-black">Trigger</span>
          <span class="text-white font-semibold text-sm">aether-trigger</span>
        </div>
        <p class="text-white/50 text-xs">REST 控制器层，Chat API / SSE 流式端点</p>
      </div>

      <div class="text-center text-white/20 text-sm">↓</div>

      <!-- API -->
      <div
        v-motion
        :initial="{ opacity: 0, x: -20 }"
        :visible="{ opacity: 1, x: 0, transition: { delay: 0.2, duration: 600 } }"
        class="bg-white/[0.03] border border-white/10 rounded-xl p-5"
      >
        <div class="flex items-center gap-3 mb-2">
          <span class="text-xs font-bold px-2 py-0.5 rounded bg-[#30D158] text-black">API</span>
          <span class="text-white font-semibold text-sm">aether-api</span>
        </div>
        <p class="text-white/50 text-xs">服务接口 & DTO 定义，跨模块契约</p>
      </div>

      <div class="text-center text-white/20 text-sm">↓</div>

      <!-- Domain Core -->
      <div
        v-motion
        :initial="{ opacity: 0, x: -20 }"
        :visible="{ opacity: 1, x: 0, transition: { delay: 0.3, duration: 600 } }"
        class="bg-white/[0.03] border-2 border-[#FF9F0A]/40 rounded-xl p-5"
      >
        <div class="flex items-center gap-3 mb-4">
          <span class="text-xs font-bold px-2 py-0.5 rounded bg-[#FF9F0A] text-black">Domain Core</span>
          <span class="text-white font-semibold text-sm">aether-domain</span>
        </div>
        <div class="grid grid-cols-2 sm:grid-cols-3 gap-3">
          <div
            v-for="comp in domainComponents"
            :key="comp.name"
            class="bg-white/[0.05] rounded-lg p-3 text-center"
          >
            <div class="text-white text-xs font-semibold">{{ comp.name }}</div>
            <div class="text-white/40 text-[10px] mt-1">{{ comp.desc }}</div>
          </div>
        </div>
      </div>

      <div class="text-center text-white/20 text-sm">↓</div>

      <!-- Infra + Types -->
      <div
        v-motion
        :initial="{ opacity: 0, x: -20 }"
        :visible="{ opacity: 1, x: 0, transition: { delay: 0.4, duration: 600 } }"
        class="grid grid-cols-2 gap-4"
      >
        <div class="bg-white/[0.03] border border-white/10 rounded-xl p-5">
          <div class="flex items-center gap-3 mb-2">
            <span class="text-xs font-bold px-2 py-0.5 rounded bg-[#BF5AF2] text-white">Infra</span>
            <span class="text-white font-semibold text-sm">aether-infrastructure</span>
          </div>
          <p class="text-white/50 text-xs">SessionStore · MCP Client · Adapter</p>
        </div>
        <div class="bg-white/[0.03] border border-white/10 rounded-xl p-5">
          <div class="flex items-center gap-3 mb-2">
            <span class="text-xs font-bold px-2 py-0.5 rounded bg-white/20 text-white">Types</span>
            <span class="text-white font-semibold text-sm">aether-types</span>
          </div>
          <p class="text-white/50 text-xs">枚举 · 异常 · 常量定义</p>
        </div>
      </div>
    </div>
  </section>
</template>
