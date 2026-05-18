<script setup>
defineProps({
  side: {
    type: String,
    required: true,
    validator: (v) => ['user', 'agent'].includes(v)
  },
  text: { type: String, required: true },
  meta: { type: String, default: null },
  index: { type: Number, default: 0 }
})
</script>

<template>
  <div :class="['msg-row', side]" :style="{ '--msg-index': index }">
    <div :class="['bubble', side]">
      {{ text }}
      <div v-if="meta" class="meta">{{ meta }}</div>
    </div>
  </div>
</template>

<style scoped>
.msg-row {
  display: flex;
}

.msg-row.user {
  justify-content: flex-start;
}

.msg-row.agent {
  justify-content: flex-end;
}

.bubble {
  max-width: 72%;
  padding: 14px 18px;
  border-radius: var(--radius-md);
  font-size: 14px;
  line-height: 1.65;
  word-break: normal;
  overflow-wrap: break-word;
  white-space: pre-line;
  opacity: 0;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
  animation-name: fadeUp;
  animation-duration: 0.45s;
  animation-timing-function: var(--ease-out-expo);
  animation-fill-mode: forwards;
  animation-delay: calc(var(--msg-index, 0) * 45ms);
}

.bubble:hover {
  transform: translateY(-1px);
}

.bubble.user {
  background: #eeeeef;
  color: var(--text);
  border-bottom-left-radius: 4px;
}

.bubble.agent {
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow-md);
  border-bottom-right-radius: 4px;
  border-left: 3px solid transparent;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.4s ease;
}

.bubble.agent:hover {
  box-shadow: 0 4px 18px rgba(0, 0, 0, 0.10);
  border-left-color: var(--text);
  transform: translateY(-2px) scale(1.01);
}

.bubble .meta {
  margin-top: 8px;
  font-size: 11px;
  color: var(--text-tertiary);
}

@media (max-width: 767px) {
  .bubble {
    max-width: 88%;
    font-size: 13px;
  }
}
</style>
