<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  status: string
  size?: 'sm' | 'md'
}>(), {
  size: 'md',
})

const colorClass = computed(() => {
  const map: Record<string, string> = {
    active: 'text-accent-green bg-accent-green/10',
    error: 'text-accent-red bg-accent-red/10',
    warning: 'text-accent-yellow bg-accent-yellow/10',
    paused: 'text-slate-400 bg-slate-400/10',
  }
  return map[props.status] ?? 'text-slate-400 bg-slate-400/10'
})

const label = computed(() => {
  const map: Record<string, string> = {
    active: '运行中',
    error: '错误',
    warning: '警告',
    paused: '已暂停',
  }
  return map[props.status] ?? props.status
})

const sizeClass = computed(() => props.size === 'sm' ? 'text-2xs px-1.5 py-0' : 'text-xs px-2 py-0.5')
</script>

<template>
  <span
    :class="[colorClass, sizeClass]"
    class="inline-flex items-center gap-1 rounded-full font-medium"
  >
    <span class="w-1.5 h-1.5 rounded-full bg-current flex-shrink-0" />
    <span>{{ label }}</span>
  </span>
</template>
