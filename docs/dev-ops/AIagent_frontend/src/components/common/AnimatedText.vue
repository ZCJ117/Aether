<script setup>
import { toRef } from 'vue'
import { useLetterAnimation } from '@/composables/useLetterAnimation'

const props = defineProps({
  text: { type: String, required: true },
  as: { type: String, default: 'span' },
  speed: {
    type: String,
    default: 'medium',
    validator: (v) => ['slow', 'medium', 'fast'].includes(v)
  },
  loop: { type: Boolean, default: true }
})

const textRef = toRef(props, 'text')
const { chars } = useLetterAnimation(textRef, { speed: props.speed })
</script>

<template>
  <component
    :is="as"
    class="animated-text"
    :class="{ 'animated-text--once': !loop }"
    :aria-label="text"
  >
    <span
      v-for="c in chars"
      :key="c.index"
      class="animated-text__char"
      :style="{
        '--char-delay': c.delay + 's',
        '--char-duration': c.duration + 's'
      }"
      :aria-hidden="c.char === ' ' ? 'true' : undefined"
    >{{ c.char }}</span>
  </component>
</template>

<style scoped>
.animated-text {
  display: inline;
}

.animated-text__char {
  display: inline-block;
  animation: letter-breathe var(--char-duration, 3s) ease-in-out infinite;
  animation-delay: var(--char-delay, 0s);
}

.animated-text--once .animated-text__char {
  animation-iteration-count: 1;
  animation-fill-mode: both;
}
</style>
