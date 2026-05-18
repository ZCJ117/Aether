<script setup>
defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  errorCode: { type: String, default: '' },
  showRetry: { type: Boolean, default: false }
})

import AppButton from './AppButton.vue'

const emit = defineEmits(['close', 'retry'])

function onBackdropClick(e) {
  if (e.target === e.currentTarget) emit('close')
}
</script>

<template>
  <Transition name="modal">
    <div
      v-if="visible"
      class="modal-backdrop"
      role="dialog"
      aria-modal="true"
      @click="onBackdropClick"
    >
      <div class="modal">
        <div class="modal-header">
          <h3>{{ title }}</h3>
          <button class="modal-close" type="button" @click="emit('close')">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <p>{{ description }}</p>
          <code v-if="errorCode">{{ errorCode }}</code>
        </div>
        <div class="modal-footer">
          <AppButton v-if="showRetry" variant="ghost" @click="emit('retry')">
            重试
          </AppButton>
          <AppButton variant="solid" @click="emit('close')"> 知道了 </AppButton>
        </div>
      </div>
    </div>
  </Transition>
</template>


<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.modal {
  width: min(520px, 100%);
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xl);
  overflow: hidden;
  animation: fadeIn 0.3s ease;
}

.modal-header {
  padding: 18px 20px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.modal-header h3 {
  font-size: 15px;
  font-weight: 600;
}

.modal-close {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  display: grid;
  place-items: center;
  transition: color 0.18s ease;
}

.modal-close:hover {
  color: var(--text);
}

.modal-body {
  padding: 4px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.modal-body p {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.6;
}

.modal-body code {
  display: block;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: #f5f5f7;
  border: 1px solid var(--border-light);
  font-size: 12px;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--font-mono);
}

.modal-footer {
  padding: 0 20px 18px;
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}
</style>
