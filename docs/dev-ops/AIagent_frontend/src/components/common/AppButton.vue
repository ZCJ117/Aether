<script setup>
defineProps({
  variant: {
    type: String,
    default: 'solid',
    validator: (v) => ['solid', 'outline', 'ghost', 'send'].includes(v)
  },
  type: { type: String, default: 'button' },
  disabled: { type: Boolean, default: false },
  ariaLabel: { type: String, default: '' }
})

defineEmits(['click'])
</script>

<template>
  <button
    :class="['app-btn', `app-btn--${variant}`]"
    :type="type"
    :disabled="disabled"
    :aria-label="ariaLabel || undefined"
    @click="$emit('click')"
  >
    <slot />
  </button>
</template>

<style scoped>
.app-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: none;
  font-family: var(--font);
  cursor: pointer;
  white-space: nowrap;
  transition: opacity 0.18s ease, transform 0.12s ease, border-color 0.18s ease,
    color 0.18s ease, box-shadow 0.18s ease;
}

.app-btn:active {
  transform: scale(0.97);
}

/* Solid — dark background pill */
.app-btn--solid {
  height: 46px;
  padding: 0 24px;
  border-radius: var(--radius-full);
  background: var(--text);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.3px;
}

.app-btn--solid:hover {
  opacity: 0.88;
}

.app-btn--solid:disabled {
  opacity: 0.45;
  cursor: default;
}

/* Outline — border-only pill */
.app-btn--outline {
  height: 32px;
  padding: 0 16px;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
}

.app-btn--outline:hover {
  border-color: var(--text);
  color: var(--text);
}

/* Ghost — text-only, no border */
.app-btn--ghost {
  height: 34px;
  padding: 0 18px;
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.app-btn--ghost:hover {
  color: var(--text);
}

/* Send — circular icon button */
.app-btn--send {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: var(--text);
  color: #fff;
  flex-shrink: 0;
}

.app-btn--send:hover {
  opacity: 0.85;
}

.app-btn--send:active {
  transform: scale(0.93);
}

.app-btn--send:disabled {
  opacity: 0.35;
  cursor: default;
}
</style>
