// ============================================
// Aether 对话 / 聊天类型定义
// ============================================

import type { PendingToolCall } from './sse'

/** 消息来源方 */
export type MessageSide = 'user' | 'agent' | 'system'

/** 聊天消息 */
export interface ChatMessage {
  /** 消息唯一标识 */
  id: string
  /** 消息来源方 */
  side: MessageSide
  /** 消息文本内容 */
  text: string
  /** 附加元数据 */
  meta?: Record<string, unknown>
  /** 是否正在流式生成 */
  streaming?: boolean
  /** 消息时间戳（Unix 毫秒时间戳） */
  timestamp?: number
}

/** 工具调用状态 */
export interface ToolCallState {
  /** 工具调用 ID */
  toolCallId: string
  /** 工具名称 */
  toolName: string
  /** 工具输入参数 */
  toolInput?: Record<string, unknown>
  /** 工具输出结果 */
  toolOutput?: string
  /** 工具调用错误信息 */
  toolError?: boolean
  /** 调用耗时（毫秒） */
  durationMs?: number
  /** 工具调用状态 */
  status: 'running' | 'success' | 'error'
}

/** Token 预算数据 */
export interface TokenBudgetData {
  /** 已使用 Token 数 */
  budgetUsed: number
  /** Token 预算总额 */
  budgetTotal: number
  /** 预算使用百分比（0-100） */
  budgetPercent: number
}

/** 权限确认事件数据 */
export interface PermissionEventData {
  /** 回复 ID（用于批准 / 拒绝） */
  replyId: string
  /** 待确认的工具调用列表 */
  pendingToolCalls: PendingToolCall[]
}

/** 上下文压缩边界标记 */
export interface CompactBoundary {
  /** 压缩摘要文本 */
  summary: string
  /** 标记时间戳 */
  timestamp: number
}

/** 检查点信息 */
export interface CheckpointInfo {
  /** 会话 ID */
  sessionId: string
  /** 对话回合数 */
  turnNumber: number
}

/** LLM 调用日志 */
export interface LlmCallLog {
  /** 调用来源 */
  source: string
  /** 模型名称 */
  model: string
  /** 耗时（毫秒） */
  durationMs: number
  /** 是否成功 */
  success: boolean
  /** 时间戳 */
  timestamp: number
}
