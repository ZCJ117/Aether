import { ref, computed, type Ref } from 'vue'

export function usePagination(initialPageSize: number = 10): {
  page: Ref<number>
  pageSize: Ref<number>
  total: Ref<number>
  setTotal: (t: number) => void
  nextPage: () => void
  prevPage: () => void
  goToPage: (p: number) => void
  reset: () => void
} {
  const page = ref(1)
  const pageSize = ref(initialPageSize)
  const total = ref(0)

  const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

  function setTotal(t: number): void {
    total.value = t
  }

  function nextPage(): void {
    if (page.value < totalPages.value) {
      page.value++
    }
  }

  function prevPage(): void {
    if (page.value > 1) {
      page.value--
    }
  }

  function goToPage(p: number): void {
    if (p >= 1 && p <= totalPages.value) {
      page.value = p
    }
  }

  function reset(): void {
    page.value = 1
    total.value = 0
  }

  return { page, pageSize, total, setTotal, nextPage, prevPage, goToPage, reset }
}
