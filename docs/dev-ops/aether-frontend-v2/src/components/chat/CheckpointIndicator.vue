<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Check } from 'lucide-vue-next'

const props = defineProps<{
  turnNumber: number
  sessionId: string
}>()

const visible = ref(true)
let timer: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  timer = setTimeout(() => {
    visible.value = false
  }, 3000)
})

onUnmounted(() => {
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <Transition name="chip">
    <div
      v-if="visible"
      class="inline-flex items-center gap-1.5 rounded-full bg-emerald-500/10 border border-emerald-500/20 px-2.5 py-1 text-xs text-emerald-400"
    >
      <Check :size="12" />
      <span>Checkpoint #{{ turnNumber }} 已保存</span>
    </div>
  </Transition>
</template>

<style scoped>
.chip-enter-active {
  transition: opacity 0.3s ease, transform 0.3s ease;
}
.chip-leave-active {
  transition: opacity 0.5s ease;
}
.chip-enter-from {
  opacity: 0;
  transform: translateY(-4px);
}
.chip-leave-to {
  opacity: 0;
}
</style>
