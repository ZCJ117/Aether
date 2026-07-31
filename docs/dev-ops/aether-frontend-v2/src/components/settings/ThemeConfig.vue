<script setup lang="ts">
import { Sun, Moon } from 'lucide-vue-next'
import { useUiStore, type Theme } from '@/stores/ui'

const uiStore = useUiStore()

function selectTheme(t: Theme) {
  uiStore.setTheme(t)
}
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-5">
    <h3 class="text-sm font-medium text-text-primary mb-4">主题设置</h3>

    <div class="grid grid-cols-2 gap-3">
      <label
        class="relative p-4 rounded-xl border-2 cursor-pointer transition-all"
        :class="uiStore.isDark ? 'border-blue-500/50 bg-blue-500/5' : 'border-white/5 hover:border-white/10'"
      >
        <input
          type="radio"
          name="theme"
          value="dark"
          :checked="uiStore.isDark"
          class="sr-only"
          @change="selectTheme('dark')"
        />
        <div class="flex items-center justify-between mb-3">
          <Moon class="w-5 h-5 text-blue-400" />
          <span
            v-if="uiStore.isDark"
            class="w-5 h-5 rounded-full border-2 border-blue-500 bg-blue-500 flex items-center justify-center"
          >
            <span class="w-2 h-2 rounded-full bg-white" />
          </span>
        </div>
        <div class="rounded-lg bg-[#0f0f0f] border border-white/5 p-3">
          <div class="h-1.5 w-12 bg-white/10 rounded mb-2" />
          <div class="h-1 w-20 bg-white/5 rounded mb-1.5" />
          <div class="h-1 w-16 bg-white/5 rounded" />
        </div>
        <p class="text-sm text-text-primary mt-3">暗夜模式</p>
      </label>

      <label
        class="relative p-4 rounded-xl border-2 cursor-pointer transition-all"
        :class="!uiStore.isDark ? 'border-blue-500/50 bg-blue-500/5' : 'border-white/5 hover:border-white/10'"
      >
        <input
          type="radio"
          name="theme"
          value="light"
          :checked="!uiStore.isDark"
          class="sr-only"
          @change="selectTheme('light')"
        />
        <div class="flex items-center justify-between mb-3">
          <Sun class="w-5 h-5 text-amber-400" />
          <span
            v-if="!uiStore.isDark"
            class="w-5 h-5 rounded-full border-2 border-blue-500 bg-blue-500 flex items-center justify-center"
          >
            <span class="w-2 h-2 rounded-full bg-white" />
          </span>
        </div>
        <div class="rounded-lg bg-[#f5f5f5] border border-gray-200 p-3">
          <div class="h-1.5 w-12 bg-gray-300 rounded mb-2" />
          <div class="h-1 w-20 bg-gray-200 rounded mb-1.5" />
          <div class="h-1 w-16 bg-gray-200 rounded" />
        </div>
        <p class="text-sm text-text-primary mt-3">浅色模式</p>
      </label>
    </div>
  </div>
</template>
