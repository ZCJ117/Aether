<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useAgentStore } from '@/stores/agent'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useSessionStore } from '@/stores/session'
import { ChatLayout, SessionSidebar } from '@/components/chat'
import FilePickerModal from '@/components/chat/FilePickerModal.vue'
import MarkdownRender from 'markstream-vue'
import 'markstream-vue/index.css'
import { Paperclip, X } from 'lucide-vue-next'

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
  if (!agentStore.selectedAgentId && agentStore.agents.length > 0) {
    agentStore.selectAgent(agentStore.agents[0].agentId)
  }
  if (authStore.userId) {
    for (const agent of agentStore.agents) {
      await sessionStore.loadSessions(agent.agentId, authStore.userId)
    }
  }
  for (const agent of agentStore.agents) {
    const sessions = sessionStore.filteredSessions(agent.agentId)
    if (sessions.length > 0) {
      agentStore.selectAgent(agent.agentId)
      const latest = sessions[0]
      sessionStore.switchSession(latest.sessionId)
      chatStore.switchToSession(latest.sessionId)
      break
    }
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

// Reset attached files only on deliberate session switch by user (not on first-message auto-create)
watch(
  () => sessionStore.currentSessionId,
  (newId, oldId) => {
    // Only clear if switching FROM an existing session TO a different one
    if (oldId && newId && oldId !== newId) {
      attachedFiles.value = []
      filesSentToAgent.value = false
    }
  }
)

// ── Progressive streaming hint ──────────────────────────────
const streamingStartTime = ref(0)
const streamingElapsed = ref(0)
let streamingTimer: ReturnType<typeof setInterval> | null = null

watch(
  () => chatStore.isSending,
  (sending) => {
    if (sending) {
      streamingStartTime.value = Date.now()
      streamingElapsed.value = 0
      streamingTimer = setInterval(() => {
        streamingElapsed.value = Math.floor((Date.now() - streamingStartTime.value) / 1000)
      }, 1000)
    } else {
      if (streamingTimer) {
        clearInterval(streamingTimer)
        streamingTimer = null
      }
      streamingElapsed.value = 0
    }
  }
)

onUnmounted(() => {
  if (streamingTimer) clearInterval(streamingTimer)
})

const streamingHint = computed(() => {
  const t = streamingElapsed.value
  if (t < 15) return '正在生成...'
  if (t < 30) return '模型处理中，请稍候...'
  if (t < 60) return '模型响应较慢，仍在处理...'
  return `等待模型响应中... (${Math.floor(t / 60)}分${t % 60}秒，可停止重试)`
})

// 显示实时状态（工具调用/错误），避免用户只看到通用的"正在生成中"
const statusText = computed(() => chatStore.statusText?.trim() || '')
const hasStatus = computed(() => statusText.value.length > 0)

const showFilePicker = ref(false)
const attachedFiles = ref<{ name: string; path: string; size: number }[]>([])
// Track whether file paths have been sent to the agent in this conversation
const filesSentToAgent = ref(false)

function onFilesUploaded(files: { name: string; path: string; size: number }[]) {
  attachedFiles.value.push(...files)
  filesSentToAgent.value = false
}

function removeFile(idx: number) {
  attachedFiles.value.splice(idx, 1)
  if (attachedFiles.value.length === 0) {
    filesSentToAgent.value = false
  }
}

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || !agentStore.selectedAgentId || chatStore.isSending) return

  // Track whether this message will create a new session (first message)
  const isNewSession = !sessionStore.currentSessionId

  if (!sessionStore.currentSessionId) {
    await sessionStore.newSession(agentStore.selectedAgentId, authStore.userId)
    chatStore.switchToSession(sessionStore.currentSessionId)
  }

  inputText.value = ''

  // Include file paths only on first message (agent remembers context)
  let fullMessage = text
  if (attachedFiles.value.length > 0 && !filesSentToAgent.value) {
    const paths = attachedFiles.value.map(f => f.path).join('\n')
    fullMessage = `[附加文件/目录:\n${paths}]\n\n${text}`
    filesSentToAgent.value = true
  }

  // Files stay visible in the input bar (do NOT clear attachedFiles)
  await chatStore.sendMessage(fullMessage, agentStore.selectedAgentId, authStore.userId)
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
            <MarkdownRender
              v-if="msg.side === 'agent' && msg.text"
              mode="chat"
              :content="msg.text"
              :final="!msg.streaming"
            />
            <span v-else>{{ msg.text }}</span>
          </div>
        </div>

        <!-- 实时状态：工具调用 / 错误 / 轮次（替代泛化的"正在生成中"） -->
        <div v-if="hasStatus || chatStore.isSending" class="streaming-hint"
             :class="chatStore.statusType === 'error' ? 'status-error'
                    : chatStore.statusType === 'warning' ? 'status-warning'
                    : chatStore.statusType === 'success' ? 'status-success' : ''">
          <span v-if="chatStore.isSending" class="streaming-dot" />
          <span>{{ hasStatus ? statusText : '正在生成...' }}</span>
          <span v-if="chatStore.isSending && streamingElapsed > 0" class="streaming-elapsed">
            · {{ streamingHint }}
          </span>
        </div>
      </div>
    </template>

    <template #footer>
      <!-- Unified File Picker Modal -->
      <FilePickerModal
        v-if="showFilePicker"
        @close="showFilePicker = false"
        @uploaded="onFilesUploaded"
      />
      <!-- Attached files indicator -->
      <div v-if="attachedFiles.length > 0" class="attached-bar">
        <span class="attached-label">📎 已附加 {{ attachedFiles.length }} 项：</span>
        <div class="attached-chips">
          <span
            v-for="(f, i) in attachedFiles"
            :key="i"
            class="attached-chip"
          >
            {{ f.name }}
            <button @click="removeFile(i)" class="attached-remove" title="移除">
              <X :size="12" />
            </button>
          </span>
        </div>
      </div>

      <div class="input-bar">
        <button
          class="file-btn"
          :disabled="chatStore.isSending"
          @click="showFilePicker = true"
          title="选择文件或文件夹"
        >
          <Paperclip :size="16" />
        </button>
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

.chat-workspace-badge {
  font-size: 10px;
  color: #30D158;
  background: rgba(48, 209, 88, 0.1);
  border-radius: 5px;
  padding: 2px 8px;
  cursor: default;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  background: #ffffff;
  color: #1d1d1f;
  border-radius: 14px 14px 4px 14px;
}

.bubble-agent {
  background: rgba(44, 44, 46, 0.6);
  color: #F5F5F7;
  border-radius: 14px 14px 14px 4px;
  max-width: 85%;
}

.streaming-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  font-size: 12px;
  color: #98989D;
}

.streaming-hint.status-error {
  color: #FF453A;
}

.streaming-hint.status-warning {
  color: #FF9F0A;
}

.streaming-hint.status-success {
  color: #30D158;
}

.streaming-elapsed {
  opacity: 0.6;
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

.attached-bar {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 14px 0;
  flex-wrap: wrap;
}

.attached-label {
  font-size: 11px;
  color: #8E8E93;
  flex-shrink: 0;
  line-height: 24px;
}

.attached-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.attached-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  font-size: 11px;
  border-radius: 6px;
  background: rgba(90, 200, 250, 0.1);
  color: #5AC8FA;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attached-remove {
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #8E8E93;
  cursor: pointer;
  padding: 0;
  flex-shrink: 0;
}

.attached-remove:hover {
  color: #FF453A;
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

.file-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(44, 44, 46, 0.5);
  color: #98989D;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
  flex-shrink: 0;
}

.file-btn:hover {
  color: #5AC8FA;
  border-color: rgba(90, 200, 250, 0.3);
  background: rgba(90, 200, 250, 0.08);
}
</style>
