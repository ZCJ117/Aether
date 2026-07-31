import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { AiAgentConfigDTO } from '@/types/api'
import type { AgentFullConfig, AgentFormData } from '@/types/agent'
import { fetchAgents, fetchManagementAgents } from '@/api/agent'

export const useAgentStore = defineStore('agent', () => {
  // ---- Chat 视图 ----
  const agents = ref<AiAgentConfigDTO[]>([])
  const selectedAgentId = ref(localStorage.getItem('aether_selected_agent') || '')
  const isLoading = ref(false)
  const backendDown = ref(false)
  const backendError = ref<string | null>(null)
  const expandedAgentId = ref<string | null>(null)

  // ---- 管理面板 ----
  const managementAgents = ref<AgentFullConfig[]>([])
  const managementLoading = ref(false)

  const selectedAgent = computed(() =>
    agents.value.find((a) => a.agentId === selectedAgentId.value) || null
  )
  const hasAgents = computed(() => agents.value.length > 0)

  function selectAgent(id: string): void {
    selectedAgentId.value = id
    localStorage.setItem('aether_selected_agent', id)
  }

  async function loadAgents(): Promise<void> {
    isLoading.value = true
    backendDown.value = false
    backendError.value = null
    try {
      agents.value = await fetchAgents()
      if (agents.value.length > 0 && !selectedAgentId.value) {
        selectAgent(agents.value[0].agentId)
      }
    } catch (err) {
      backendDown.value = true
      backendError.value = (err as Error).message
    } finally {
      isLoading.value = false
    }
  }

  function toggleAgent(id: string): void {
    expandedAgentId.value = expandedAgentId.value === id ? null : id
  }

  function expandAgent(id: string): void {
    expandedAgentId.value = id
  }

  // ---- 管理面板 Actions ----
  async function loadManagementAgents(): Promise<void> {
    managementLoading.value = true
    try {
      managementAgents.value = await fetchManagementAgents()
    } finally {
      managementLoading.value = false
    }
  }

  function addManagementAgent(agent: AgentFullConfig): void {
    managementAgents.value.push(agent)
  }

  function removeManagementAgent(id: string): void {
    managementAgents.value = managementAgents.value.filter((a) => a.agentId !== id)
  }

  return {
    agents,
    selectedAgentId,
    isLoading,
    backendDown,
    backendError,
    expandedAgentId,
    managementAgents,
    managementLoading,
    selectedAgent,
    hasAgents,
    selectAgent,
    loadAgents,
    toggleAgent,
    expandAgent,
    loadManagementAgents,
    addManagementAgent,
    removeManagementAgent,
  }
})
