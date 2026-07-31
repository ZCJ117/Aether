import { api } from './client'
import { createSSEConnection } from './sse-client'
import type { ChatRequestDTO, ChatResponseDTO, ConfirmRequestDTO } from '@/types/api'
import type { StreamEvent, StreamHandle } from '@/types/sse'

const BASE = '/api/v1'

/** 阻塞式对话 */
export function sendMessage(
  agentId: string,
  userId: string,
  sessionId: string | null,
  message: string
): Promise<ChatResponseDTO> {
  return api.post<ChatResponseDTO>(`${BASE}/chat`, {
    agentId,
    userId,
    sessionId: sessionId ?? undefined,
    message,
  } satisfies ChatRequestDTO)
}

/** SSE 流式对话 */
export function sendMessageStream(
  agentId: string,
  userId: string,
  sessionId: string | null,
  message: string,
  onEvent: (event: StreamEvent) => void,
  onError?: (error: Error) => void,
  onComplete?: () => void
): StreamHandle {
  return createSSEConnection({
    url: `${BASE}/chat_stream`,
    body: {
      agentId,
      userId,
      sessionId: sessionId ?? undefined,
      message,
    } satisfies ChatRequestDTO,
    onEvent,
    onError,
    onComplete,
  })
}

/** H4 工具调用权限确认 */
export function confirmToolCalls(
  agentId: string,
  userId: string,
  sessionId: string,
  confirmResults: { toolCallId: string; approved: boolean }[]
): Promise<unknown> {
  return api.post(`${BASE}/confirm`, {
    agentId,
    userId,
    sessionId,
    confirmResults,
  } satisfies ConfirmRequestDTO)
}
