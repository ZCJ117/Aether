<script setup lang="ts">
import { ref } from 'vue'
import { X } from 'lucide-vue-next'

defineProps<{
  summary: string
}>()

const dismissed = ref(false)

function dismiss() {
  dismissed.value = true
}
</script>

<template>
  <Transition name="banner">
    <div
      v-if="!dismissed"
      class="flex items-center justify-between gap-3 px-4 py-2 mx-4 my-2 rounded-lg border border-amber-500/20 bg-amber-500/5 text-xs text-amber-300/80"
    >
      <p class="flex-1 min-w-0">
        <span class="font-medium text-amber-300">对话上下文已压缩</span>
        <span class="text-amber-300/50"> — {{ summary }}</span>
      </p>
      <button
        class="flex-shrink-0 rounded p-0.5 text-amber-300/40 hover:text-amber-300 hover:bg-amber-500/10 transition-colors"
        @click="dismiss"
        title="关闭"
      >
        <X :size="14" />
      </button>
    </div>
  </Transition>
</template>

<style scoped>
.banner-enter-active,
.banner-leave-active {
  transition: all 0.3s ease;
}
.banner-enter-from,
.banner-leave-to {
  opacity: 0;
  max-height: 0;
  margin-top: 0;
  margin-bottom: 0;
  padding-top: 0;
  padding-bottom: 0;
}
</style>
