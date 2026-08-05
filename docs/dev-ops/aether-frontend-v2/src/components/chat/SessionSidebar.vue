<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { Search } from 'lucide-vue-next'
import { useAgentStore } from '@/stores/agent'
import { useSessionStore } from '@/stores/session'
import { useAuthStore } from '@/stores/auth'
import AgentAccordionItem from './AgentAccordionItem.vue'

const agentStore = useAgentStore()
const sessionStore = useSessionStore()
const authStore = useAuthStore()

const emit = defineEmits<{
  'new-chat': [agentId: string]
  'select-session': [sessionId: string, agentId: string]
}>()

// Sessions are loaded by ChatView.vue's onMounted — no need to duplicate here.
// Only reload when agents list changes (e.g. after a config refresh).
watch(
  () => agentStore.agents.length,
  async (len) => {
    if (authStore.userId && len > 0) {
      for (const agent of agentStore.agents) {
        await sessionStore.loadSessions(agent.agentId, authStore.userId)
      }
    }
  }
)

function handleToggle(agentId: string) {
  agentStore.toggleAgent(agentId)
}

function handleCreateSession(agentId: string) {
  emit('new-chat', agentId)
}

function handleSelectSession(sessionId: string, agentId: string) {
  sessionStore.switchSession(sessionId)
  emit('select-session', sessionId, agentId)
}

function handleDeleteSession(sessionId: string, agentId: string) {
  sessionStore.deleteSession(sessionId, agentId)
}
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- Search input -->
    <div class="p-3 border-b border-white/5">
      <div class="relative">
        <Search :size="14" class="absolute left-2.5 top-1/2 -translate-y-1/2 text-[#DEDBC8]/30" />
        <input
          v-model="sessionStore.searchQuery"
          type="text"
          placeholder="搜索会话..."
          class="w-full rounded-md bg-white/5 pl-8 pr-3 py-1.5 text-sm text-[#DEDBC8] placeholder-[#DEDBC8]/25 border border-white/5 focus:border-white/10 focus:outline-none transition-colors"
        />
      </div>
    </div>

    <!-- Agent & session list -->
    <div class="flex-1 overflow-y-auto">
      <!-- Loading -->
      <div v-if="agentStore.isLoading" class="flex items-center justify-center py-8">
        <div class="h-5 w-5 animate-spin rounded-full border-2 border-[#DEDBC8]/20 border-t-[#DEDBC8]/60" />
      </div>

      <!-- Error -->
      <div v-else-if="agentStore.backendDown" class="px-4 py-6 text-center text-sm text-[#DEDBC8]/40">
        <p>后端服务不可用</p>
        <p class="mt-1 text-xs">{{ agentStore.backendError }}</p>
      </div>

      <!-- Empty -->
      <div v-else-if="!agentStore.hasAgents" class="px-4 py-6 text-center text-sm text-[#DEDBC8]/30">
        暂无可用智能体
      </div>

      <!-- Agent list -->
      <template v-else>
        <AgentAccordionItem
          v-for="agent in agentStore.agents"
          :key="agent.agentId"
          :agent-id="agent.agentId"
          :agent-name="agent.agentName"
          :is-expanded="agentStore.expandedAgentId === agent.agentId"
          :sessions="sessionStore.filteredSessions(agent.agentId)"
          :current-session-id="sessionStore.currentSessionId"
          @toggle="handleToggle(agent.agentId)"
          @create-session="handleCreateSession(agent.agentId)"
          @select-session="(id: string) => handleSelectSession(id, agent.agentId)"
          @delete-session="(id: string) => handleDeleteSession(id, agent.agentId)"
        />
      </template>
    </div>
  </div>
</template>
