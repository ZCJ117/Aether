<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { ChevronDown, MessageSquare } from 'lucide-vue-next'
import type { ChatMessage } from '@/types/chat'
import MessageBubble from './MessageBubble.vue'

const props = defineProps<{
  messages: ChatMessage[]
}>()

const container = ref<HTMLDivElement | null>(null)
const isAtBottom = ref(true)
const userScrolled = ref(false)

function scrollToBottom(smooth = true) {
  nextTick(() => {
    if (!container.value) return
    container.value.scrollTo({
      top: container.value.scrollHeight,
      behavior: smooth ? 'smooth' : 'auto',
    })
    isAtBottom.value = true
    userScrolled.value = false
  })
}

function onScroll() {
  if (!container.value) return
  const { scrollTop, scrollHeight, clientHeight } = container.value
  isAtBottom.value = scrollHeight - scrollTop - clientHeight < 60
  userScrolled.value = !isAtBottom.value
}

// Auto-scroll on new messages
watch(
  () => props.messages.length,
  () => {
    nextTick(() => {
      if (!userScrolled.value) {
        scrollToBottom(false)
      }
    })
  }
)

// Auto-scroll on message text changes (streaming)
watch(
  () => props.messages.map((m) => m.text),
  () => {
    if (!userScrolled.value) {
      scrollToBottom(false)
    }
  }
)

onMounted(() => {
  scrollToBottom(false)
  container.value?.addEventListener('scroll', onScroll, { passive: true })
})

onUnmounted(() => {
  container.value?.removeEventListener('scroll', onScroll)
})
</script>

<template>
  <div class="relative flex-1 min-h-0">
    <div ref="container" class="h-full overflow-y-auto">
      <!-- Empty state -->
      <div
        v-if="messages.length === 0"
        class="flex flex-col items-center justify-center h-full text-[#DEDBC8]/20"
      >
        <MessageSquare :size="48" class="mb-4" />
        <p class="text-sm">开始一段新的对话</p>
      </div>

      <!-- Messages -->
      <div v-else class="max-w-3xl mx-auto px-4 py-6 space-y-6">
        <MessageBubble
          v-for="message in messages"
          :key="message.id"
          :message="message"
        />
      </div>
    </div>

    <!-- Scroll to bottom button -->
    <Transition name="fade-up">
      <button
        v-if="!isAtBottom"
        class="absolute bottom-4 right-4 flex items-center justify-center w-9 h-9 rounded-full bg-[#0f0f0f] border border-white/10 text-[#DEDBC8]/60 hover:text-[#DEDBC8] hover:border-white/20 shadow-lg transition-colors"
        @click="scrollToBottom(true)"
        title="滚动到底部"
      >
        <ChevronDown :size="18" />
      </button>
    </Transition>
  </div>
</template>

<style scoped>
.fade-up-enter-active,
.fade-up-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.fade-up-enter-from,
.fade-up-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
