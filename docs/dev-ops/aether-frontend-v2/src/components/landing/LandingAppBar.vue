<script setup lang="ts">
import { Search } from 'lucide-vue-next'
import AetherLogo from './AetherLogo.vue'

const MENU_ITEMS = ['File', 'Edit', 'View', 'Agent', 'Window', 'Help']

function visibilityClass(index: number): string {
  if (index > 3) return 'hidden md:inline'
  if (index > 2) return 'hidden sm:inline'
  return 'inline'
}
</script>

<template>
  <div
    v-motion
    :initial="{ opacity: 0, y: -8 }"
    :enter="{ opacity: 1, y: 0, transition: { delay: 900, duration: 600, ease: 'easeOut' } }"
    class="w-full h-10 bg-black/40 backdrop-blur-md border-t border-b border-white/10"
  >
    <div class="max-w-6xl mx-auto px-6 h-full flex items-center justify-between text-xs">
      <div class="flex items-center gap-4 text-white/80">
        <AetherLogo className="w-3.5 h-3.5" />
        <span class="font-bold text-white">Aether</span>
        <span
          v-for="(item, i) in MENU_ITEMS"
          :key="item"
          :class="[visibilityClass(i), 'hover:text-white cursor-default']"
        >
          {{ item }}
        </span>
      </div>
      <div class="flex items-center gap-3 text-white/80">
        <Search class="w-3.5 h-3.5" :stroke-width="2" />
        <span>Aether Agent Console</span>
      </div>
    </div>
  </div>
</template>
