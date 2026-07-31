import { ref, watch, type Ref } from 'vue'

export function useDebounce<T>(value: Ref<T>, delay: number): Ref<T> {
  const debounced = ref(value.value) as Ref<T>

  let timer: ReturnType<typeof setTimeout> | null = null

  watch(value, (newVal) => {
    if (timer !== null) {
      clearTimeout(timer)
    }
    timer = setTimeout(() => {
      debounced.value = newVal
    }, delay)
  })

  return debounced
}
