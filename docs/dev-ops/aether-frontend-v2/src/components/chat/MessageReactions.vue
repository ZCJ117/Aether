<script setup lang="ts">
import { ref } from 'vue'
import { Copy, RotateCcw } from 'lucide-vue-next'

defineProps<{
  messageId: string
}>()

const emit = defineEmits<{
  copy: []
  retry: []
}>()

const copied = ref(false)
let copyTimer: ReturnType<typeof setTimeout> | null = null

function handleCopy() {
  copied.value = true
  emit('copy')
  if (copyTimer) clearTimeout(copyTimer)
  copyTimer = setTimeout(() => {
    copied.value = false
  }, 2000)
}
</script>

<template>
  <div class="flex items-center gap-1 mt-1 opacity-0 group-hover:opacity-100 transition-opacity">
    <!-- Copy button -->
    <div class="relative">
      <button
        class="rounded p-1 text-[#DEDBC8]/20 hover:text-[#DEDBC8]/50 hover:bg-white/5 transition-colors"
        title="复制"
        @click="handleCopy"
      >
        <Copy :size="14" />
      </button>
      <Transition name="tooltip-mini">
        <span
          v-if="copied"
          class="absolute -top-7 left-1/2 -translate-x-1/2 rounded bg-[#0f0f0f] border border-white/10 px-2 py-0.5 text-[10px] text-emerald-400 whitespace-nowrap"
        >
          已复制
        </span>
      </Transition>
    </div>

    <!-- Retry button -->
    <button
      class="rounded p-1 text-[#DEDBC8]/20 hover:text-[#DEDBC8]/50 hover:bg-white/5 transition-colors"
      title="重试"
      @click="emit('retry')"
    >
      <RotateCcw :size="14" />
    </button>
  </div>
</template>

<style scoped>
.tooltip-mini-enter-active,
.tooltip-mini-leave-active {
  transition: opacity 0.15s ease;
}
.tooltip-mini-enter-from,
.tooltip-mini-leave-to {
  opacity: 0;
}
</style>
