<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  tag?: string
}>(), {
  tag: undefined,
})
</script>

<template>
  <div class="inline-flex items-center gap-2 text-xs text-white/50">
    <span class="w-1.5 h-1.5 rounded-full bg-white" />
    <span>{{ label }}</span>
    <span
      v-if="tag"
      class="px-2 py-0.5 rounded-full border border-white/10 text-white/50"
    >
      {{ tag }}
    </span>
  </div>
</template>
