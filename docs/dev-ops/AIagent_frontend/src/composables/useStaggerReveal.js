import { ref, onMounted, onUnmounted } from 'vue'

/**
 * Intersection Observer composable that triggers a staggered reveal
 * of direct children via CSS nth-child animation delays.
 */
export function useStaggerReveal(options = {}) {
  const containerRef = ref(null)
  const isRevealed = ref(false)
  let observer = null

  onMounted(() => {
    if (!containerRef.value) return

    observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          isRevealed.value = true
          observer.unobserve(entry.target)
        }
      },
      {
        threshold: options.threshold ?? 0.1,
        rootMargin: options.rootMargin ?? '0px 0px -30px 0px',
      }
    )

    observer.observe(containerRef.value)
  })

  onUnmounted(() => {
    if (observer) observer.disconnect()
  })

  return { containerRef, isRevealed }
}
