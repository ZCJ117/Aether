<script setup lang="ts">
import type { Skill } from '@/types/skill'

defineProps<{
  skill: Skill
}>()

const emit = defineEmits<{
  toggle: [skill: Skill]
  detail: [skill: Skill]
}>()

const categoryColors: Record<string, string> = {
  mcp: 'bg-purple-500/10 text-purple-400',
  resource: 'bg-blue-500/10 text-blue-400',
  directory: 'bg-amber-500/10 text-amber-400',
  knowledge: 'bg-green-500/10 text-green-400',
}
</script>

<template>
  <div
    class="bg-surface-card border border-white/5 rounded-xl p-4 hover:border-white/10 transition-all cursor-pointer"
    @click="emit('detail', skill)"
  >
    <div class="flex items-start justify-between gap-3">
      <div class="flex-1 min-w-0">
        <h4 class="text-sm font-medium text-text-primary truncate">{{ skill.name }}</h4>
        <p class="text-xs text-text-muted mt-1 line-clamp-2">{{ skill.description }}</p>
      </div>

      <button
        class="relative inline-flex h-5 w-9 flex-shrink-0 items-center rounded-full transition-colors cursor-pointer"
        :class="skill.enabled ? 'bg-blue-600' : 'bg-white/10'"
        @click.stop="emit('toggle', skill)"
      >
        <span
          class="inline-block h-3.5 w-3.5 rounded-full bg-white transition-transform"
          :class="skill.enabled ? 'translate-x-[18px]' : 'translate-x-[3px]'"
        />
      </button>
    </div>

    <div class="flex items-center gap-2 mt-3">
      <span
        class="text-2xs px-1.5 py-0.5 rounded"
        :class="categoryColors[skill.type] || 'bg-white/5 text-text-muted'"
      >
        {{ skill.category }}
      </span>
      <span v-if="skill.version" class="text-2xs text-text-muted">v{{ skill.version }}</span>
    </div>
  </div>
</template>
