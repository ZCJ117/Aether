<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  current: number
  max: number
}>()

const isWarning = computed(() => {
  if (props.max === 0) return false
  return props.current / props.max > 0.8
})

const displayCurrent = computed(() => Math.min(props.current, props.max))
</script>

<template>
  <div
    class="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-mono border transition-colors"
    :class="isWarning
      ? 'bg-amber-500/10 border-amber-500/20 text-amber-400'
      : 'bg-white/5 border-white/5 text-[#DEDBC8]/40'"
  >
    Round {{ displayCurrent }} / {{ max }}
  </div>
</template>
