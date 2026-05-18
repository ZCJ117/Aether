import { computed } from 'vue'

/**
 * Splits text into individual character objects with staggered delay
 * for the blur-reveal keyframe animation.
 */
export function useBlurReveal(textRef, options = {}) {
  const staggerMs = options.staggerMs ?? 50

  const chars = computed(() => {
    const text = String(textRef.value || '')
    return text.split('').map((char, i) => ({
      char: char === ' ' ? ' ' : char,
      index: i,
      delay: (i * staggerMs) / 1000,
    }))
  })

  return { chars }
}
