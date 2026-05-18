import { computed } from 'vue'

const SPEED_MAP = {
  slow: { duration: 4.5, stagger: 0.10 },
  medium: { duration: 3.0, stagger: 0.07 },
  fast: { duration: 2.0, stagger: 0.05 }
}

/**
 * Splits text into individual character objects with staggered animation
 * delay and duration values for the letter-breathe keyframe.
 *
 * @param {import('vue').Ref<string>} textRef - Reactive text string
 * @param {Object} options
 * @param {'slow'|'medium'|'fast'} options.speed - Animation speed preset
 */
export function useLetterAnimation(textRef, options = {}) {
  const speed = options.speed || 'medium'
  const cfg = SPEED_MAP[speed] || SPEED_MAP.medium

  const chars = computed(() => {
    const text = String(textRef.value || '')
    return text.split('').map((char, i) => ({
      char: char === ' ' ? ' ' : char,
      index: i,
      delay: i * cfg.stagger,
      duration: cfg.duration
    }))
  })

  const totalStaggerMs = computed(() => {
    const len = String(textRef.value || '').length
    return (len - 1) * cfg.stagger * 1000
  })

  return { chars, totalStaggerMs }
}
