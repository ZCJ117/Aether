import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchAgents } from '@/api/agent'

const LAST_AGENT_KEY = 'ai_agent_last_agent'

export const useAgentStore = defineStore('agent', () => {
  const agents = ref([])
  const selectedAgentId = ref('')
  const isLoading = ref(false)
  const backendDown = ref(false)
  const backendError = ref(null)

  const selectedAgent = computed(
    () => agents.value.find((a) => a.agentId === selectedAgentId.value) || null
  )
  const hasAgents = computed(() => agents.value.length > 0)

  function selectAgent(id) {
    selectedAgentId.value = id || ''
    if (id) {
      localStorage.setItem(LAST_AGENT_KEY, id)
    }
  }

  async function loadAgents() {
    isLoading.value = true
    try {
      const data = (await fetchAgents()) || []
      // API interceptor already unwraps to data array
      agents.value = data

      // Restore last selected agent
      const lastAgent = localStorage.getItem(LAST_AGENT_KEY) || ''
      if (lastAgent && data.some((a) => a.agentId === lastAgent)) {
        selectedAgentId.value = lastAgent
      } else {
        selectedAgentId.value = ''
      }
    } finally {
      isLoading.value = false
    }
  }

  async function retry() {
    backendDown.value = false
    backendError.value = null
    await loadAgents()
  }

  function hideBackendModal() {
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
    retry,
    hideBackendModal
  }
})
