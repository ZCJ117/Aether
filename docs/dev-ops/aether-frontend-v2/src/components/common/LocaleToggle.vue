<script setup lang="ts">
import { computed } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useI18n } from 'vue-i18n'

const ui = useUiStore()
const { locale } = useI18n()

const localeLabel = computed(() => ui.locale === 'zh-CN' ? '中' : 'EN')

function toggleLocale() {
  const next = ui.locale === 'zh-CN' ? 'en' : 'zh-CN'
  ui.setLocale(next)
  locale.value = next
}
</script>

<template>
  <button
    @click="toggleLocale"
    class="p-2 rounded-lg text-secondary hover:text-primary hover:bg-white/[0.05] transition-colors text-xs font-medium"
    :title="ui.locale === 'zh-CN' ? 'Switch to English' : '切换到中文'"
  >
    {{ localeLabel }}
  </button>
</template>
