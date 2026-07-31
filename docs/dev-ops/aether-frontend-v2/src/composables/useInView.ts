import { ref, type Ref } from 'vue'

/**
 * IntersectionObserver 封装 — 对标 framer-motion useInView
 */
export function useInView(options?: {
  once?: boolean
  rootMargin?: string
  threshold?: number
}): { elRef: Ref<HTMLElement | null>; isInView: Ref<boolean> } {
  const elRef = ref<HTMLElement | null>(null)
  const isInView = ref(false)
  const { once = true, rootMargin = '0px', threshold = 0.1 } = options || {}

  let observer: IntersectionObserver | null = null

  if (typeof IntersectionObserver !== 'undefined') {
    observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          isInView.value = true
          if (once && observer && elRef.value) {
            observer.unobserve(elRef.value)
          }
        } else if (!once) {
          isInView.value = false
        }
      },
      { rootMargin, threshold }
    )
  } else {
    isInView.value = true
  }

  return { elRef, isInView }
}
