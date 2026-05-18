<script setup>
import { toRef } from 'vue'
import { useBlurReveal } from '@/composables/useBlurReveal'

const props = defineProps({
  text: { type: String, required: true },
  as: { type: String, default: 'h2' },
  staggerMs: { type: Number, default: 50 },
})

const textRef = toRef(props, 'text')
const { chars } = useBlurReveal(textRef, { staggerMs: props.staggerMs })
</script>

<template>
  <component :is="as" class="blur-reveal-text" :aria-label="text">
    <span
      v-for="c in chars"
      :key="c.index"
      class="blur-reveal-text__char"
      :style="{ animationDelay: c.delay + 's' }"
      :aria-hidden="c.char === ' ' ? 'true' : undefined"
    >{{ c.char }}</span>
  </component>
</template>

<style scoped>
.blur-reveal-text {
  display: inline;
}

.blur-reveal-text__char {
  display: inline-block;
  animation: blur-reveal 0.6s var(--ease-out-expo) both;
  white-space: pre;
}
</style>
