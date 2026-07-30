<script setup lang="ts">
import { computed } from 'vue'
import { ChevronRight, Plus } from 'lucide-vue-next'
import ConversationRow from './ConversationRow.vue'
import type { SessionInfo } from '@/stores/session'

const props = defineProps<{
  agentId: string
  agentName: string
  agentDesc: string
  sessions: SessionInfo[]
  isExpanded: boolean
  isSelected: boolean
  currentSessionId: string | null
}>()

const emit = defineEmits<{
  toggle: []
  createSession: []
  selectSession: [sessionId: string]
  deleteSession: [sessionId: string]
}>()

const hasConversations = computed(() => props.sessions.length > 0)
</script>

<template>
  <div class="rounded-xl overflow-hidden">
    <!-- 头部行 -->
    <button
      @click="emit('toggle')"
      :class="[
        'w-full flex items-center gap-2.5 px-3 py-2.5 rounded-xl transition-colors duration-150 group',
        isSelected
          ? 'bg-primary/10'
          : 'hover:bg-white/[0.04]'
      ]"
    >
      <!-- 展开箭头: 0.25s旋转, Apple cubic-bezier -->
      <div
        :class="[
          'text-primary/40 transition-transform duration-250 flex-shrink-0',
          'ease-[cubic-bezier(0.4,0,0.2,1)]',
          isExpanded ? 'rotate-90' : ''
        ]"
      >
        <ChevronRight :size="14" :stroke-width="2" />
      </div>

      <!-- Agent 信息 -->
      <div class="flex-1 min-w-0 text-left">
        <div
          :class="[
            'text-[13px] font-medium leading-tight truncate transition-colors duration-150',
            isSelected ? 'text-primary-100' : 'text-primary/70 group-hover:text-primary-100'
          ]"
        >
          {{ agentName }}
        </div>
        <div
          v-if="agentDesc"
          class="text-[11px] text-primary/30 mt-0.5 truncate leading-tight"
        >
          {{ agentDesc }}
        </div>
      </div>

      <!-- "+" 圆形按钮: Apple-style 32x32 circle -->
      <button
        @click.stop="emit('createSession')"
        class="w-8 h-8 rounded-full flex items-center justify-center
               bg-primary/10 text-primary/70
               hover:bg-primary/20 hover:text-primary-100 hover:scale-105
               active:scale-95
               transition-all duration-150 flex-shrink-0"
        title="新建对话"
      >
        <Plus :size="16" :stroke-width="2" />
      </button>
    </button>

    <!-- 对话列表: max-height动画 -->
    <div
      :class="[
        'overflow-hidden transition-all duration-250 ease-[cubic-bezier(0.4,0,0.2,1)]',
        isExpanded ? 'max-h-96 opacity-100' : 'max-h-0 opacity-0'
      ]"
    >
      <div class="pl-7 pr-1 py-1 space-y-0.5">
        <!-- 空状态 -->
        <div
          v-if="!hasConversations"
          class="text-[11px] text-primary/25 py-2 px-3"
        >
          暂无对话
        </div>

        <!-- 对话列表 -->
        <ConversationRow
          v-for="session in sessions"
          :key="session.sessionId"
          :session="session"
          :is-active="session.sessionId === currentSessionId"
          @select="emit('selectSession', session.sessionId)"
          @delete="emit('deleteSession', session.sessionId)"
        />
      </div>
    </div>
  </div>
</template>
