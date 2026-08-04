<script setup lang="ts">
import { ChevronRight, ChevronDown, Plus } from 'lucide-vue-next'
import type { SessionInfo } from '@/types/session'
import ConversationRow from './ConversationRow.vue'

const props = defineProps<{
  agentId: string
  agentName: string
  isExpanded: boolean
  sessions: SessionInfo[]
  currentSessionId: string | null
}>()

const emit = defineEmits<{
  toggle: []
  'create-session': []
  'select-session': [sessionId: string]
  'delete-session': [sessionId: string]
}>()
</script>

<template>
  <div class="border-b border-white/5">
    <!-- Header -->
    <button
      class="flex w-full items-center gap-2 px-3 py-2.5 text-left text-sm font-medium text-[#DEDBC8] hover:bg-white/5 transition-colors"
      @click="emit('toggle')"
    >
      <component
        :is="isExpanded ? ChevronDown : ChevronRight"
        :size="16"
        class="flex-shrink-0 text-[#DEDBC8]/50"
      />
      <span class="flex-1 truncate">{{ agentName }}</span>
      <span class="flex-shrink-0 text-xs text-[#DEDBC8]/30 rounded-full bg-white/5 px-1.5 py-0.5">
        {{ sessions.length }}
      </span>
      <span
        class="flex-shrink-0 rounded p-0.5 text-[#DEDBC8]/50 hover:bg-emerald-500/20 hover:text-emerald-400 transition-colors cursor-pointer"
        title="新建对话"
        role="button"
        tabindex="0"
        @click.stop="emit('create-session')"
        @keydown.enter.prevent="emit('create-session')"
        @keydown.space.prevent="emit('create-session')"
      >
        <Plus :size="16" />
      </span>
    </button>

    <!-- Sessions list (expandable) -->
    <Transition name="accordion">
      <div v-if="isExpanded" class="overflow-hidden">
        <div v-if="sessions.length === 0" class="px-4 py-3 text-xs text-[#DEDBC8]/30 text-center">
          暂无对话
        </div>
        <ConversationRow
          v-for="session in sessions"
          :key="session.sessionId"
          :session="session"
          :is-active="session.sessionId === currentSessionId"
          @select="emit('select-session', session.sessionId)"
          @delete="emit('delete-session', session.sessionId)"
        />
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.accordion-enter-active {
  transition: max-height 0.25s ease, opacity 0.2s ease;
  max-height: 2000px;
}
.accordion-leave-active {
  transition: max-height 0.2s ease, opacity 0.15s ease;
  max-height: 2000px;
}
.accordion-enter-from,
.accordion-leave-to {
  max-height: 0;
  opacity: 0;
}
</style>
