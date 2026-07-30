<script setup lang="ts">
import { watch, onMounted } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useChatStore } from '@/stores/chat'
import { useAgentStore } from '@/stores/agent'
import { useAuthStore } from '@/stores/auth'
import { createSession } from '@/api/session'
import { RefreshCw } from 'lucide-vue-next'
import AgentAccordionItem from './AgentAccordionItem.vue'

const sessionStore = useSessionStore()
const chatStore = useChatStore()
const agentStore = useAgentStore()
const authStore = useAuthStore()

onMounted(() => {
  if (!agentStore.hasAgents && !agentStore.isLoading) {
    agentStore.loadAgents()
  }
})

// When selected agent changes, load its session list from backend
watch(() => agentStore.selectedAgentId, async (newId) => {
  if (newId && authStore.userId) {
    await sessionStore.loadSessions(newId, authStore.userId)
  }
})

function handleToggleAgent(agentId: string) {
  agentStore.toggleAgent(agentId)
}

async function handleCreateSession(agentId: string) {
  if (!authStore.userId) return
  try {
    const res = await createSession(agentId, authStore.userId)
    const agent = agentStore.agents.find(a => a.agentId === agentId)
    sessionStore.addSessionToAgent({
      sessionId: res.sessionId,
      agentId,
      agentName: agent?.agentName ?? '',
      title: '新对话',
      createdAt: Date.now()
    })
    agentStore.expandAgent(agentId)
    agentStore.selectAgent(agentId)
    chatStore.clearMessages()
  } catch {
    // error toast handled by axios interceptor
  }
}

function handleSelectSession(sessionId: string) {
  sessionStore.switchSession(sessionId)
  chatStore.clearMessages()
}

function handleDeleteSession(sessionId: string, agentId: string) {
  sessionStore.deleteSession(sessionId, agentId)
  if (sessionStore.currentSessionId === null) {
    chatStore.clearMessages()
  }
}
</script>

<template>
  <div class="h-full flex flex-col bg-[#0a0a0a]">
    <!-- 标题行 -->
    <div class="px-3 pt-3 pb-2 flex items-center justify-between">
      <span class="text-xs text-primary/50 uppercase tracking-wider">智能体</span>
      <button
        v-if="agentStore.hasAgents"
        @click="agentStore.loadAgents()"
        class="text-primary/40 hover:text-primary-100 transition-colors p-0.5"
        title="刷新智能体列表"
      >
        <RefreshCw
          :size="13"
          :class="agentStore.isLoading ? 'animate-spin' : ''"
        />
      </button>
    </div>

    <!-- Agent 列表区域 -->
    <div class="flex-1 overflow-y-auto px-3 pb-3 space-y-1">
      <!-- 加载中 -->
      <div v-if="agentStore.isLoading" class="text-xs text-primary/40 py-4 text-center">
        加载中…
      </div>

      <!-- 后端不可用 -->
      <div v-else-if="agentStore.backendDown" class="py-4 text-center">
        <p class="text-xs text-primary/40 mb-2">{{ agentStore.backendError || '无法连接后端' }}</p>
        <button
          @click="agentStore.loadAgents()"
          class="flex items-center gap-1 text-xs text-primary/60 hover:text-primary-100 transition-colors mx-auto"
        >
          <RefreshCw :size="12" />
          <span>重试</span>
        </button>
      </div>

      <!-- Agent 折叠列表 -->
      <template v-else-if="agentStore.hasAgents">
        <AgentAccordionItem
          v-for="agent in agentStore.agents"
          :key="agent.agentId"
          :agent-id="agent.agentId"
          :agent-name="agent.agentName"
          :agent-desc="agent.agentDesc"
          :sessions="sessionStore.getSessionsForAgent(agent.agentId)"
          :is-expanded="agentStore.expandedAgentId === agent.agentId"
          :is-selected="agentStore.selectedAgentId === agent.agentId"
          :current-session-id="sessionStore.currentSessionId"
          @toggle="handleToggleAgent(agent.agentId)"
          @create-session="handleCreateSession(agent.agentId)"
          @select-session="handleSelectSession"
          @delete-session="(sessionId: string) => handleDeleteSession(sessionId, agent.agentId)"
        />
      </template>

      <!-- 无 Agent -->
      <div v-else class="text-xs text-primary/40 py-4 text-center">
        暂无可用智能体
      </div>
    </div>
  </div>
</template>
