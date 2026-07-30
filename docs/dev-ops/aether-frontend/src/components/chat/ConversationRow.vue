<script setup lang="ts">
import { computed } from 'vue'
import { Trash2 } from 'lucide-vue-next'
import type { SessionInfo } from '@/stores/session'

const props = defineProps<{
  session: SessionInfo
  isActive: boolean
}>()

const emit = defineEmits<{
  select: []
  delete: []
}>()

/** 相对时间文本 */
const timeAgo = computed(() => {
  const diff = Date.now() - props.session.createdAt
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return '刚刚'
  if (mins < 60) return `${mins}分钟前`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}天前`
  return new Date(props.session.createdAt).toLocaleDateString()
})
</script>

<template>
  <button
    @click="emit('select')"
    :class="[
      'w-full text-left px-3 py-2 rounded-lg text-sm group transition-colors duration-150 flex items-center gap-2',
      isActive
        ? 'bg-primary/10 text-primary-100'
        : 'text-primary/50 hover:bg-white/5 hover:text-primary-100'
    ]"
  >
    <!-- 左侧指示条 -->
    <div
      :class="[
        'w-[3px] h-4 rounded-full flex-shrink-0 transition-colors duration-150',
        isActive ? 'bg-primary' : 'bg-transparent'
      ]"
    />
    <!-- 标题 -->
    <span class="truncate flex-1 text-[13px] leading-tight">
      {{ session.title || '新对话' }}
    </span>
    <!-- 时间 + 删除 -->
    <div class="flex items-center gap-1 flex-shrink-0">
      <span class="text-[11px] text-primary/25 tabular-nums">
        {{ timeAgo }}
      </span>
      <button
        @click.stop="emit('delete')"
        class="opacity-0 group-hover:opacity-100 text-primary/30 hover:text-red-400 transition-all p-0.5"
        title="删除会话"
      >
        <Trash2 :size="12" />
      </button>
    </div>
  </button>
</template>
