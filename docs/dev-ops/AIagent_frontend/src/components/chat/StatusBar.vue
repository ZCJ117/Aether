<script setup>
defineProps({
  text: { type: String, default: '' },
  type: { type: String, default: 'info' }
})
</script>

<template>
  <div
    :class="['status-bar', { error: type === 'error' }]"
    role="status"
    aria-live="polite"
  >
    <Transition name="status-fade" mode="out-in">
      <span :key="text || 'empty'" class="status-bar__text">{{ text }}</span>
    </Transition>
  </div>
</template>

<style scoped>
.status-bar {
  padding: 6px 0;
  font-size: 12px;
  color: var(--text-tertiary);
  text-align: center;
  min-height: 22px;
}

.status-bar.error {
  color: var(--error);
}

.status-bar__text {
  transition: color 0.2s ease;
}

/* Text transition */
.status-fade-enter-active {
  transition: opacity 0.25s var(--ease-out-expo), transform 0.25s var(--ease-out-expo);
}
.status-fade-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.status-fade-enter-from {
  opacity: 0;
  transform: translateY(4px);
}
.status-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
