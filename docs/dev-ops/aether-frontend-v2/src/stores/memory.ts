import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { LongTermMemoryEntry } from '@/types/memory'

const STORAGE_PREFIX = 'agent_long_term_memory_'

function generateId(): string {
  return `mem_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

export const useMemoryStore = defineStore('memory', () => {
  const memories = ref<LongTermMemoryEntry[]>([])
  const currentAgentId = ref<string>('')

  function getStorageKey(agentId: string): string {
    return `${STORAGE_PREFIX}${agentId}`
  }

  /** 加载指定 Agent 的长期记忆，切换 Agent 时调用 */
  function loadMemories(agentId: string): void {
    currentAgentId.value = agentId
    if (!agentId) {
      memories.value = []
      return
    }
    try {
      const raw = localStorage.getItem(getStorageKey(agentId))
      memories.value = raw ? JSON.parse(raw) : []
    } catch {
      memories.value = []
    }
  }

  function persist(): void {
    if (!currentAgentId.value) return
    localStorage.setItem(
      getStorageKey(currentAgentId.value),
      JSON.stringify(memories.value)
    )
  }

  function addMemory(content: string): LongTermMemoryEntry {
    const entry: LongTermMemoryEntry = {
      id: generateId(),
      content,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    }
    memories.value.push(entry)
    persist()
    return entry
  }

  function updateMemory(id: string, content: string): void {
    const idx = memories.value.findIndex((m) => m.id === id)
    if (idx !== -1) {
      memories.value[idx] = {
        ...memories.value[idx],
        content,
        updatedAt: Date.now(),
      }
      persist()
    }
  }

  function deleteMemory(id: string): void {
    memories.value = memories.value.filter((m) => m.id !== id)
    persist()
  }

  return {
    memories,
    currentAgentId,
    loadMemories,
    addMemory,
    updateMemory,
    deleteMemory,
  }
})
