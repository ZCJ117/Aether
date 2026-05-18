import apiClient from './client'

/**
 * POST /api/v1/create_session
 * Body: { agentId, userId }
 * Returns: { sessionId }
 */
export function createSession(agentId, userId) {
  return apiClient.post('/api/v1/create_session', {
    agentId,
    userId
  })
}
