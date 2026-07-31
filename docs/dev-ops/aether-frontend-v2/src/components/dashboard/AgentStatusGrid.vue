<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { AgentStat } from '@/types/dashboard'

const props = defineProps<{
  agents: AgentStat[]
}>()

const router = useRouter()

const gridClass = computed(() => {
  const len = props.agents.length
  if (len <= 2) return 'grid-cols-1'
  return 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3'
})

function toAgent(id: string) {
  router.push(`/agent/${id}`)
}
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-5">
    <h3 class="text-sm font-medium text-text-primary mb-4">智能体状态</h3>

    <div v-if="agents.length === 0" class="text-center py-8 text-text-muted text-sm">
      暂无智能体数据
    </div>

    <div v-else :class="gridClass" class="grid gap-3">
      <div
        v-for="agent in agents"
        :key="agent.agentId"
        class="bg-white/[0.03] border border-white/5 rounded-lg p-3 cursor-pointer hover:bg-white/[0.06] transition-colors"
        @click="toAgent(agent.agentId)"
      >
        <div class="flex items-center gap-2 mb-2">
          <span
            class="w-2 h-2 rounded-full flex-shrink-0"
            :class="agent.status === 'active' ? 'bg-green-400' : 'bg-slate-500'"
          />
          <span class="text-sm font-medium text-text-primary truncate">{{ agent.agentName }}</span>
        </div>
        <div class="flex gap-4 text-xs text-text-muted">
          <span>会话 {{ agent.sessionCount }}</span>
          <span>{{ (agent.tokenCount / 1000).toFixed(1) }}k Token</span>
        </div>
      </div>
    </div>
  </div>
</template>
