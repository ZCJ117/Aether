<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useAgentStore } from '@/stores/agent'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useSessionStore } from '@/stores/session'

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
  <div class="chat-view">
    <!-- Session Sidebar (Left) -->
    <aside class="session-sidebar">
      <div class="sidebar-header">
        <h3>会话</h3>
      </div>
      <div class="session-list">
        <div
          v-for="session in sessionStore.filteredSessions(agentStore.selectedAgentId)"
          :key="session.sessionId"
          :class="['session-item', { active: session.sessionId === sessionStore.currentSessionId }]"
          @click="sessionStore.switchSession(session.sessionId)"
        >
          <span class="session-title">{{ session.title }}</span>
          <span class="session-date">{{ new Date(session.createdAt).toLocaleDateString() }}</span>
        </div>
        <div v-if="!agentStore.selectedAgentId" class="sidebar-empty">
          请先选择一个智能体
        </div>
      </div>
    </aside>

    <!-- Chat Area (Right) -->
    <main class="chat-main">
      <!-- Empty State -->
      <div v-if="!agentStore.selectedAgent" class="chat-empty">
        <div class="empty-icon">🤖</div>
        <h2>选择一个智能体开始对话</h2>
        <p>从侧边栏选择一个智能体，开始体验 Aether 的强大能力</p>
      </div>

      <!-- Chat Interface -->
      <template v-else>
        <div class="chat-messages">
          <div v-if="chatStore.isEmpty" class="messages-empty">
            <div class="empty-icon">💬</div>
            <p>发送消息开始对话</p>
          </div>
          <div
            v-for="msg in chatStore.messages"
            :key="msg.id"
            :class="['message', msg.side]"
          >
            <div class="message-text">{{ msg.text }}</div>
            <div v-if="msg.streaming" class="streaming-indicator">...</div>
          </div>
        </div>

        <!-- Streaming Status -->
        <div v-if="chatStore.statusText" :class="['streaming-status', chatStore.statusType]">
          {{ chatStore.statusText }}
        </div>

        <!-- Token Budget Indicator -->
        <div v-if="chatStore.tokenBudget.budgetTotal > 0" class="token-budget">
          <div class="budget-bar">
            <div
              class="budget-fill"
              :style="{ width: chatStore.tokenBudget.budgetPercent + '%' }"
            ></div>
          </div>
          <span class="budget-text">
            {{ chatStore.tokenBudget.budgetUsed.toLocaleString() }} /
            {{ chatStore.tokenBudget.budgetTotal.toLocaleString() }}
          </span>
        </div>

        <!-- Chat Input -->
        <div class="chat-input-area">
          <input
            v-model="inputText"
            type="text"
            placeholder="输入消息..."
            :disabled="chatStore.isSending"
            @keydown.enter="handleSend"
          />
          <button
            :disabled="!inputText.trim() || chatStore.isSending"
            @click="handleSend"
          >
            {{ chatStore.isSending ? '发送中...' : '发送' }}
          </button>
        </div>
      </template>
    </main>

    <!-- Permission Dialog -->
    <div v-if="showPermissionDialog" class="permission-overlay">
      <div class="permission-dialog">
        <h3>工具调用确认</h3>
        <p>智能体请求调用以下工具：</p>
        <ul>
          <li v-for="tc in chatStore.permissionEvent?.pendingToolCalls" :key="tc.toolCallId">
            <strong>{{ tc.toolName }}</strong>
            <pre>{{ JSON.stringify(tc.input, null, 2) }}</pre>
          </li>
        </ul>
        <div class="permission-actions">
          <button class="btn-deny" @click="handlePermissionResponse(false)">拒绝所有</button>
          <button class="btn-approve" @click="handlePermissionResponse(true)">全部允许</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  height: calc(100vh - var(--header-height, 56px));
}

/* Session Sidebar */
.session-sidebar {
  width: 260px;
  min-width: 260px;
  background: var(--bg-secondary, #1a1a2e);
  border-right: 1px solid var(--border-color, #2a2a4a);
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: 1rem;
  border-bottom: 1px solid var(--border-color, #2a2a4a);
}

.sidebar-header h3 {
  margin: 0;
  font-size: 0.9375rem;
  color: var(--text-primary, #eee);
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0.5rem;
}

.session-item {
  padding: 0.625rem 0.75rem;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  transition: background 0.15s;
}

.session-item:hover {
  background: rgba(99, 102, 241, 0.1);
}

.session-item.active {
  background: rgba(99, 102, 241, 0.2);
}

.session-title {
  font-size: 0.8125rem;
  color: var(--text-primary, #eee);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-date {
  font-size: 0.6875rem;
  color: var(--text-secondary, #666);
  flex-shrink: 0;
  margin-left: 0.5rem;
}

.sidebar-empty {
  padding: 1rem;
  text-align: center;
  color: var(--text-secondary, #888);
  font-size: 0.8125rem;
}

/* Chat Main */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: var(--bg-primary, #0f0f1a);
}

.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  color: var(--text-secondary, #888);
}

.empty-icon {
  font-size: 3rem;
  margin-bottom: 0.5rem;
}

.chat-empty h2 {
  font-size: 1.25rem;
  color: var(--text-primary, #eee);
  margin: 0;
}

.chat-empty p {
  margin: 0;
  font-size: 0.875rem;
}

/* Messages */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 1rem;
}

.messages-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-secondary, #888);
}

.message {
  margin-bottom: 0.75rem;
  padding: 0.75rem 1rem;
  border-radius: 10px;
  max-width: 80%;
}

.message.user {
  margin-left: auto;
  background: var(--accent-color, #6366f1);
  color: #fff;
}

.message.agent {
  background: var(--bg-secondary, #1a1a2e);
  color: var(--text-primary, #eee);
  border: 1px solid var(--border-color, #2a2a4a);
}

.streaming-indicator {
  font-size: 0.75rem;
  color: var(--text-secondary, #888);
  margin-top: 0.25rem;
  animation: pulse 1s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

/* Streaming Status */
.streaming-status {
  padding: 0.5rem 1rem;
  font-size: 0.8125rem;
}

.streaming-status.info { color: var(--accent-color, #6366f1); }
.streaming-status.error { color: #ef4444; }
.streaming-status.success { color: #22c55e; }
.streaming-status.warning { color: #f59e0b; }

/* Token Budget */
.token-budget {
  padding: 0.5rem 1rem;
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.budget-bar {
  flex: 1;
  height: 4px;
  background: var(--border-color, #2a2a4a);
  border-radius: 2px;
  overflow: hidden;
}

.budget-fill {
  height: 100%;
  background: var(--accent-color, #6366f1);
  border-radius: 2px;
  transition: width 0.3s;
}

.budget-text {
  font-size: 0.6875rem;
  color: var(--text-secondary, #888);
  white-space: nowrap;
}

/* Chat Input */
.chat-input-area {
  display: flex;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  border-top: 1px solid var(--border-color, #2a2a4a);
  background: var(--bg-secondary, #1a1a2e);
}

.chat-input-area input {
  flex: 1;
  padding: 0.625rem 0.75rem;
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 8px;
  background: var(--bg-primary, #0f0f1a);
  color: var(--text-primary, #eee);
  font-size: 0.875rem;
  outline: none;
}

.chat-input-area input:focus {
  border-color: var(--accent-color, #6366f1);
}

.chat-input-area input:disabled {
  opacity: 0.6;
}

.chat-input-area button {
  padding: 0.625rem 1.25rem;
  border: none;
  border-radius: 8px;
  background: var(--accent-color, #6366f1);
  color: #fff;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s;
}

.chat-input-area button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Permission Dialog */
.permission-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.permission-dialog {
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 12px;
  padding: 1.5rem;
  max-width: 480px;
  width: 90%;
}

.permission-dialog h3 {
  margin: 0 0 0.75rem;
  color: var(--text-primary, #eee);
}

.permission-dialog p {
  margin: 0 0 0.75rem;
  color: var(--text-secondary, #aaa);
  font-size: 0.875rem;
}

.permission-dialog ul {
  list-style: none;
  padding: 0;
  margin: 0 0 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.permission-dialog li {
  padding: 0.625rem;
  background: var(--bg-primary, #0f0f1a);
  border-radius: 8px;
  font-size: 0.8125rem;
  color: var(--text-primary, #eee);
}

.permission-dialog li strong {
  color: var(--accent-color, #6366f1);
}

.permission-dialog li pre {
  margin: 0.375rem 0 0;
  font-size: 0.6875rem;
  color: var(--text-secondary, #888);
  white-space: pre-wrap;
  word-break: break-all;
}

.permission-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
}

.permission-actions button {
  padding: 0.5rem 1.25rem;
  border: none;
  border-radius: 8px;
  font-size: 0.875rem;
  cursor: pointer;
  transition: opacity 0.2s;
}

.btn-deny {
  background: rgba(239, 68, 68, 0.15);
  color: #ef4444;
  border: 1px solid rgba(239, 68, 68, 0.3);
}

.btn-approve {
  background: var(--accent-color, #6366f1);
  color: #fff;
}

.permission-actions button:hover {
  opacity: 0.85;
}
</style>
