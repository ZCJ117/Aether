import { ref, onMounted, onUnmounted } from 'vue'

/**
 * Intersection Observer composable — sets isRevealed to true
 * when the element enters the viewport.
 */
export function useScrollReveal(options = {}) {
  const target = ref(null)
  const isRevealed = ref(false)
  let observer = null

  onMounted(() => {
    if (!target.value) return

    observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          isRevealed.value = true
          observer.unobserve(entry.target)
        }
      },
      {
        threshold: options.threshold ?? 0.15,
        rootMargin: options.rootMargin ?? '0px 0px -30px 0px'
      }
    )

    observer.observe(target.value)
  })

  onUnmounted(() => {
    if (observer) observer.disconnect()
  })

  return { target, isRevealed }
}
