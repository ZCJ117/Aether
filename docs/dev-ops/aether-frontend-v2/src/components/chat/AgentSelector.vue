<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { ChevronDown, Loader2, AlertCircle } from 'lucide-vue-next'
import { useAgentStore } from '@/stores/agent'

const agentStore = useAgentStore()
const open = ref(false)
const el = ref<HTMLElement | null>(null)

function toggle() {
  open.value = !open.value
}

function select(agentId: string) {
  agentStore.selectAgent(agentId)
  open.value = false
}

function handleClickOutside(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (el.value && !el.value.contains(target)) {
    open.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
  if (!agentStore.hasAgents && !agentStore.isLoading) {
    agentStore.loadAgents()
  }
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <div ref="el" class="relative" data-dropdown>
    <!-- Trigger button -->
    <button
      class="flex items-center gap-2 rounded-lg border border-white/5 bg-white/5 px-3 py-2 text-sm text-[#DEDBC8] hover:bg-white/10 transition-colors min-w-[160px]"
      :disabled="agentStore.isLoading"
      @click="toggle"
    >
      <!-- Loading -->
      <Loader2 v-if="agentStore.isLoading" :size="16" class="animate-spin text-[#DEDBC8]/40" />

      <!-- Error -->
      <AlertCircle v-else-if="agentStore.backendDown" :size="16" class="text-red-400 flex-shrink-0" />

      <!-- Selected agent name -->
      <span class="flex-1 text-left truncate">
        <template v-if="agentStore.isLoading">加载中...</template>
        <template v-else-if="agentStore.backendDown">后端异常</template>
        <template v-else-if="agentStore.selectedAgent">{{ agentStore.selectedAgent.agentName }}</template>
        <template v-else-if="agentStore.hasAgents">选择智能体</template>
        <template v-else>暂无智能体</template>
      </span>

      <ChevronDown :size="16" class="text-[#DEDBC8]/30 flex-shrink-0 transition-transform" :class="{ 'rotate-180': open }" />
    </button>

    <!-- Dropdown -->
    <Transition name="dropdown">
      <div
        v-if="open && agentStore.hasAgents"
        class="absolute top-full left-0 mt-1 w-full rounded-lg border border-white/10 bg-[#0f0f0f] shadow-xl py-1 z-10 max-h-60 overflow-y-auto"
      >
        <button
          v-for="agent in agentStore.agents"
          :key="agent.agentId"
          class="flex w-full items-center px-3 py-2 text-sm text-left transition-colors"
          :class="agent.agentId === agentStore.selectedAgentId
            ? 'bg-white/10 text-[#DEDBC8]'
            : 'text-[#DEDBC8]/60 hover:bg-white/5 hover:text-[#DEDBC8]'"
          @click="select(agent.agentId)"
        >
          <span class="truncate">{{ agent.agentName }}</span>
        </button>
      </div>
    </Transition>

    <!-- Error tooltip -->
    <div
      v-if="agentStore.backendDown && agentStore.backendError"
      class="absolute top-full left-0 mt-1 w-full rounded-lg border border-red-500/20 bg-red-500/5 px-3 py-2 text-xs text-red-300/80"
    >
      {{ agentStore.backendError }}
    </div>
  </div>
</template>

<style scoped>
.dropdown-enter-active,
.dropdown-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
