<script setup lang="ts">
import { computed } from 'vue'
import { Brain, AlertCircle, CheckCircle2, AlertTriangle } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'

const chatStore = useChatStore()

const iconComponent = computed(() => {
  switch (chatStore.statusType) {
    case 'error': return AlertCircle
    case 'success': return CheckCircle2
    case 'warning': return AlertTriangle
    default: return Brain
  }
})

const iconClass = computed(() => {
  switch (chatStore.statusType) {
    case 'error': return 'text-red-400'
    case 'success': return 'text-emerald-400'
    case 'warning': return 'text-amber-400'
    default: return 'text-blue-400'
  }
})
</script>

<template>
  <Transition name="status-fade">
    <div
      v-if="chatStore.statusText"
      class="flex items-center gap-2 px-4 py-1.5 text-xs"
      :class="{
        'animate-pulse': chatStore.isSending && chatStore.statusType === 'info',
      }"
    >
      <component
        :is="iconComponent"
        :size="14"
        :class="[iconClass, { 'animate-spin': chatStore.isSending && chatStore.statusType === 'error' }]"
      />
      <span class="text-[#DEDBC8]/50">{{ chatStore.statusText }}</span>
    </div>
  </Transition>
</template>

<style scoped>
.status-fade-enter-active,
.status-fade-leave-active {
  transition: opacity 0.25s ease;
}
.status-fade-enter-from,
.status-fade-leave-to {
  opacity: 0;
}
</style>
