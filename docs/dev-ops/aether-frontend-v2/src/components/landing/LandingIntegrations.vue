<script setup lang="ts">
const PARTNERS = ['OpenAI', 'Anthropic', 'DeepSeek', 'Qwen', 'Llama', 'MCP', 'LangChain', 'Spring AI']
</script>

<template>
  <section class="max-w-6xl mx-auto px-6 py-16 md:py-20">
    <p class="text-center text-xs uppercase tracking-widest text-white/40">
      与业界领先的 AI 生态深度集成
    </p>
    <div class="mt-10 grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-8 gap-6">
      <div
        v-for="(name, i) in PARTNERS"
        :key="name"
        v-motion
        :initial="{ opacity: 0, y: 8 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: i * 50, duration: 500, ease: 'easeOut' } }"
        class="flex items-center justify-center"
      >
        <span class="text-sm font-semibold tracking-tight text-white/50 hover:text-white transition-colors cursor-default">
          {{ name }}
        </span>
      </div>
    </div>
  </section>
</template>
