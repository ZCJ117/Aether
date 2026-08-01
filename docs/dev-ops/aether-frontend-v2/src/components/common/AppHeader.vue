<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { PanelLeftClose, PanelLeftOpen } from 'lucide-vue-next'

const ui = useUiStore()
const route = useRoute()

const breadcrumbs = computed(() => {
  const matched = route.matched.filter((r) => r.meta?.title)
  return matched.map((r) => ({
    title: (r.meta?.title as string) ?? (r.name as string) ?? '',
    path: r.path,
  }))
})
</script>

<template>
  <header
    class="h-11 flex items-center px-5 gap-3 flex-shrink-0"
    style="background: rgba(28,28,30,0.80); backdrop-filter: blur(20px) saturate(180%); -webkit-backdrop-filter: blur(20px) saturate(180%); border-bottom: 0.5px solid rgba(255,255,255,0.06);"
  >
    <!-- Sidebar toggle -->
    <button
      @click="ui.toggleSidebar()"
      class="p-1.5 rounded-md transition-colors"
      style="color: #98989D;"
      onmouseover="this.style.color='#F5F5F7'; this.style.background='rgba(255,255,255,0.04)'"
      onmouseout="this.style.color='#98989D'; this.style.background='transparent'"
    >
      <PanelLeftClose v-if="!ui.sidebarCollapsed" class="w-[18px] h-[18px]" />
      <PanelLeftOpen v-else class="w-[18px] h-[18px]" />
    </button>

    <!-- Breadcrumb -->
    <nav class="flex items-center gap-1.5 flex-1 min-w-0">
      <template v-for="(crumb, idx) in breadcrumbs" :key="idx">
        <span v-if="idx > 0" class="text-sm" style="color: #48484A;">/</span>
        <span
          class="text-xs truncate"
          :style="idx === breadcrumbs.length - 1 ? 'color: #F5F5F7;' : 'color: #98989D;'"
        >
          {{ crumb.title }}
        </span>
      </template>
    </nav>
  </header>
</template>
