<script setup>
defineProps({
  username: { type: String, default: '-' },
  meta: { type: String, default: '-' }
})
</script>

<template>
  <div class="user-pill">
    <strong>{{ username }}</strong>
    <span>{{ meta }}</span>
  </div>
</template>

<style scoped>
.user-pill {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border-radius: var(--radius-full);
  background: var(--surface);
  border: 1px solid var(--border-light);
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
  flex-shrink: 0;
}

.user-pill strong {
  font-weight: 600;
  color: var(--text);
}
</style>
