<script setup lang="ts">
import { Menu } from 'lucide-vue-next'
import AetherLogo from './AetherLogo.vue'
import LandingButton from './LandingButton.vue'

const NAV_LINKS = [
  { label: '特性', href: '#features' },
  { label: '定价', href: '#pricing' },
  { label: '文档', href: '#docs' },
  { label: '关于', href: '#about' },
]

const emit = defineEmits<{
  cta: []
}>()
</script>

<template>
  <nav
    v-motion
    :initial="{ opacity: 0, y: -10 }"
    :enter="{ opacity: 1, y: 0, transition: { duration: 600, ease: 'easeOut' } }"
    class="relative z-20 max-w-6xl mx-auto px-6 py-6 flex items-center justify-between"
  >
    <a href="#" aria-label="Aether home" class="text-white">
      <AetherLogo className="w-8 h-8" />
    </a>

    <div class="hidden md:flex gap-8">
      <a
        v-for="(link, i) in NAV_LINKS"
        :key="link.label"
        :href="link.href"
        :style="{ animationDelay: `${0.1 + i * 0.05}s` }"
        class="text-white/70 text-sm font-medium hover:text-white transition-colors animate-fade-in"
      >
        {{ link.label }}
      </a>
    </div>

    <div class="hidden md:block">
      <LandingButton label="开始使用" @click="emit('cta')" />
    </div>

    <button
      type="button"
      aria-label="Open menu"
      class="md:hidden w-10 h-10 rounded-full border border-white/10 bg-white/5 flex items-center justify-center text-white/80 hover:text-white"
    >
      <Menu class="w-4 h-4" />
    </button>
  </nav>
</template>
