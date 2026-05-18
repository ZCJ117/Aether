import apiClient from './client'

/**
 * GET /api/v1/query_ai_agent_config_list
 * Returns Array<{agentId, agentName, agentDesc}>
 */
export function fetchAgents() {
  return apiClient.get('/api/v1/query_ai_agent_config_list')
}
