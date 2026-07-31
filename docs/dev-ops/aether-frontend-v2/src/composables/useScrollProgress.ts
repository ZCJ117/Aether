import { ref, onMounted, onUnmounted, type Ref } from 'vue'

export function useScrollProgress(
  elRef: Ref<HTMLElement | null>,
  offset: [string, string] = ['start 0.8', 'end 0.2']
): {
  progress: Ref<number>
} {
  const progress = ref(0)

  let rafId: number | null = null
  let observer: IntersectionObserver | null = null

  function updateProgress(): void {
    if (!elRef.value) return

    const rect = elRef.value.getBoundingClientRect()
    const viewportHeight = window.innerHeight
    const topOut = viewportHeight * 0.2
    const bottomIn = viewportHeight * 0.8

    // Element top relative to the viewport clamped between 0.8 and 0.2 viewport height
    const start = bottomIn
    const end = topOut
    const current = rect.top

    if (current >= start) {
      progress.value = 0
    } else if (current <= end) {
      progress.value = 1
    } else {
      progress.value = (start - current) / (start - end)
    }
  }

  function onScroll(): void {
    if (rafId !== null) return
    rafId = requestAnimationFrame(() => {
      updateProgress()
      rafId = null
    })
  }

  onMounted(() => {
    if (typeof IntersectionObserver !== 'undefined') {
      observer = new IntersectionObserver(
        () => {
          onScroll()
        },
        {
          threshold: [0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1],
        }
      )

      if (elRef.value) {
        observer.observe(elRef.value)
      }
    }

    window.addEventListener('scroll', onScroll, { passive: true })
    updateProgress()
  })

  onUnmounted(() => {
    if (observer) {
      observer.disconnect()
    }

    if (rafId !== null) {
      cancelAnimationFrame(rafId)
    }

    window.removeEventListener('scroll', onScroll)
  })

  return { progress }
}
