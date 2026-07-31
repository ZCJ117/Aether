<script setup lang="ts">
import { ChevronRight } from 'lucide-vue-next'
import AetherLogo from './AetherLogo.vue'

withDefaults(defineProps<{
  label?: string
  full?: boolean
}>(), {
  label: '开始使用',
  full: false,
})

const emit = defineEmits<{
  click: []
}>()
</script>

<template>
  <button
    type="button"
    :class="[
      'group inline-flex items-center justify-center gap-2 rounded-full bg-white text-black font-medium text-sm px-5 py-3 transition-all hover:bg-white/90 active:scale-[0.98]',
      full ? 'w-full' : '',
    ]"
    @click="emit('click')"
  >
    <AetherLogo className="w-4 h-4 text-black" />
    <span>{{ label }}</span>
    <ChevronRight
      class="w-4 h-4 transition-transform duration-200 group-hover:translate-x-[1px]"
      :stroke-width="2"
    />
  </button>
</template>
