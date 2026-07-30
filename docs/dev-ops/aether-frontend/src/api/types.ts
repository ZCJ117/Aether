/**
 * 全局 API 类型定义
 * ---------------------------------------------------------------
 * 统一封装 Aether 后端响应包络（Response<T>）、DTO、错误码、
 * SSE 事件类型。所有 API 模块统一从此文件导入类型。
 */

/** 后端统一响应包络 — 与 cn.zcj.aether.api.response.Response 对应 */
export interface ApiResponse<T> {
  code: string
  info: string
  data: T
}

/** 业务错误码（与后端 ResponseCode 对齐） */
export const ResponseCode = {
  SUCCESS: '0000',
  UN_ERROR: '0001'
  // 后续按需扩展
} as const

export type ResponseCodeValue = (typeof ResponseCode)[keyof typeof ResponseCode]

/* ---------------- 智能体 ---------------- */

/** 智能体配置响应 DTO */
export interface AiAgentConfigDTO {
  agentId: string
  agentName: string
  agentDesc: string
}

/** 会话列表响应 DTO */
export interface SessionItemDTO {
  sessionId: string
  agentId: string
  userId: string
  title: string
  status: string
  createdAt: string   // ISO instant string
  updatedAt: string
}

/* ---------------- 会话 ---------------- */

export interface CreateSessionRequestDTO {
  agentId: string
  userId: string
}

export interface CreateSessionResponseDTO {
  sessionId: string
}

/* ---------------- 对话 ---------------- */

export interface ChatRequestDTO {
  agentId: string
  userId: string
  /** 后端允许为空：为 null 时由后端创建 */
  sessionId?: string
  message: string
}

export interface ChatResponseDTO {
  content: string
}

/* ---------------- SSE 事件 ---------------- */

/**
 * 与后端 RuntimeEvent 枚举对齐的事件类型。
 * 前端通过 type 字段做 discriminated union 处理。
 */
export type StreamEventType =
  | 'textDelta'
  | 'toolCall'
  | 'toolResult'
  | 'compactBoundary'
  | 'error'
  | 'turnComplete'
  | 'permissionAsking'
  | 'agentPaused'
  | 'tokenBudget'
  | 'checkpoint'
  | 'maxTurnsReached'
  | 'internalLlmCall'
  | 'done'

export interface StreamEventBase {
  type: StreamEventType
}

export interface TextDeltaEvent extends StreamEventBase {
  type: 'textDelta'
  text: string
}

export interface ToolCallEvent extends StreamEventBase {
  type: 'toolCall'
  toolCallId?: string
  toolName?: string
  toolInput?: unknown
}

export interface ToolResultEvent extends StreamEventBase {
  type: 'toolResult'
  toolCallId?: string
  toolName?: string
  toolOutput?: unknown
  toolError?: boolean
}

export interface CompactBoundaryEvent extends StreamEventBase {
  type: 'compactBoundary'
  summary?: string
}

export interface ErrorEvent extends StreamEventBase {
  type: 'error'
  errorMessage: string
}

export interface TurnCompleteEvent extends StreamEventBase {
  type: 'turnComplete'
  turnCount: number
}

export interface PermissionAskingEvent extends StreamEventBase {
  type: 'permissionAsking'
  replyId?: string
  pendingToolCalls?: unknown
}

export interface AgentPausedEvent extends StreamEventBase {
  type: 'agentPaused'
  reason?: string
}

export interface TokenBudgetEvent extends StreamEventBase {
  type: 'tokenBudget'
  budgetUsed?: number
  budgetTotal?: number
  budgetPercent?: number
}

export interface CheckpointEvent extends StreamEventBase {
  type: 'checkpoint'
  sessionId?: string
  turnNumber?: number
}

export interface MaxTurnsReachedEvent extends StreamEventBase {
  type: 'maxTurnsReached'
}

export interface InternalLlmCallEvent extends StreamEventBase {
  type: 'internalLlmCall'
  source?: string   // "context-compaction" | "memory-encoding"
  model?: string    // model name used
  durationMs?: number // duration in ms
  success?: boolean // whether successful
}

export interface DoneEvent extends StreamEventBase {
  type: 'done'
}

export type StreamEvent =
  | TextDeltaEvent
  | ToolCallEvent
  | ToolResultEvent
  | CompactBoundaryEvent
  | ErrorEvent
  | TurnCompleteEvent
  | PermissionAskingEvent
  | AgentPausedEvent
  | TokenBudgetEvent
  | CheckpointEvent
  | MaxTurnsReachedEvent
  | InternalLlmCallEvent
  | DoneEvent

/* ---------------- 流式对话控制句柄 ---------------- */

export interface StreamHandle {
  /** 流处理结束的 Promise（done / error / 主动 cancel 都会 resolve） */
  promise: Promise<void>
  /** 主动取消 — 会中断 fetch / reader */
  cancel: () => void
}

/* ---------------- 工具类型 ---------------- */

/** 任意可序列化数据 */
export type Json = unknown

/** API 调用抛错的统一错误对象 */
export interface ApiError extends Error {
  code?: string
  status?: number
  payload?: unknown
}
