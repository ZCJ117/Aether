<script setup lang="ts">
import { ref, computed } from 'vue'

const props = defineProps<{
  budgetUsed: number
  budgetTotal: number
  budgetPercent: number
}>()

const showDetail = ref(false)

const barColor = computed(() => {
  if (props.budgetPercent >= 90) return 'bg-red-500'
  if (props.budgetPercent >= 70) return 'bg-amber-500'
  return 'bg-emerald-500'
})

const formattedUsed = computed(() => props.budgetUsed.toLocaleString())
const formattedTotal = computed(() => props.budgetTotal.toLocaleString())

const clampedPercent = computed(() => Math.min(100, Math.max(0, props.budgetPercent)))
</script>

<template>
  <div class="relative">
    <!-- Compact bar -->
    <button
      class="w-full group relative"
      @click="showDetail = !showDetail"
      title="点击展开详情"
    >
      <div class="h-1.5 rounded-full bg-white/5 overflow-hidden cursor-pointer">
        <div
          class="h-full rounded-full transition-all duration-500 ease-out"
          :class="barColor"
          :style="{ width: `${clampedPercent}%` }"
        />
      </div>
    </button>

    <!-- Expanded detail tooltip -->
    <Transition name="tooltip">
      <div
        v-if="showDetail"
        class="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 rounded-lg bg-[#0f0f0f] border border-white/10 px-3 py-2 text-xs text-[#DEDBC8]/70 shadow-xl whitespace-nowrap"
      >
        <span class="font-mono">{{ formattedUsed }}</span>
        <span class="text-[#DEDBC8]/30"> / </span>
        <span class="font-mono">{{ formattedTotal }}</span>
        <span class="text-[#DEDBC8]/30"> tokens </span>
        <span>({{ clampedPercent.toFixed(1) }}%)</span>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.tooltip-enter-active,
.tooltip-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.tooltip-enter-from,
.tooltip-leave-to {
  opacity: 0;
  transform: translate(-50%, 4px);
}
</style>
