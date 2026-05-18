<script setup>
import { ref, watch } from 'vue'
import AppButton from '@/components/common/AppButton.vue'
import { useTextareaAutosize } from '@/composables/useTextareaAutosize'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  placeholder: { type: String, default: '输入消息，回车发送（Shift+Enter 换行）' }
})

const emit = defineEmits(['send'])

const message = ref('')
const textareaRef = ref(null)
const { onInput, resetHeight } = useTextareaAutosize()

// Clear input when disabled changes to false (message sent)
watch(
  () => props.disabled,
  (val) => {
    if (!val) {
      message.value = ''
      resetHeight(textareaRef.value)
    }
  }
)

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

function submit() {
  const text = message.value.trim()
  if (!text || props.disabled) return
  emit('send', text)
  message.value = ''
  resetHeight(textareaRef.value)
}
</script>

<template>
  <div class="composer-wrap">
    <form class="composer" @submit.prevent="submit">
      <textarea
        ref="textareaRef"
        v-model="message"
        :placeholder="placeholder"
        :disabled="disabled"
        rows="1"
        autocomplete="off"
        @input="onInput"
        @keydown="onKeydown"
      />
      <AppButton
        variant="send"
        type="submit"
        :disabled="disabled || !message.trim()"
        aria-label="发送"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" style="width:16px;height:16px;margin-left:1px">
          <line x1="22" y1="2" x2="11" y2="13" />
          <polygon points="22 2 15 22 11 13 2 9 22 2" />
        </svg>
      </AppButton>
    </form>
  </div>
</template>

<style scoped>
.composer-wrap {
  padding: 0 24px 20px;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
}

.composer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  background: var(--surface);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  padding: 10px 10px 10px 18px;
  border: 1.5px solid transparent;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.composer:focus-within {
  border-color: var(--border);
  box-shadow: 0 0 0 4px var(--focus-ring-color), 0 2px 18px rgba(0, 0, 0, 0.08);
  animation: ring-glow 0.8s var(--ease-out-expo);
}

.composer textarea {
  flex: 1 1 auto;
  border: none;
  outline: none;
  resize: none;
  font-family: var(--font);
  font-size: 14px;
  line-height: 1.6;
  color: var(--text);
  background: transparent;
  min-height: 24px;
  max-height: 150px;
  padding: 4px 0;
}

.composer textarea::placeholder {
  color: var(--text-tertiary);
}

.composer textarea:disabled {
  opacity: 0.5;
}

@media (max-width: 767px) {
  .composer-wrap {
    padding: 0 12px 14px;
  }
  .composer {
    padding: 8px 8px 8px 14px;
  }
}
</style>
