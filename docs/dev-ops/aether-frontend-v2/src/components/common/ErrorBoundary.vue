<script setup lang="ts">
import { ref, onErrorCaptured } from 'vue'

const hasError = ref(false)
const errorMessage = ref('')

onErrorCaptured((err: unknown) => {
  hasError.value = true
  errorMessage.value = err instanceof Error ? err.message : String(err)
  return false
})

function retry() {
  hasError.value = false
  errorMessage.value = ''
}
</script>

<template>
  <template v-if="hasError">
    <div class="flex flex-col items-center justify-center py-16 px-4">
      <div class="w-16 h-16 rounded-full bg-accent-red/10 flex items-center justify-center mb-4">
        <span class="text-accent-red text-2xl">!</span>
      </div>
      <h3 class="text-lg font-medium text-primary mb-1">出现错误</h3>
      <p class="text-sm text-secondary mb-4 text-center max-w-md">
        {{ errorMessage || '发生了未知错误，请重试。' }}
      </p>
      <button
        @click="retry"
        class="px-4 py-2 bg-accent-red/10 text-accent-red rounded-lg text-sm font-medium
               hover:bg-accent-red/20 transition-colors"
      >
        重试
      </button>
    </div>
  </template>
  <template v-else>
    <slot />
  </template>
</template>
