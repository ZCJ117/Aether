import { api } from './client'
import type { CreateSessionRequestDTO, CreateSessionResponseDTO, SessionItemDTO } from '@/types/api'

const BASE = '/api/v1'

export function createSession(agentId: string, userId: string): Promise<CreateSessionResponseDTO> {
  return api.post<CreateSessionResponseDTO>(`${BASE}/create_session`, {
    agentId,
    userId,
  } satisfies CreateSessionRequestDTO)
}

export function fetchSessions(agentId: string, userId: string): Promise<SessionItemDTO[]> {
  return api.get<SessionItemDTO[]>(`${BASE}/list_sessions`, { agentId, userId })
}

export function deleteSession(sessionId: string): Promise<void> {
  return api.delete<void>(`${BASE}/delete_session?sessionId=${encodeURIComponent(sessionId)}`)
}

export interface SessionMessageDTO {
  role: string
  content: string
}

export function fetchSessionMessages(sessionId: string): Promise<SessionMessageDTO[]> {
  return api.get<SessionMessageDTO[]>(`${BASE}/session_messages`, { sessionId })
}
