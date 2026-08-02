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

const currentSessionTitle = computed(() => {
  const sid = sessionStore.currentSessionId
  if (!sid) return '新对话'
  for (const sessions of Object.values(sessionStore.sessionsByAgent)) {
    const found = sessions.find((s) => s.sessionId === sid)
    if (found) return found.title
  }
  return '新对话'
})

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

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || !agentStore.selectedAgentId || chatStore.isSending) return

  // 如果还没有会话，先创建一个
  if (!sessionStore.currentSessionId) {
    await sessionStore.newSession(agentStore.selectedAgentId, authStore.userId)
    chatStore.switchToSession(sessionStore.currentSessionId)
  }

  inputText.value = ''
  await chatStore.sendMessage(text, agentStore.selectedAgentId, authStore.userId)
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <ChatLayout>
    <template #sidebar>
      <SessionSidebar
        @new-chat="async (agentId: string) => {
          await sessionStore.newSession(agentId, authStore.userId)
          agentStore.selectAgent(agentId)
          chatStore.switchToSession(sessionStore.currentSessionId)
        }"
        @select-session="(sid: string, _agentId: string) => chatStore.switchToSession(sid)"
      />
    </template>

    <template #header>
      <div class="chat-topbar">
        <span class="chat-session-name">
          {{ currentSessionTitle }}
        </span>
        <span v-if="chatStore.turnCount > 0" class="chat-turn-badge">
          {{ chatStore.turnCount }} 轮
        </span>
      </div>
    </template>

    <template #default>
      <div v-if="!agentStore.selectedAgentId" class="empty-state">
        请先在左侧选择一个智能体
      </div>
      <div v-else-if="chatStore.messages.length === 0" class="empty-state">
        输入消息开始与 <strong>{{ agentStore.selectedAgent?.agentName || agentStore.selectedAgentId }}</strong> 对话
      </div>

      <div v-else class="messages">
        <div
          v-for="msg in chatStore.messages"
          :key="msg.id"
          :class="['msg-row', msg.side === 'user' ? 'msg-user' : 'msg-agent']"
        >
          <div v-if="msg.side === 'agent'" class="msg-avatar">
            {{ (agentStore.selectedAgent?.agentName || 'A').charAt(0) }}
          </div>
          <div :class="['msg-bubble', msg.side === 'user' ? 'bubble-user' : 'bubble-agent']">
            {{ msg.text }}
          </div>
        </div>

        <div v-if="chatStore.isSending" class="streaming-hint">
          <span class="streaming-dot" />
          正在生成...
        </div>
      </div>
    </template>

    <template #footer>
      <div class="input-bar">
        <input
          v-model="inputText"
          :placeholder="agentStore.selectedAgentId
            ? `给 ${agentStore.selectedAgent?.agentName || 'Agent'} 发送消息...`
            : '请先选择智能体'"
          :disabled="!agentStore.selectedAgentId || chatStore.isSending"
          class="msg-input"
          @keydown="handleKeydown"
        />
        <button
          class="send-btn"
          :disabled="!inputText.trim() || chatStore.isSending"
          @click="handleSend"
        >
          ↑
        </button>
      </div>
    </template>
  </ChatLayout>
</template>

<style scoped>
.chat-topbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-session-name {
  font-size: 13px;
  font-weight: 600;
  color: #F5F5F7;
  flex: 1;
}

.chat-turn-badge {
  font-size: 10px;
  color: #98989D;
  background: rgba(44, 44, 46, 0.5);
  border-radius: 5px;
  padding: 2px 8px;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 14px;
  color: #636366;
}

.messages {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
}

.msg-row {
  display: flex;
  gap: 8px;
}

.msg-user {
  justify-content: flex-end;
}

.msg-agent {
  justify-content: flex-start;
}

.msg-avatar {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  background: rgba(90, 200, 250, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  color: #5AC8FA;
}

.msg-bubble {
  padding: 9px 13px;
  max-width: 68%;
  font-size: 13px;
  line-height: 1.5;
}

.bubble-user {
  background: #5AC8FA;
  color: #000;
  border-radius: 14px 14px 4px 14px;
}

.bubble-agent {
  background: rgba(44, 44, 46, 0.6);
  color: #F5F5F7;
  border-radius: 14px 14px 14px 4px;
}

.streaming-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  font-size: 12px;
  color: #98989D;
}

.streaming-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #5AC8FA;
  animation: pulseDot 1.4s infinite ease-in-out;
}

@keyframes pulseDot {
  0%, 80%, 100% { opacity: 0.2; transform: scale(0.8); }
  40% { opacity: 1; transform: scale(1); }
}

.input-bar {
  display: flex;
  gap: 8px;
  padding: 10px 14px;
  border-top: 0.5px solid rgba(255, 255, 255, 0.06);
}

.msg-input {
  flex: 1;
  padding: 10px 12px;
  border-radius: 10px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(44, 44, 46, 0.5);
  color: #F5F5F7;
  font-size: 13px;
  outline: none;
  font-family: inherit;
}

.msg-input:focus {
  border-color: #5AC8FA;
}

.send-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: none;
  background: #5AC8FA;
  color: #000;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.15s;
  flex-shrink: 0;
}

.send-btn:hover {
  opacity: 0.88;
}

.send-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
</style>
