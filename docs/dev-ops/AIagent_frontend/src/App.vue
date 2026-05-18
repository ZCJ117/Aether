<script setup>
import PageTransition from '@/components/common/PageTransition.vue'
import AppModal from '@/components/common/AppModal.vue'
import { useAgentStore } from '@/stores/agent'

const agentStore = useAgentStore()
</script>

<template>
  <PageTransition />
  <AppModal
    :visible="agentStore.backendDown"
    title="服务端接口不可用"
    description="当前页面需要访问服务端接口。请先启动服务端（确认 API_BASE 可访问），然后点击「重试」。"
    :error-code="agentStore.backendError"
    show-retry
    @close="agentStore.hideBackendModal"
    @retry="agentStore.retry"
  />
</template>
