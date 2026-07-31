<script setup lang="ts">
import { ref, computed } from 'vue'
import { ChevronDown, ChevronRight, Circle, CheckCircle2, XCircle } from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  toolCallId?: string
  toolName: string
  toolInput?: unknown
  toolOutput?: string
  toolError?: boolean
  status?: 'running' | 'success' | 'error'
}>(), {
  status: 'running',
})

const isExpanded = ref(false)

const statusColor = computed(() => {
  switch (props.status) {
    case 'running': return 'border-blue-500'
    case 'success': return 'border-emerald-500'
    case 'error': return 'border-red-500'
  }
})

const statusDot = computed(() => {
  switch (props.status) {
    case 'running': return { icon: Circle, color: 'text-blue-400' }
    case 'success': return { icon: CheckCircle2, color: 'text-emerald-400' }
    case 'error': return { icon: XCircle, color: 'text-red-400' }
  }
})

const formattedInput = computed(() => {
  if (!props.toolInput) return ''
  try {
    return JSON.stringify(props.toolInput, null, 2)
  } catch {
    return String(props.toolInput)
  }
})

function toggleExpand() {
  isExpanded.value = !isExpanded.value
}
</script>

<template>
  <div
    class="rounded-lg border border-white/5 overflow-hidden transition-colors"
    :class="[statusColor, { 'border-l-2': true }]"
  >
    <!-- Header -->
    <button
      class="flex w-full items-center gap-2 px-3 py-2 text-left text-xs hover:bg-white/5 transition-colors"
      @click="toggleExpand"
    >
      <!-- Status dot -->
      <component :is="statusDot.icon" :size="14" :class="statusDot.color" />
      <span class="flex-1 font-mono text-[#DEDBC8]/70 truncate">{{ toolName }}</span>
      <span v-if="status === 'running'" class="text-blue-400 text-xs">执行中</span>
      <span v-else-if="status === 'success'" class="text-emerald-400 text-xs">成功</span>
      <span v-else class="text-red-400 text-xs">失败</span>
      <component :is="isExpanded ? ChevronDown : ChevronRight" :size="14" class="text-[#DEDBC8]/30 flex-shrink-0" />
    </button>

    <!-- Expanded content -->
    <Transition name="expand">
      <div v-if="isExpanded" class="border-t border-white/5">
        <!-- Input JSON -->
        <div v-if="formattedInput" class="px-3 py-2">
          <div class="text-[10px] uppercase tracking-wider text-[#DEDBC8]/30 mb-1">输入参数</div>
          <pre class="text-xs text-[#DEDBC8]/60 font-mono bg-black/20 rounded p-2 max-h-32 overflow-y-auto">{{ formattedInput }}</pre>
        </div>

        <!-- Running shimmer -->
        <div v-if="status === 'running'" class="px-3 py-2">
          <div class="flex items-center gap-2">
            <div class="h-2 flex-1 rounded bg-gradient-to-r from-blue-500/20 via-blue-400/40 to-blue-500/20 animate-shimmer" />
            <span class="text-xs text-blue-400 font-mono">执行中...</span>
          </div>
        </div>

        <!-- Output -->
        <div v-if="toolOutput && status !== 'running'" class="px-3 py-2">
          <div class="text-[10px] uppercase tracking-wider text-[#DEDBC8]/30 mb-1">输出结果</div>
          <pre
            class="text-xs font-mono rounded p-2 max-h-48 overflow-y-auto whitespace-pre-wrap break-all"
            :class="toolError ? 'bg-red-500/10 text-red-300' : 'bg-emerald-500/10 text-emerald-300'"
          >{{ toolOutput }}</pre>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
@keyframes shimmer {
  0% { background-position: -100% 0; }
  100% { background-position: 100% 0; }
}
.animate-shimmer {
  background-size: 200% 100%;
  animation: shimmer 1.5s ease-in-out infinite;
}

.expand-enter-active,
.expand-leave-active {
  transition: max-height 0.25s ease, opacity 0.2s ease;
  max-height: 600px;
}
.expand-enter-from,
.expand-leave-to {
  max-height: 0;
  opacity: 0;
}
</style>
