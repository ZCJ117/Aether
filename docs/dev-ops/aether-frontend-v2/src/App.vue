<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterView } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import ToastNotification from '@/components/common/ToastNotification.vue'
import type { SupportedLocale } from '@/locales'

const ui = useUiStore()
const auth = useAuthStore()
const { locale } = useI18n()

// ---- Theme watcher ----
watch(
  () => ui.theme,
  (val) => {
    const root = document.documentElement
    if (val === 'light') {
      root.classList.add('light')
    } else {
      root.classList.remove('light')
    }
  },
  { immediate: true }
)

// ---- Init ----
onMounted(async () => {
  // Restore theme & locale from localStorage
  const savedTheme = localStorage.getItem('theme') as 'dark' | 'light' | null
  if (savedTheme) {
    ui.setTheme(savedTheme)
  }

  const savedLocale = localStorage.getItem('locale') as SupportedLocale | null
  if (savedLocale) {
    locale.value = savedLocale
  }

  // Check login state
  try {
    await auth.checkLogin()
  } catch {
    // Not authenticated — landing page will handle routing
  }
})
</script>

<template>
  <div class="app-root">
    <RouterView v-slot="{ Component, route }">
      <transition name="page-fade" mode="out-in">
        <component :is="Component" :key="route.fullPath" />
      </transition>
    </RouterView>

    <ToastNotification />
  </div>
</template>

<style scoped>
.app-root {
  min-height: 100vh;
  background-color: var(--bg-primary);
  color: var(--text-primary);
}

/* Page transition */
.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity 0.2s ease;
}

.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
}
</style>
