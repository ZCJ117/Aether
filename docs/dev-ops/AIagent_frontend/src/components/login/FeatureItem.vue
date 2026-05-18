<script setup>
defineProps({
  title: { type: String, required: true },
  description: { type: String, required: true }
})
</script>

<template>
  <div class="feature">
    <div class="feature-icon" aria-hidden="true">
      <slot name="icon" />
    </div>
    <div class="feature-text">
      <b>{{ title }}</b>
      <span>{{ description }}</span>
    </div>
  </div>
</template>

<style scoped>
.feature {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 4px;
  border-radius: var(--radius-sm);
  transition: transform 0.3s var(--ease-out-expo);
}

.feature:hover {
  transform: translateX(6px);
}

.feature-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-full);
  background: #f5f5f7;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  margin-top: 1px;
  transition: transform 0.3s var(--ease-out-expo), background 0.3s ease, box-shadow 0.3s ease;
}

.feature:hover .feature-icon {
  transform: scale(1.15);
  background: #e8e8ed;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.feature-icon :deep(svg) {
  width: 14px;
  height: 14px;
  color: var(--text);
}

.feature-text b {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
  margin-bottom: 2px;
}

.feature-text span {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.5;
}
</style>
