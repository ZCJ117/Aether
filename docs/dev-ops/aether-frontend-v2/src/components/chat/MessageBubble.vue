<script setup lang="ts">
import { ref, computed } from 'vue'
import { Copy, RotateCcw } from 'lucide-vue-next'
import type { ChatMessage, ToolCallState } from '@/types/chat'
import { renderMarkdown } from '@/utils/markdown'
import ToolCallDisplay from './ToolCallDisplay.vue'
import MessageReactions from './MessageReactions.vue'

const props = defineProps<{
  message: ChatMessage
}>()

const showTimestamp = ref(false)
const expandedMeta = computed<Record<string, unknown>>(() => props.message.meta || {})

const renderedText = computed(() => {
  try {
    return renderMarkdown(props.message.text || '')
  } catch {
    return props.message.text || ''
  }
})

// Extract tool calls from meta
const toolCalls = computed(() => {
  const calls = expandedMeta.value.toolCalls
  if (!Array.isArray(calls)) return []
  return calls as Array<{
    toolCallId?: string
    toolName: string
    toolInput?: unknown
    toolOutput?: string
    toolError?: boolean
    status?: string
  }>
})

function formatMessageTime(timestamp?: number): string {
  if (timestamp == null) return ''
  try {
    return new Date(timestamp).toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return ''
  }
}

function handleCopy() {
  navigator.clipboard.writeText(props.message.text).catch(() => {
    // fallback
  })
}

function handleRetry() {
  // Retry handled by parent
}
</script>

<template>
  <div
    class="group"
    :class="{
      'flex justify-end': message.side === 'user',
      'flex justify-start': message.side === 'agent',
      'flex justify-center': message.side === 'system',
    }"
  >
    <!-- System message -->
    <div
      v-if="message.side === 'system'"
      class="text-xs text-[#DEDBC8]/30 py-1 px-3 rounded-full bg-white/5"
      @mouseenter="showTimestamp = true"
      @mouseleave="showTimestamp = false"
    >
      <span>{{ message.text }}</span>
      <span v-if="showTimestamp" class="ml-2 opacity-50">{{ formatMessageTime(message.timestamp) }}</span>
    </div>

    <!-- User message -->
    <div
      v-else-if="message.side === 'user'"
      class="inline-block max-w-[80%] px-4 py-2.5 rounded-l-lg rounded-br-lg bg-white/10 text-[#DEDBC8] text-sm"
      @mouseenter="showTimestamp = true"
      @mouseleave="showTimestamp = false"
    >
      <div class="whitespace-pre-wrap break-words">{{ message.text }}</div>
      <div v-if="showTimestamp" class="mt-1 text-xs text-[#DEDBC8]/30 text-right">
        {{ formatMessageTime(message.timestamp) }}
      </div>
    </div>

    <!-- Agent message -->
    <div
      v-else
      class="w-full"
      @mouseenter="showTimestamp = true"
      @mouseleave="showTimestamp = false"
    >
      <!-- Tool calls -->
      <div v-if="toolCalls.length > 0" class="mb-3 space-y-2">
        <ToolCallDisplay
          v-for="(tc, i) in toolCalls"
          :key="tc.toolCallId || i"
          :tool-call-id="tc.toolCallId"
          :tool-name="tc.toolName"
          :tool-input="tc.toolInput"
          :tool-output="tc.toolOutput"
          :tool-error="tc.toolError"
          :status="(tc.status as 'running' | 'success' | 'error') || 'success'"
        />
      </div>

      <!-- Markdown content -->
      <div
        class="prose prose-invert prose-sm max-w-none text-[#DEDBC8]"
        v-html="renderedText"
      />

      <!-- Streaming cursor -->
      <span
        v-if="message.streaming"
        class="inline-block w-2 h-4 ml-0.5 bg-[#DEDBC8]/60 animate-pulse align-text-bottom"
      />

      <!-- Actions bar -->
      <MessageReactions
        :message-id="message.id"
        @copy="handleCopy"
        @retry="handleRetry"
      />

      <!-- Timestamp -->
      <div v-if="showTimestamp && message.timestamp" class="mt-1 text-xs text-[#DEDBC8]/20">
        {{ formatMessageTime(message.timestamp) }}
      </div>
    </div>
  </div>
</template>
