<script setup lang="ts">
import { ref, computed } from 'vue'
import { X, Shield, ShieldAlert } from 'lucide-vue-next'
import type { PendingToolCall } from '@/types/sse'
import { useChatStore } from '@/stores/chat'

const props = defineProps<{
  visible: boolean
  replyId: string
  pendingToolCalls: PendingToolCall[]
  agentId: string
  userId: string
  sessionId: string
}>()

const emit = defineEmits<{
  close: []
  confirmed: []
}>()

const chatStore = useChatStore()
const isSubmitting = ref(false)

const decisions = ref<Map<string, boolean>>(new Map())

function toggleDecision(toolCallId: string) {
  const current = decisions.value.get(toolCallId)
  if (current === true) {
    decisions.value.set(toolCallId, false)
  } else {
    decisions.value.set(toolCallId, true)
  }
}

function allowAll() {
  for (const tc of props.pendingToolCalls) {
    decisions.value.set(tc.toolCallId, true)
  }
}

function denyAll() {
  for (const tc of props.pendingToolCalls) {
    decisions.value.set(tc.toolCallId, false)
  }
}

async function handleConfirm() {
  if (isSubmitting.value) return
  isSubmitting.value = true
  try {
    const results = props.pendingToolCalls.map((tc) => ({
      toolCallId: tc.toolCallId,
      approved: decisions.value.get(tc.toolCallId) ?? false,
    }))
    await chatStore.confirmPermission(props.agentId, props.userId, props.sessionId, results)
    emit('confirmed')
  } finally {
    isSubmitting.value = false
  }
}

function handleClose() {
  emit('close')
}

function getInputPreview(input?: Record<string, unknown>): string {
  if (!input) return '无参数'
  try {
    const json = JSON.stringify(input, null, 2)
    return json.length > 200 ? json.slice(0, 200) + '...' : json
  } catch {
    return '无法解析参数'
  }
}

function getRiskLevel(input?: Record<string, unknown>): 'low' | 'medium' | 'high' {
  if (!input) return 'low'
  const str = JSON.stringify(input).toLowerCase()
  if (str.includes('delete') || str.includes('drop') || str.includes('rm') || str.includes('exec')) {
    return 'high'
  }
  if (str.includes('write') || str.includes('update') || str.includes('save') || str.includes('create')) {
    return 'medium'
  }
  return 'low'
}

function riskColor(level: string): string {
  switch (level) {
    case 'high': return 'text-red-400 border-red-500/30 bg-red-500/5'
    case 'medium': return 'text-amber-400 border-amber-500/30 bg-amber-500/5'
    default: return 'text-emerald-400 border-emerald-500/30 bg-emerald-500/5'
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="visible"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        @click.self="handleClose"
      >
        <div class="w-full max-w-lg mx-4 rounded-xl border border-white/10 bg-[#0f0f0f] shadow-2xl">
          <!-- Title -->
          <div class="flex items-center justify-between px-5 py-4 border-b border-white/5">
            <div class="flex items-center gap-2">
              <Shield :size="18" class="text-amber-400" />
              <h2 class="text-sm font-medium text-[#DEDBC8]">工具调用授权</h2>
            </div>
            <button
              class="rounded p-1 text-[#DEDBC8]/30 hover:text-[#DEDBC8] hover:bg-white/5 transition-colors"
              @click="handleClose"
            >
              <X :size="16" />
            </button>
          </div>

          <!-- Tool call list -->
          <div class="px-5 py-3 max-h-[50vh] overflow-y-auto space-y-3">
            <div
              v-for="tc in pendingToolCalls"
              :key="tc.toolCallId"
              class="rounded-lg border p-3 transition-colors"
              :class="riskColor(getRiskLevel(tc.input))"
            >
              <div class="flex items-start justify-between gap-3">
                <div class="flex-1 min-w-0">
                  <div class="flex items-center gap-2">
                    <span class="text-sm font-mono font-medium truncate">{{ tc.toolName }}</span>
                    <ShieldAlert
                      v-if="getRiskLevel(tc.input) === 'high'"
                      :size="14"
                      class="text-red-400 flex-shrink-0"
                    />
                  </div>
                  <div v-if="tc.reason" class="mt-1 text-xs opacity-70">{{ tc.reason }}</div>
                  <pre class="mt-2 text-xs font-mono opacity-60 whitespace-pre-wrap break-all">{{ getInputPreview(tc.input) }}</pre>
                </div>
                <div class="flex gap-1 flex-shrink-0">
                  <button
                    class="rounded px-2 py-1 text-xs font-medium transition-colors"
                    :class="decisions.get(tc.toolCallId) === true
                      ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                      : 'bg-white/5 text-[#DEDBC8]/40 border border-white/5 hover:text-[#DEDBC8]/60'"
                    @click="toggleDecision(tc.toolCallId)"
                  >
                    {{ decisions.get(tc.toolCallId) === true ? '已允许' : '允许' }}
                  </button>
                  <button
                    class="rounded px-2 py-1 text-xs font-medium transition-colors"
                    :class="decisions.get(tc.toolCallId) === false
                      ? 'bg-red-500/20 text-red-400 border border-red-500/30'
                      : 'bg-white/5 text-[#DEDBC8]/40 border border-white/5 hover:text-[#DEDBC8]/60'"
                    @click="toggleDecision(tc.toolCallId)"
                  >
                    {{ decisions.get(tc.toolCallId) === false ? '已拒绝' : '拒绝' }}
                  </button>
                </div>
              </div>
            </div>
          </div>

          <!-- Batch actions + confirm -->
          <div class="flex items-center justify-between px-5 py-3 border-t border-white/5">
            <div class="flex gap-2">
              <button
                class="rounded-md px-3 py-1.5 text-xs font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 hover:bg-emerald-500/20 transition-colors"
                @click="allowAll"
              >
                全部允许
              </button>
              <button
                class="rounded-md px-3 py-1.5 text-xs font-medium bg-red-500/10 text-red-400 border border-red-500/20 hover:bg-red-500/20 transition-colors"
                @click="denyAll"
              >
                全部拒绝
              </button>
            </div>
            <button
              class="rounded-md px-4 py-1.5 text-xs font-medium bg-blue-600 text-white hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              :disabled="isSubmitting"
              @click="handleConfirm"
            >
              {{ isSubmitting ? '提交中...' : '确认' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.25s ease;
}
.modal-enter-active > :deep(div:first-child),
.modal-leave-active > :deep(div:first-child) {
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-from > :deep(div:first-child),
.modal-leave-to > :deep(div:first-child) {
  opacity: 0;
  transform: scale(0.95) translateY(-8px);
}
</style>
