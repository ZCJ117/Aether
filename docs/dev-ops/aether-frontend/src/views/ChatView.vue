<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppLayout from '@/components/common/AppLayout.vue'
import ChatLayout from '@/components/chat/ChatLayout.vue'
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import StreamingStatus from '@/components/chat/StreamingStatus.vue'
import TokenBudgetIndicator from '@/components/chat/TokenBudgetIndicator.vue'
import PermissionDialog from '@/components/chat/PermissionDialog.vue'
import { useAgentStore } from '@/stores/agent'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useSessionStore } from '@/stores/session'
import { useToast } from '@/composables/useToast'
import { Bot } from 'lucide-vue-next'
import type { PendingToolCall } from '@/components/chat/PermissionDialog.vue'

const router = useRouter()
const agentStore = useAgentStore()
const authStore = useAuthStore()
const chatStore = useChatStore()
const sessionStore = useSessionStore()
const toast = useToast()

const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)

// Permission dialog state — driven by chatStore.permissionEvent
const showPermissionDialog = ref(false)

// Computed
const canSend = computed(() => {
  return agentStore.selectedAgentId && authStore.userId && !chatStore.isSending
})

const headerTitle = computed(() => {
  if (agentStore.selectedAgent) {
    return agentStore.selectedAgent.agentName
  }
  return '选择智能体开始对话'
})

// Watch permissionEvent from store → open dialog
watch(
  () => chatStore.permissionEvent,
  (evt) => {
    if (evt && evt.pendingToolCalls.length > 0) {
      showPermissionDialog.value = true
    }
  }
)

// Methods
async function handleSend(message: string) {
  if (!agentStore.selectedAgentId) {
    toast.warning('请先选择一个智能体')
    return
  }
  if (!authStore.userId) {
    toast.error('未登录，请重新登录')
    router.push('/login')
    return
  }

  // Track whether a session already existed before sending
  const hadSession = !!chatStore.sessionId

  try {
    await chatStore.sendMessage(message, agentStore.selectedAgentId, authStore.userId)

    // After first message creates a session, add it to session store
    if (!hadSession && chatStore.sessionId && agentStore.selectedAgent) {
      const title = message.slice(0, 30) + (message.length > 30 ? '…' : '')
      sessionStore.addSessionToAgent({
        sessionId: chatStore.sessionId,
        agentId: agentStore.selectedAgentId,
        agentName: agentStore.selectedAgent.agentName,
        title,
        createdAt: Date.now()
      })
    }
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '发送失败')
  }
}

function handleCancel() {
  chatStore.cancelStream()
}

function handleNewChat() {
  chatStore.clearMessages()
}

function handlePermissionConfirmed() {
  showPermissionDialog.value = false
  chatStore.permissionEvent = null
  toast.success('权限已确认，Agent 继续执行')
}

function handlePermissionClose() {
  showPermissionDialog.value = false
}

// Initialize
onMounted(async () => {
  // Load agents if not loaded
  if (!agentStore.hasAgents && !agentStore.isLoading) {
    try {
      await agentStore.loadAgents()
    } catch {
      toast.error('加载智能体列表失败')
    }
  }

  // Check login status
  if (!authStore.isLoggedIn) {
    authStore.checkLogin()
  }
})

// Focus input when agent is selected
watch(() => agentStore.selectedAgentId, () => {
  chatInputRef.value?.focus()
})
</script>

<template>
  <AppLayout>
    <ChatLayout @new-chat="handleNewChat">
      <!-- Header slot: show selected agent name -->
      <template #header>
        <div class="flex items-center gap-2">
          <Bot v-if="agentStore.selectedAgent" :size="14" class="text-primary/60" />
          <span class="text-sm text-primary/60" data-testid="chat-header-title">{{ headerTitle }}</span>
        </div>
      </template>

      <!-- Default slot: main chat area -->
      <div class="flex flex-col h-full">
        <!-- No agent selected state -->
        <div v-if="!agentStore.selectedAgentId" class="flex-1 flex items-center justify-center" data-testid="chat-no-agent-hint">
          <div class="text-center">
            <Bot :size="40" class="text-primary/20 mx-auto mb-4" />
            <p class="text-primary/40 text-sm">请从侧边栏选择一个智能体开始对话</p>
          </div>
        </div>

        <!-- Chat content -->
        <template v-else>
          <!-- Token budget indicator -->
          <TokenBudgetIndicator
            :budget-used="chatStore.tokenBudget.budgetUsed"
            :budget-total="chatStore.tokenBudget.budgetTotal"
            :budget-percent="chatStore.tokenBudget.budgetPercent"
          />

          <!-- Streaming status -->
          <StreamingStatus
            :status-text="chatStore.statusText"
            :status-type="chatStore.statusType"
            :is-sending="chatStore.isSending"
          />

          <!-- Message list -->
          <MessageList :messages="chatStore.messages" />

          <!-- Input area -->
          <ChatInput
            ref="chatInputRef"
            :disabled="chatStore.isSending"
            @send="handleSend"
            @cancel="handleCancel"
          />
        </template>
      </div>
    </ChatLayout>

    <!-- Permission dialog (teleported inside PermissionDialog) -->
    <PermissionDialog
      :visible="showPermissionDialog"
      :reply-id="chatStore.permissionEvent?.replyId ?? ''"
      :pending-tool-calls="(chatStore.permissionEvent?.pendingToolCalls as PendingToolCall[] | undefined)"
      :agent-id="agentStore.selectedAgentId || ''"
      :user-id="authStore.userId"
      :session-id="chatStore.sessionId || ''"
      @close="handlePermissionClose"
      @confirmed="handlePermissionConfirmed"
    />
  </AppLayout>
</template>
