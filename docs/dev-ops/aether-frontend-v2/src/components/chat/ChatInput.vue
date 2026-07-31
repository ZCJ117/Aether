<script setup lang="ts">
import { ref, computed, nextTick } from 'vue'
import { SendHorizontal, Square, Code, List, Bold } from 'lucide-vue-next'

const props = withDefaults(defineProps<{
  disabled?: boolean
  placeholder?: string
}>(), {
  disabled: false,
  placeholder: '输入消息...',
})

const emit = defineEmits<{
  send: [message: string]
  cancel: []
}>()

const MAX_LENGTH = 4000
const SHOW_COUNT_THRESHOLD = 3000

const textarea = ref<HTMLTextAreaElement | null>(null)
const message = ref('')
const shaking = ref(false)

const charCount = computed(() => message.value.length)
const showCharCount = computed(() => charCount.value > SHOW_COUNT_THRESHOLD)
const charLimitReached = computed(() => charCount.value >= MAX_LENGTH)
const canSend = computed(() => message.value.trim().length > 0 && !props.disabled)

function autoResize() {
  nextTick(() => {
    const el = textarea.value
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 160) + 'px'
  })
}

function handleSend() {
  if (!canSend.value) {
    if (message.value.trim().length === 0 && !props.disabled) {
      shaking.value = true
      setTimeout(() => (shaking.value = false), 500)
    }
    return
  }
  if (charCount.value > MAX_LENGTH) return
  const text = message.value.trim()
  message.value = ''
  nextTick(autoResize)
  emit('send', text)
}

function handleCancel() {
  emit('cancel')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function onInput() {
  autoResize()
}

// ---- Quick action buttons ----
function insertCodeBlock() {
  insertText('\n```\n\n```\n')
}

function insertBulletList() {
  insertText('\n- ')
}

function insertBold() {
  insertText('**粗体**')
}

function insertText(text: string) {
  const el = textarea.value
  if (!el) return
  const start = el.selectionStart
  const end = el.selectionEnd
  message.value = message.value.substring(0, start) + text + message.value.substring(end)
  nextTick(() => {
    el.focus()
    const pos = start + text.length
    el.setSelectionRange(pos, pos)
    autoResize()
  })
}
</script>

<template>
  <div class="border-t border-white/5 bg-[#0f0f0f] p-4">
    <!-- Quick action buttons -->
    <div class="flex items-center gap-1 mb-2">
      <button
        class="rounded p-1 text-[#DEDBC8]/30 hover:text-[#DEDBC8]/60 hover:bg-white/5 transition-colors"
        title="插入代码块"
        @click="insertCodeBlock"
      >
        <Code :size="16" />
      </button>
      <button
        class="rounded p-1 text-[#DEDBC8]/30 hover:text-[#DEDBC8]/60 hover:bg-white/5 transition-colors"
        title="插入列表"
        @click="insertBulletList"
      >
        <List :size="16" />
      </button>
      <button
        class="rounded p-1 text-[#DEDBC8]/30 hover:text-[#DEDBC8]/60 hover:bg-white/5 transition-colors"
        title="插入粗体"
        @click="insertBold"
      >
        <Bold :size="16" />
      </button>
    </div>

    <!-- Textarea + send -->
    <div class="flex items-end gap-3">
      <div class="flex-1 relative" :class="{ 'animate-[shake_0.5s_ease-in-out]': shaking }">
        <textarea
          ref="textarea"
          v-model="message"
          :disabled="disabled"
          :placeholder="placeholder"
          :maxlength="MAX_LENGTH"
          rows="1"
          class="w-full resize-none rounded-lg bg-white/5 px-4 py-2.5 text-sm text-[#DEDBC8] placeholder-[#DEDBC8]/30 border border-white/5 focus:border-white/10 focus:outline-none max-h-40 overflow-y-auto disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          @keydown="onKeydown"
          @input="onInput"
        />
        <!-- Character count -->
        <span
          v-if="showCharCount"
          class="absolute bottom-1 right-2 text-xs"
          :class="charLimitReached ? 'text-red-400' : 'text-[#DEDBC8]/30'"
        >
          {{ charCount }} / {{ MAX_LENGTH }}
        </span>
      </div>

      <!-- Send / Cancel button -->
      <button
        v-if="!disabled"
        class="flex-shrink-0 rounded-lg bg-emerald-600 p-2.5 text-white hover:bg-emerald-500 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        :disabled="!canSend"
        @click="handleSend"
        title="发送"
      >
        <SendHorizontal :size="18" />
      </button>
      <button
        v-else
        class="flex-shrink-0 rounded-lg bg-red-600 p-2.5 text-white hover:bg-red-500 transition-colors"
        @click="handleCancel"
        title="停止"
      >
        <Square :size="18" />
      </button>
    </div>
  </div>
</template>

<style scoped>
@keyframes shake {
  0%, 100% { transform: translateX(0); }
  25% { transform: translateX(-4px); }
  75% { transform: translateX(4px); }
}
</style>
