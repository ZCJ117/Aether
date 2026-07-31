<script setup lang="ts">
import { Loader2 } from 'lucide-vue-next'
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  size?: 'sm' | 'md' | 'lg'
}>(), {
  size: 'md',
})

const sizeClass = computed(() => {
  const map: Record<string, string> = {
    sm: 'w-4 h-4',
    md: 'w-6 h-6',
    lg: 'w-8 h-8',
  }
  return map[props.size] ?? map.md
})
</script>

<template>
  <div class="flex items-center justify-center py-4">
    <Loader2 :class="sizeClass" class="text-accent-blue animate-spin" />
  </div>
</template>
