<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import TopBar from '@/components/layout/TopBar.vue'
import AgentSelector from '@/components/chat/AgentSelector.vue'
import ChatArea from '@/components/chat/ChatArea.vue'
import Composer from '@/components/chat/Composer.vue'
import StatusBar from '@/components/chat/StatusBar.vue'
import { useAuthStore } from '@/stores/auth'
import { useAgentStore } from '@/stores/agent'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const auth = useAuthStore()
const agentStore = useAgentStore()
const chatStore = useChatStore()

onMounted(async () => {
  try {
    await agentStore.loadAgents()
    chatStore.setStatus('已加载智能体列表。')
  } catch (err) {
    chatStore.setStatus(`智能体列表加载失败：${err?.message || '加载失败'}`, 'error')
  }
})

function onAgentSelect(id) {
  agentStore.selectAgent(id)
}

function onSend(message) {
  if (!agentStore.selectedAgentId) {
    chatStore.setStatus('请先选择智能体。', 'error')
    return
  }
  chatStore.sendMessage(message, agentStore.selectedAgentId, auth.userId)
}

function onLogout() {
  auth.logout()
  router.replace({ name: 'Login' })
}
</script>

<template>
  <div class="chat-view">
    <TopBar
      :username="auth.userId"
      :login-meta="auth.loginTs ? '登录于 ' + auth.formattedLoginTime : '已登录'"
      @logout="onLogout"
    >
      <template #selector>
        <AgentSelector
          :agents="agentStore.agents"
          :selected-id="agentStore.selectedAgentId"
          :loading="agentStore.isLoading"
          :error="agentStore.backendError"
          @select="onAgentSelect"
        />
      </template>
    </TopBar>

    <section class="main">
      <ChatArea
        :messages="chatStore.messages"
        :is-empty="chatStore.isEmpty"
      />
      <StatusBar :text="chatStore.statusText" :type="chatStore.statusType" />
    </section>

    <Composer :disabled="chatStore.isSending" @send="onSend" />
  </div>
</template>

<style scoped>
.chat-view {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.main {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
  padding: 0 24px;
}

@media (max-width: 767px) {
  .main {
    padding: 0 12px;
  }
}
</style>
