<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useAgentStore } from '@/stores/agent'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useSessionStore } from '@/stores/session'
import { ChatLayout, SessionSidebar } from '@/components/chat'

const agentStore = useAgentStore()
const authStore = useAuthStore()
const chatStore = useChatStore()
const sessionStore = useSessionStore()

const inputText = ref('')
const showPermissionDialog = computed(() => chatStore.hasActivePermissionRequest)

onMounted(async () => {
  await agentStore.loadAgents()
  if (agentStore.selectedAgentId) {
    await sessionStore.loadSessions(agentStore.selectedAgentId, authStore.userId)
  }
})

watch(
  () => agentStore.selectedAgentId,
  (newId) => {
    if (newId) {
      sessionStore.loadSessions(newId, authStore.userId)
    }
  }
)

async function handleNewChat(agentId: string) {
  agentStore.selectAgent(agentId)  // 先切换智能体，否则发送消息仍走旧 Agent
  await sessionStore.newSession(agentId, authStore.userId)
  agentStore.expandAgent(agentId)
  await sessionStore.loadSessions(agentId, authStore.userId)
  chatStore.switchToSession(sessionStore.currentSessionId)
}

function handleSelectSession(sessionId: string, agentId: string) {
  agentStore.selectAgent(agentId)  // 点击会话时也需同步切换智能体
  sessionStore.switchSession(sessionId)
  chatStore.switchToSession(sessionId)
}

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || !agentStore.selectedAgentId || chatStore.isSending) return
  inputText.value = ''
  await chatStore.sendMessage(text, agentStore.selectedAgentId, authStore.userId)
}

function handlePermissionResponse(approved: boolean) {
  if (!chatStore.permissionEvent) return
  if (approved) {
    const results = chatStore.permissionEvent.pendingToolCalls.map((tc) => ({
      toolCallId: tc.toolCallId,
      approved: true,
    }))
    chatStore.confirmPermission(agentStore.selectedAgentId!, authStore.userId, '', results)
  } else {
    chatStore.denyAllPermissions(agentStore.selectedAgentId!, authStore.userId, '')
  }
}
</script>

<template>
  <ChatLayout>
    <template #sidebar>
      <SessionSidebar
        @new-chat="handleNewChat"
        @select-session="handleSelectSession"
      />
    </template>

    <template #header>
      <div class="flex items-center gap-3">
        <span class="text-sm font-medium text-[#DEDBC8]">
          {{ agentStore.selectedAgent?.agentName || 'Aether Chat' }}
        </span>
        <span v-if="agentStore.selectedAgent" class="text-xs text-[#DEDBC8]/40">
          {{ agentStore.selectedAgent.agentDesc }}
        </span>
      </div>
    </template>

    <!-- Chat Area -->
    <div class="flex-1 min-h-0 flex flex-col">
      <!-- Empty State -->
      <div v-if="!agentStore.selectedAgent" class="flex-1 flex flex-col items-center justify-center gap-2 text-[#DEDBC8]/50">
        <div class="text-5xl mb-2">🤖</div>
        <h2 class="text-xl text-[#DEDBC8]">选择一个智能体开始对话</h2>
        <p class="text-sm">从左侧栏展开一个智能体，点击 + 创建新对话</p>
      </div>

      <!-- Chat Interface -->
      <template v-else>
        <div class="flex-1 overflow-y-auto px-4 py-3">
          <div v-if="chatStore.isEmpty" class="flex flex-col items-center justify-center h-full gap-2 text-[#DEDBC8]/40">
            <div class="text-4xl mb-1">💬</div>
            <p class="text-sm">发送消息开始对话</p>
          </div>
          <div
            v-for="msg in chatStore.messages"
            :key="msg.id"
            :class="['mb-3 px-4 py-3 rounded-xl max-w-[80%]', msg.side === 'user' ? 'ml-auto bg-indigo-600 text-white' : 'bg-[#1a1a2e] text-[#DEDBC8] border border-white/5']"
          >
            <div class="text-sm whitespace-pre-wrap">{{ msg.text }}</div>
            <div v-if="msg.streaming" class="text-xs text-[#DEDBC8]/40 mt-1 animate-pulse">...</div>
          </div>
        </div>

        <!-- Streaming Status -->
        <div v-if="chatStore.statusText" :class="['px-4 py-1.5 text-xs', { 'text-indigo-400': chatStore.statusType === 'info', 'text-red-400': chatStore.statusType === 'error', 'text-emerald-400': chatStore.statusType === 'success', 'text-amber-400': chatStore.statusType === 'warning' }]">
          {{ chatStore.statusText }}
        </div>

        <!-- Token Budget -->
        <div v-if="chatStore.tokenBudget.budgetTotal > 0" class="px-4 py-1 flex items-center gap-3">
          <div class="flex-1 h-1 bg-white/5 rounded-full overflow-hidden">
            <div class="h-full bg-indigo-500 rounded-full transition-all duration-300" :style="{ width: chatStore.tokenBudget.budgetPercent + '%' }"></div>
          </div>
          <span class="text-[11px] text-[#DEDBC8]/40 whitespace-nowrap">
            {{ chatStore.tokenBudget.budgetUsed.toLocaleString() }} / {{ chatStore.tokenBudget.budgetTotal.toLocaleString() }}
          </span>
        </div>

        <!-- Chat Input -->
        <div class="flex-shrink-0 border-t border-white/5 bg-[#0f0f0f] px-4 py-3">
          <div class="flex gap-2">
            <input
              v-model="inputText"
              type="text"
              placeholder="输入消息..."
              :disabled="chatStore.isSending"
              class="flex-1 rounded-lg bg-white/5 px-4 py-2.5 text-sm text-[#DEDBC8] placeholder-[#DEDBC8]/25 border border-white/5 focus:border-indigo-500/50 focus:outline-none transition-colors disabled:opacity-50"
              @keydown.enter="handleSend"
            />
            <button
              :disabled="!inputText.trim() || chatStore.isSending"
              class="rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-indigo-500 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              @click="handleSend"
            >
              {{ chatStore.isSending ? '发送中...' : '发送' }}
            </button>
          </div>
        </div>
      </template>
    </div>

    <!-- Permission Dialog -->
    <div v-if="showPermissionDialog" class="fixed inset-0 z-50 bg-black/60 flex items-center justify-center">
      <div class="bg-[#1a1a2e] border border-white/10 rounded-xl p-6 max-w-lg w-[90%]">
        <h3 class="text-lg font-medium text-[#DEDBC8] mb-2">工具调用确认</h3>
        <p class="text-sm text-[#DEDBC8]/60 mb-3">智能体请求调用以下工具：</p>
        <ul class="space-y-2 mb-4">
          <li v-for="tc in chatStore.permissionEvent?.pendingToolCalls" :key="tc.toolCallId" class="bg-[#0f0f0f] rounded-lg p-3 text-sm">
            <strong class="text-indigo-400">{{ tc.toolName }}</strong>
            <pre class="mt-1 text-xs text-[#DEDBC8]/50 whitespace-pre-wrap">{{ JSON.stringify(tc.input, null, 2) }}</pre>
          </li>
        </ul>
        <div class="flex gap-3 justify-end">
          <button class="px-4 py-2 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 text-sm hover:bg-red-500/20 transition-colors" @click="handlePermissionResponse(false)">拒绝所有</button>
          <button class="px-4 py-2 rounded-lg bg-indigo-600 text-white text-sm hover:bg-indigo-500 transition-colors" @click="handlePermissionResponse(true)">全部允许</button>
        </div>
      </div>
    </div>
  </ChatLayout>
</template>

<style scoped>
/* 背景由 ChatLayout 统一控制，无需额外样式 */
</style>
