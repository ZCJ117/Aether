<script setup lang="ts">
import { ChevronRight } from 'lucide-vue-next'
import LandingButton from './LandingButton.vue'

const emit = defineEmits<{
  cta: []
}>()
</script>

<template>
  <section class="max-w-6xl mx-auto px-6 py-20 md:py-32">
    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
      class="liquid-glass relative overflow-hidden rounded-3xl px-8 py-16 md:py-24 text-center"
    >
      <div
        class="pointer-events-none absolute inset-0"
        style="
          background: radial-gradient(600px circle at 50% 0%, rgba(255,255,255,0.15), transparent 70%);
          opacity: 0.3;
        "
      />

      <div class="relative">
        <h2 class="text-4xl md:text-6xl font-semibold tracking-tight leading-[1.02]">
          告别重复劳动。
          <br />
          开启智能协作。
        </h2>
        <p class="mt-6 text-white/60 max-w-md mx-auto text-sm leading-[1.6]">
          加入数千名开发者、架构师和技术负责人，用 Aether 构建下一代 AI 智能体系统。
        </p>
        <div class="mt-8 flex flex-col sm:flex-row items-center justify-center gap-3">
          <LandingButton label="开始使用" @click="emit('cta')" />
          <button
            type="button"
            class="group inline-flex items-center justify-center gap-1.5 rounded-full border border-white/15 text-white text-sm font-medium px-5 py-3 hover:bg-white/5 transition-colors"
          >
            <span>联系我们</span>
            <ChevronRight
              class="w-4 h-4 transition-transform duration-200 group-hover:translate-x-[1px]"
              :stroke-width="2"
            />
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
