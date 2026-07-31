<script setup lang="ts">
import { Sun, Moon } from 'lucide-vue-next'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
</script>

<template>
  <button
    @click="ui.toggleTheme()"
    class="p-2 rounded-lg text-secondary hover:text-primary hover:bg-white/[0.05] transition-colors"
    :title="ui.isDark ? '切换浅色模式' : '切换深色模式'"
  >
    <Moon v-if="ui.isDark" class="w-4 h-4" />
    <Sun v-else class="w-4 h-4" />
  </button>
</template>
