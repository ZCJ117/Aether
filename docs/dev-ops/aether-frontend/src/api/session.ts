import apiClient from './client'
import type { CreateSessionRequestDTO, CreateSessionResponseDTO, SessionItemDTO } from './types'

/**
 * 会话相关 API
 * ---------------------------------------------------------------
 * POST /api/v1/create_session
 *   Body:  { agentId, userId }
 *   Reply: { sessionId }
 */

/** 创建新会话 — 返回后端生成的 sessionId */
export function createSession(
  agentId: string,
  userId: string
): Promise<CreateSessionResponseDTO> {
  const body: CreateSessionRequestDTO = { agentId, userId }
  return apiClient.post<unknown, CreateSessionResponseDTO>('/api/v1/create_session', body)
}

/** 创建新会话（GET 方式） — 返回后端生成的 sessionId */
export function createSessionGet(
  agentId: string,
  userId: string
): Promise<CreateSessionResponseDTO> {
  return apiClient.get<unknown, CreateSessionResponseDTO>('/api/v1/create_session', {
    params: { agentId, userId }
  })
}

/**
 * GET /api/v1/list_sessions — 查询用户在指定 Agent 下的会话列表
 */
export function fetchSessions(
  agentId: string,
  userId: string
): Promise<SessionItemDTO[]> {
  return apiClient.get<unknown, SessionItemDTO[]>('/api/v1/list_sessions', {
    params: { agentId, userId }
  })
}
