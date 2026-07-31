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
