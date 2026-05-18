<script setup>
import { useStaggerReveal } from '@/composables/useStaggerReveal'

const props = defineProps({
  threshold: { type: Number, default: 0.1 },
  rootMargin: { type: String, default: '0px 0px -30px 0px' },
})

const { containerRef, isRevealed } = useStaggerReveal({
  threshold: props.threshold,
  rootMargin: props.rootMargin,
})
</script>

<template>
  <div ref="containerRef" :class="['stagger-reveal', { 'stagger-reveal--active': isRevealed }]">
    <slot />
  </div>
</template>

<style scoped>
.stagger-reveal > :deep(*) {
  opacity: 0;
  animation: stagger-fade-up 0.7s var(--ease-out-expo) both;
  animation-play-state: paused;
}

.stagger-reveal--active > :deep(*) {
  animation-play-state: running;
}

.stagger-reveal--active > :deep(*:nth-child(1)) { animation-delay: 0ms; }
.stagger-reveal--active > :deep(*:nth-child(2)) { animation-delay: 80ms; }
.stagger-reveal--active > :deep(*:nth-child(3)) { animation-delay: 160ms; }
.stagger-reveal--active > :deep(*:nth-child(4)) { animation-delay: 240ms; }
.stagger-reveal--active > :deep(*:nth-child(5)) { animation-delay: 320ms; }
.stagger-reveal--active > :deep(*:nth-child(6)) { animation-delay: 400ms; }
.stagger-reveal--active > :deep(*:nth-child(7)) { animation-delay: 480ms; }
.stagger-reveal--active > :deep(*:nth-child(8)) { animation-delay: 560ms; }
.stagger-reveal--active > :deep(*:nth-child(9)) { animation-delay: 640ms; }
.stagger-reveal--active > :deep(*:nth-child(10)) { animation-delay: 720ms; }
</style>
