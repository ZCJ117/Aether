import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'

export type Theme = 'dark' | 'light'
export type Locale = 'zh-CN' | 'en'

export const useUiStore = defineStore('ui', () => {
  const sidebarCollapsed = ref(false)
  const sidebarWidth = ref(280)
  const globalLoading = ref(false)
  const theme = ref<Theme>((localStorage.getItem('aether_theme') as Theme) || 'dark')
  const locale = ref<Locale>((localStorage.getItem('locale') as Locale) || 'zh-CN')
  const commandPaletteOpen = ref(false)

  const isDark = computed(() => theme.value === 'dark')
  const effectiveSidebarWidth = computed(() => (sidebarCollapsed.value ? 0 : sidebarWidth.value))

  watch(theme, (val) => {
    localStorage.setItem('aether_theme', val)
    document.documentElement.classList.toggle('light', val === 'light')
  }, { immediate: true })

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function setTheme(t: Theme): void {
    theme.value = t
  }

  function toggleTheme(): void {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  function setLocale(l: Locale): void {
    locale.value = l
    localStorage.setItem('locale', l)
  }

  function toggleLocale(): void {
    locale.value = locale.value === 'zh-CN' ? 'en' : 'zh-CN'
  }

  function setGlobalLoading(loading: boolean): void {
    globalLoading.value = loading
  }

  return {
    sidebarCollapsed,
    sidebarWidth,
    globalLoading,
    theme,
    locale,
    commandPaletteOpen,
    isDark,
    effectiveSidebarWidth,
    toggleSidebar,
    setTheme,
    toggleTheme,
    setLocale,
    toggleLocale,
    setGlobalLoading,
  }
})
