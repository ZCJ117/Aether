<script setup lang="ts">
import { ref } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import type { SessionInfo } from '@/types/session'
import { relativeTime } from '@/utils/time'

defineProps<{
  session: SessionInfo
  isActive: boolean
}>()

const emit = defineEmits<{
  select: []
  delete: []
}>()

const showDelete = ref(false)
</script>

<template>
  <button
    class="group relative w-full flex items-center gap-2 px-3 py-2 text-left text-sm transition-colors"
    :class="isActive ? 'bg-white/10 text-[#DEDBC8]' : 'text-[#DEDBC8]/60 hover:bg-white/5 hover:text-[#DEDBC8]/80'"
    @click="emit('select')"
    @mouseenter="showDelete = true"
    @mouseleave="showDelete = false"
  >
    <span class="flex-1 truncate">{{ session.title }}</span>
    <span class="flex-shrink-0 text-xs text-[#DEDBC8]/30">{{ relativeTime(session.createdAt) }}</span>

    <!-- Delete button on hover -->
    <Transition name="fade">
      <button
        v-if="showDelete"
        class="flex-shrink-0 rounded p-0.5 text-[#DEDBC8]/40 hover:bg-red-500/20 hover:text-red-400"
        @click.stop="emit('delete')"
        title="删除会话"
      >
        <Trash2 :size="14" />
      </button>
    </Transition>
  </button>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
