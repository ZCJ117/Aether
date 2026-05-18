<script setup>
defineProps({
  size: { type: String, default: 'md' }, // sm = 32px, md = 36px
  showName: { type: Boolean, default: true }
})
</script>

<template>
  <div class="brand">
    <div :class="['brand-mark', `brand-mark--${size}`]" aria-hidden="true">AI</div>
    <span v-if="showName" class="brand-name">AI Agent Scaffold</span>
  </div>
</template>

<style scoped>
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.brand-mark {
  border-radius: 9px;
  background: var(--text);
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 700;
  letter-spacing: 0.3px;
  flex-shrink: 0;
}

.brand-mark--sm {
  width: 32px;
  height: 32px;
  font-size: 11px;
}

.brand-mark--md {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  font-size: 13px;
}

.brand-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
  letter-spacing: 0.1px;
  white-space: nowrap;
}
</style>
