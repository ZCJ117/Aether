<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { PanelLeftClose, PanelLeftOpen, Bell } from 'lucide-vue-next'
import SearchInput from './SearchInput.vue'
import ThemeToggle from './ThemeToggle.vue'
import LocaleToggle from './LocaleToggle.vue'
import { ref } from 'vue'

const ui = useUiStore()
const route = useRoute()

const searchQuery = ref('')

const breadcrumbs = computed(() => {
  const matched = route.matched.filter((r) => r.meta?.title)
  return matched.map((r) => ({
    title: r.meta?.title as string ?? r.name as string ?? '',
    path: r.path,
  }))
})
</script>

<template>
  <header class="h-14 border-b border-white/5 flex items-center px-4 gap-4 flex-shrink-0 bg-surface-base">
    <!-- Hamburger -->
    <button
      @click="ui.toggleSidebar()"
      class="p-2 rounded-lg text-secondary hover:text-primary hover:bg-white/[0.05] transition-colors"
    >
      <PanelLeftClose v-if="!ui.sidebarCollapsed" class="w-4 h-4" />
      <PanelLeftOpen v-else class="w-4 h-4" />
    </button>

    <!-- Breadcrumb -->
    <nav class="flex items-center gap-1.5 flex-1 min-w-0">
      <template v-for="(crumb, idx) in breadcrumbs" :key="idx">
        <span v-if="idx > 0" class="text-secondary/40 text-sm">/</span>
        <span
          :class="idx === breadcrumbs.length - 1 ? 'text-primary' : 'text-secondary hover:text-primary'"
          class="text-sm truncate"
        >
          {{ crumb.title }}
        </span>
      </template>
    </nav>

    <!-- Right actions -->
    <div class="flex items-center gap-2">
      <SearchInput v-model="searchQuery" class="w-48" />
      <button
        class="p-2 rounded-lg text-secondary hover:text-primary hover:bg-white/[0.05] transition-colors relative"
      >
        <Bell class="w-4 h-4" />
      </button>
      <ThemeToggle />
      <LocaleToggle />
    </div>
  </header>
</template>
