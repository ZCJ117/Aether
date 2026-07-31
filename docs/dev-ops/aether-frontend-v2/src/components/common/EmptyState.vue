<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  icon?: Component
  title: string
  description?: string
  actionLabel?: string
}>()

defineEmits<{
  action: []
}>()
</script>

<template>
  <div class="flex flex-col items-center justify-center py-16 px-4">
    <component
      v-if="icon"
      :is="icon"
      class="w-16 h-16 text-secondary opacity-40 mb-4"
    />
    <h3 class="text-lg font-medium text-primary mb-1">{{ title }}</h3>
    <p v-if="description" class="text-sm text-secondary mb-4 text-center max-w-md">
      {{ description }}
    </p>
    <button
      v-if="actionLabel"
      @click="$emit('action')"
      class="px-4 py-2 bg-accent-blue/10 text-accent-blue rounded-lg text-sm font-medium
             hover:bg-accent-blue/20 transition-colors"
    >
      {{ actionLabel }}
    </button>
  </div>
</template>
