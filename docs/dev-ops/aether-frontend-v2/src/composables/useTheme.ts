import { computed, type ComputedRef, type Ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useUiStore, type Theme } from '@/stores/ui'

export function useTheme(): {
  theme: Ref<Theme>
  isDark: ComputedRef<boolean>
  toggle: () => void
  setTheme: (t: Theme) => void
} {
  const uiStore = useUiStore()
  const { theme } = storeToRefs(uiStore)

  const isDark = computed(() => theme.value === 'dark')

  function toggle(): void {
    uiStore.toggleTheme()
  }

  function setTheme(t: Theme): void {
    uiStore.setTheme(t)
  }

  return {
    theme,
    isDark,
    toggle,
    setTheme,
  }
}
