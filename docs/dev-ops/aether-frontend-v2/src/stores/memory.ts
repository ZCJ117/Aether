import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { MemoryRecord, MemoryTreeNode } from '@/types/memory'

export const useMemoryStore = defineStore('memory', () => {
  const memories = ref<MemoryRecord[]>([])
  const selectedMemory = ref<MemoryRecord | null>(null)
  const isLoading = ref(false)
  const searchQuery = ref('')

  const workingMemories = computed(() => memories.value.filter((m) => m.scope === 'working'))
  const shortTermMemories = computed(() => memories.value.filter((m) => m.scope === 'short_term'))
  const longTermMemories = computed(() => memories.value.filter((m) => m.scope === 'long_term'))

  const memoryTree = computed<MemoryTreeNode[]>(() => [
    { id: 'working', label: '工作记忆', children: workingMemories.value.map(toTreeNode) },
    { id: 'short_term', label: '短期记忆', children: shortTermMemories.value.map(toTreeNode) },
    { id: 'long_term', label: '长期记忆', children: longTermMemories.value.map(toTreeNode) },
  ])

  async function loadMemories(): Promise<void> {
    isLoading.value = true
    try {
      memories.value = [
        { id: 'm1', title: '用户偏好: 简洁回复', content: '用户 prefer concise responses', scope: 'short_term', type: 'semantic', path: '/preferences', createdAt: Date.now(), updatedAt: Date.now() },
        { id: 'm2', title: '上次对话摘要', content: '讨论了旅游规划功能', scope: 'short_term', type: 'episodic', path: '/history', createdAt: Date.now(), updatedAt: Date.now() },
        { id: 'm3', title: '代码规范文档', content: '使用 ESLint + Prettier', scope: 'long_term', type: 'procedural', path: '/knowledge/coding', createdAt: Date.now(), updatedAt: Date.now() },
      ]
    } finally { isLoading.value = false }
  }

  function toTreeNode(record: MemoryRecord): MemoryTreeNode {
    return { id: record.id, label: record.title, record }
  }

  return {
    memories, selectedMemory, isLoading, searchQuery,
    workingMemories, shortTermMemories, longTermMemories, memoryTree,
    loadMemories,
  }
})
