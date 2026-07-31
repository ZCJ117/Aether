<script setup lang="ts">
import { Pencil, Trash2 } from 'lucide-vue-next'
import type { AgentFullConfig } from '@/types/agent'
import StatusBadge from '@/components/common/StatusBadge.vue'

defineProps<{
  agent: AgentFullConfig
}>()

const emit = defineEmits<{
  select: [agent: AgentFullConfig]
  edit: [agent: AgentFullConfig]
  delete: [agent: AgentFullConfig]
}>()

const typeMap: Record<string, string> = {
  re_act: 'ReAct',
  plan_act: 'PlanAct',
}
</script>

<template>
  <div
    class="group bg-surface-card border border-white/5 rounded-xl p-5 cursor-pointer hover:border-white/10 transition-all relative"
    @click="emit('select', agent)"
  >
    <div class="absolute top-3 right-3 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
      <button
        class="p-1.5 rounded-md hover:bg-white/10 text-text-muted hover:text-text-primary transition-colors"
        @click.stop="emit('edit', agent)"
      >
        <Pencil class="w-3.5 h-3.5" />
      </button>
      <button
        class="p-1.5 rounded-md hover:bg-accent-red/20 text-text-muted hover:text-accent-red transition-colors"
        @click.stop="emit('delete', agent)"
      >
        <Trash2 class="w-3.5 h-3.5" />
      </button>
    </div>

    <div class="flex items-start gap-3 mb-3">
      <div class="w-10 h-10 rounded-lg bg-blue-500/10 flex items-center justify-center flex-shrink-0">
        <span class="text-blue-400 font-bold text-sm">{{ agent.agentName.charAt(0) }}</span>
      </div>
      <div class="min-w-0">
        <h4 class="text-sm font-medium text-text-primary truncate">{{ agent.agentName }}</h4>
        <p class="text-xs text-text-muted truncate mt-0.5">{{ agent.agentDesc }}</p>
      </div>
    </div>

    <div class="flex items-center gap-2 flex-wrap">
      <span class="text-2xs px-1.5 py-0.5 rounded bg-white/5 text-text-muted">
        {{ typeMap[agent.agentType] || agent.agentType }}
      </span>
      <span class="text-2xs px-1.5 py-0.5 rounded bg-white/5 text-text-muted">
        {{ agent.toolNames?.length || 0 }} 工具
      </span>
      <StatusBadge :status="agent.status?.toLowerCase()" size="sm" />
    </div>
  </div>
</template>
