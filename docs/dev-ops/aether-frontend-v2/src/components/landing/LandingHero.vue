<script setup lang="ts">
import LandingButton from './LandingButton.vue'

const emit = defineEmits<{
  cta: []
}>()

const gradientStyle = {
  backgroundImage:
    'linear-gradient(to right, #091020 0%, #0B2551 12.5%, #A4F4FD 32.5%, #00d2ff 50%, #0B2551 67.5%, #091020 87.5%, #091020 100%)',
  backgroundSize: '200% auto',
  WebkitBackgroundClip: 'text',
  backgroundClip: 'text',
  color: 'transparent',
  WebkitTextFillColor: 'transparent',
  filter: 'url(#lp-noise)',
}
</script>

<template>
  <section class="pt-16 md:pt-28 pb-20 text-center flex flex-col items-center px-6">
    <h1
      v-motion
      :initial="{ opacity: 0, y: 20 }"
      :enter="{ opacity: 1, y: 0, transition: { delay: 300, duration: 800, ease: [0.22, 1, 0.36, 1] } }"
      class="text-4xl md:text-7xl font-semibold tracking-tight leading-[0.9]"
    >
      <span class="block text-white">你的 AI 智能体。</span>
      <span class="block animate-shiny" :style="gradientStyle">即刻编排</span>
    </h1>

    <p
      v-motion
      :initial="{ opacity: 0, y: 20 }"
      :enter="{ opacity: 1, y: 0, transition: { delay: 500, duration: 800, ease: [0.22, 1, 0.36, 1] } }"
      class="mt-8 text-white/60 max-w-md text-base leading-[1.5]"
    >
      Aether 是企业级多智能体 AI 运行时平台。构建、编排和部署可扩展的多智能体系统，从原型到生产一站式交付。
    </p>

    <div
      v-motion
      :initial="{ opacity: 0, y: 20 }"
      :enter="{ opacity: 1, y: 0, transition: { delay: 700, duration: 800, ease: [0.22, 1, 0.36, 1] } }"
      class="mt-8 flex flex-col items-center gap-2"
    >
      <LandingButton label="开始使用" @click="emit('cta')" />
      <p class="text-xs text-white/40">开源 · 企业级 · 生产就绪</p>
    </div>
  </section>
</template>
