<script setup>
import { useScrollReveal } from '@/composables/useScrollReveal'

const props = defineProps({
  threshold: { type: Number, default: 0.15 },
  rootMargin: { type: String, default: '0px 0px -30px 0px' }
})

const { target, isRevealed } = useScrollReveal({
  threshold: props.threshold,
  rootMargin: props.rootMargin
})
</script>

<template>
  <div ref="target" :class="['scroll-reveal', { 'scroll-reveal--visible': isRevealed }]">
    <slot />
  </div>
</template>

<style scoped>
.scroll-reveal {
  opacity: 0;
  transform: translateY(28px);
  transition: opacity 0.7s cubic-bezier(0.25, 0.1, 0.25, 1),
    transform 0.7s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.scroll-reveal--visible {
  opacity: 1;
  transform: translateY(0);
}
</style>
