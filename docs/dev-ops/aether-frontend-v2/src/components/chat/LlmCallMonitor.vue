<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { Cpu, CheckCircle2, XCircle } from 'lucide-vue-next'
import { useChatStore } from '@/stores/chat'

const chatStore = useChatStore()

const currentLog = ref<{
  source: string
  model: string
  durationMs: number
  success: boolean
} | null>(null)

const visible = ref(false)
const dismissed = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

function showToast() {
  visible.value = true
  dismissed.value = false
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => {
    visible.value = false
  }, 3000)
}

function dismiss() {
  visible.value = false
  dismissed.value = true
}

watch(
  () => chatStore.llmCallLogs.length,
  (newLen, oldLen) => {
    if (newLen > oldLen && !dismissed.value) {
      const latest = chatStore.llmCallLogs[chatStore.llmCallLogs.length - 1]
      currentLog.value = { ...latest }
      showToast()
    }
  }
)

onUnmounted(() => {
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <Transition name="toast">
    <div
      v-if="visible && currentLog"
      class="fixed bottom-20 right-4 z-40 flex items-center gap-2 rounded-lg border border-white/10 bg-[#0f0f0f] px-3 py-2 shadow-xl text-xs"
    >
      <component
        :is="currentLog.success ? CheckCircle2 : XCircle"
        :size="14"
        :class="currentLog.success ? 'text-emerald-400' : 'text-red-400'"
      />
      <Cpu :size="12" class="text-[#DEDBC8]/30" />
      <span class="text-[#DEDBC8]/60">
        <span class="text-[#DEDBC8]/80 font-medium">{{ currentLog.source }}</span>
        <span class="text-[#DEDBC8]/30"> → </span>
        <span class="font-mono">{{ currentLog.model }}</span>
        <span class="text-[#DEDBC8]/30"> ({{ currentLog.durationMs }}ms)</span>
      </span>
      <button
        class="ml-2 rounded p-0.5 text-[#DEDBC8]/20 hover:text-[#DEDBC8]/50 transition-colors"
        @click="dismiss"
      >
        ×
      </button>
    </div>
  </Transition>
</template>

<style scoped>
.toast-enter-active {
  transition: opacity 0.3s ease, transform 0.3s ease;
}
.toast-leave-active {
  transition: opacity 0.3s ease, transform 0.3s ease;
}
.toast-enter-from {
  opacity: 0;
  transform: translateY(16px);
}
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
