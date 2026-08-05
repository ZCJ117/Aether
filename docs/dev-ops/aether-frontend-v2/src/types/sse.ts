// ============================================
// SSE 事件类型定义 — 完整 13 种事件类型
// ============================================

export type SSEEventType =
  | 'turnStarted'
  | 'textDelta'
  | 'toolCall'
  | 'toolResult'
  | 'compactBoundary'
  | 'turnComplete'
  | 'tokenBudget'
  | 'checkpoint'
  | 'internalLlmCall'
  | 'permissionAsking'
  | 'agentPaused'
  | 'done'
  | 'maxTurnsReached'
  | 'error'

// ---- 各事件接口 ----

export interface TurnStartedEvent {
  type: 'turnStarted'
  turnCount: number
}

export interface TextDeltaEvent {
  type: 'textDelta'
  text: string
}

export interface ToolCallEvent {
  type: 'toolCall'
  toolCallId?: string
  toolName?: string
  toolInput?: Record<string, unknown>
}

export interface ToolResultEvent {
  type: 'toolResult'
  toolCallId?: string
  toolName?: string
  toolOutput?: string
  toolError?: boolean
}

export interface CompactBoundaryEvent {
  type: 'compactBoundary'
  summary?: string
}

export interface TurnCompleteEvent {
  type: 'turnComplete'
  turnCount: number
}

export interface TokenBudgetEvent {
  type: 'tokenBudget'
  budgetUsed?: number
  budgetTotal?: number
  budgetPercent?: number
}

export interface CheckpointEvent {
  type: 'checkpoint'
  sessionId?: string
  turnNumber?: number
}

export interface InternalLlmCallEvent {
  type: 'internalLlmCall'
  source?: string
  model?: string
  durationMs?: number
  success?: boolean
}

export interface PermissionAskingEvent {
  type: 'permissionAsking'
  replyId?: string
  pendingToolCalls?: string
}

export interface AgentPausedEvent {
  type: 'agentPaused'
  reason?: string
}

export interface DoneEvent {
  type: 'done'
}

export interface MaxTurnsReachedEvent {
  type: 'maxTurnsReached'
}

export interface ErrorEvent {
  type: 'error'
  errorMessage: string
}

// ---- 联合类型 ----

export type StreamEvent =
  | TurnStartedEvent
  | TextDeltaEvent
  | ToolCallEvent
  | ToolResultEvent
  | CompactBoundaryEvent
  | TurnCompleteEvent
  | TokenBudgetEvent
  | CheckpointEvent
  | InternalLlmCallEvent
  | PermissionAskingEvent
  | AgentPausedEvent
  | DoneEvent
  | MaxTurnsReachedEvent
  | ErrorEvent

// ---- 解析后的挂起工具调用 ----
export interface PendingToolCall {
  toolCallId: string
  toolName: string
  input?: Record<string, unknown>
  reason?: string
  state?: 'ASKING' | 'ALLOWED' | 'DENIED'
}

// ---- SSE 流控制句柄 ----
export interface StreamHandle {
  promise: Promise<void>
  cancel: () => void
}
