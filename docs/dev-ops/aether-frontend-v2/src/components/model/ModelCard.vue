<script setup lang="ts">
import { Edit3, Trash2, Play } from 'lucide-vue-next'
import type { ModelConfig } from '@/types/model'
import StatusBadge from '@/components/common/StatusBadge.vue'

defineProps<{
  model: ModelConfig
}>()

const emit = defineEmits<{
  select: [model: ModelConfig]
  edit: [model: ModelConfig]
  delete: [model: ModelConfig]
  test: [model: ModelConfig]
}>()
</script>

<template>
  <div
    class="group bg-surface-card border border-white/5 rounded-xl p-5 cursor-pointer hover:border-white/10 transition-all relative"
    @click="emit('select', model)"
  >
    <div class="absolute top-3 right-3 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
      <button
        class="p-1.5 rounded-md hover:bg-green-500/20 text-text-muted hover:text-green-400 transition-colors"
        title="测试连通性"
        @click.stop="emit('test', model)"
      >
        <Play class="w-3.5 h-3.5" />
      </button>
      <button
        class="p-1.5 rounded-md hover:bg-white/10 text-text-muted hover:text-text-primary transition-colors"
        @click.stop="emit('edit', model)"
      >
        <Edit3 class="w-3.5 h-3.5" />
      </button>
      <button
        class="p-1.5 rounded-md hover:bg-accent-red/20 text-text-muted hover:text-accent-red transition-colors"
        @click.stop="emit('delete', model)"
      >
        <Trash2 class="w-3.5 h-3.5" />
      </button>
    </div>

    <div class="flex items-center gap-3 mb-3">
      <div class="w-10 h-10 rounded-lg bg-purple-500/10 flex items-center justify-center flex-shrink-0">
        <span class="text-purple-400 font-bold text-sm">{{ model.providerId.charAt(0).toUpperCase() }}</span>
      </div>
      <div class="min-w-0">
        <h4 class="text-sm font-medium text-text-primary truncate">{{ model.providerId }}</h4>
        <p class="text-xs text-text-muted truncate mt-0.5 font-mono">{{ model.modelId }}</p>
      </div>
    </div>

    <div class="flex items-center gap-2">
      <StatusBadge :status="model.status?.toLowerCase()" size="sm" />
      <span v-if="model.maxTokens" class="text-2xs text-text-muted">{{ (model.maxTokens / 1000).toFixed(0) }}k max</span>
    </div>
  </div>
</template>
