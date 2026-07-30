import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchAgents } from '@/api/agent'
import type { AiAgentConfigDTO } from '@/api/types'

/**
 * 智能体状态
 * ---------------------------------------------------------------
 * - 拉取 / 缓存 / 选择
 * - localStorage 记忆最近选中的 agent
 * - backendDown 用于在落地页未来扩展"打开聊天"时弹窗
 */

const LAST_AGENT_KEY = 'ai_agent_last_agent'

export const useAgentStore = defineStore('agent', () => {
  const agents = ref<AiAgentConfigDTO[]>([])
  const selectedAgentId = ref<string>('')
  const isLoading = ref<boolean>(false)
  const backendDown = ref<boolean>(false)
  const backendError = ref<string | null>(null)

  /** 当前展开的 Agent ID（用于折叠面板） */
  const expandedAgentId = ref<string | null>(null)

  function toggleAgent(agentId: string): void {
    expandedAgentId.value = expandedAgentId.value === agentId ? null : agentId
  }

  function expandAgent(agentId: string): void {
    expandedAgentId.value = agentId
  }

  function collapseAgent(agentId: string): void {
    if (expandedAgentId.value === agentId) {
      expandedAgentId.value = null
    }
  }

  const selectedAgent = computed<AiAgentConfigDTO | null>(
    () => agents.value.find((a) => a.agentId === selectedAgentId.value) ?? null
  )
  const hasAgents = computed(() => agents.value.length > 0)

  function selectAgent(id: string): void {
    selectedAgentId.value = id || ''
    if (id) {
      try {
        localStorage.setItem(LAST_AGENT_KEY, id)
      } catch {
        /* storage unavailable */
      }
    }
  }

  async function loadAgents(): Promise<void> {
    isLoading.value = true
    try {
      const data = (await fetchAgents()) ?? []
      agents.value = data
      // 恢复上次选择
      let last = ''
      try {
        last = localStorage.getItem(LAST_AGENT_KEY) || ''
      } catch {
        /* storage unavailable */
      }
      if (last && data.some((a) => a.agentId === last)) {
        selectedAgentId.value = last
      } else {
        selectedAgentId.value = ''
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : '加载失败'
      backendDown.value = true
      backendError.value = message
    } finally {
      isLoading.value = false
    }
  }

  function hideBackendModal(): void {
    backendDown.value = false
    backendError.value = null
  }

  return {
    agents,
    selectedAgentId,
    isLoading,
    backendDown,
    backendError,
    selectedAgent,
    hasAgents,
    selectAgent,
    loadAgents,
    expandedAgentId,
    toggleAgent,
    expandAgent,
    collapseAgent,
    hideBackendModal
  }
})
