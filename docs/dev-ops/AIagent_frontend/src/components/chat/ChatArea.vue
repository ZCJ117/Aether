<script setup>
import { watch, nextTick, ref } from 'vue'
import MessageBubble from './MessageBubble.vue'
import ChatEmpty from './ChatEmpty.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  isEmpty: { type: Boolean, default: true }
})

const chatRef = ref(null)

function scrollToBottom() {
  nextTick(() => {
    if (chatRef.value) {
      chatRef.value.scrollTop = chatRef.value.scrollHeight
    }
  })
}

// Auto-scroll when messages change
watch(
  () => props.messages.length,
  () => scrollToBottom()
)

defineExpose({ scrollToBottom })
</script>

<template>
  <div ref="chatRef" class="chat">
    <Transition name="fade" mode="out-in">
      <ChatEmpty v-if="isEmpty" key="empty" />
      <TransitionGroup
        v-else
        name="message"
        tag="div"
        class="chat-messages"
        key="messages"
      >
        <MessageBubble
          v-for="(msg, idx) in messages"
          :key="msg.id"
          :side="msg.side"
          :text="msg.text"
          :meta="msg.meta"
          :index="idx"
        />
      </TransitionGroup>
    </Transition>
  </div>
</template>

<style scoped>
.chat {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 20px 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.chat-messages {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

@media (max-width: 767px) {
  .chat {
    gap: 12px;
    padding: 14px 0;
  }
}
</style>
