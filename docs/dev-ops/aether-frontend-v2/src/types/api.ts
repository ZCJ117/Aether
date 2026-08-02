// ============================================
// Aether API 通用类型定义
// ============================================

/** 后端统一响应包络 */
export interface ApiResponse<T = unknown> {
  code: string
  info: string
  data: T
}

/** 响应码常量 */
export const ResponseCode = {
  SUCCESS: '0000',
  UN_ERROR: '0001',
  ILLEGAL_PARAM: '0002',
  NO_METHOD: '0003',
  AGENT_NOT_FOUND: 'E0001',
  MCP_NOT_LOADABLE: 'E0002',
} as const

export type ResponseCodeValue = (typeof ResponseCode)[keyof typeof ResponseCode]

/** API 错误 */
export class ApiError extends Error {
  code: string
  status?: number
  payload?: unknown

  constructor(message: string, code: string, status?: number, payload?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.payload = payload
  }
}

// ============================================
// 智能体 DTO
// ============================================

/** 智能体配置列表项 */
export interface AiAgentConfigDTO {
  agentId: string
  agentName: string
  agentDesc: string
  modelRef?: string
}

// ============================================
// 会话 DTO
// ============================================

/** 会话列表项 */
export interface SessionItemDTO {
  sessionId: string
  agentId: string
  userId: string
  title: string
  status: 'ACTIVE' | 'ARCHIVED' | 'ERROR'
  createdAt: string
  updatedAt: string
}

/** 创建会话请求 */
export interface CreateSessionRequestDTO {
  agentId: string
  userId: string
}

/** 创建会话响应 */
export interface CreateSessionResponseDTO {
  sessionId: string
}

// ============================================
// 对话 DTO
// ============================================

/** 对话请求 */
export interface ChatRequestDTO {
  agentId: string
  userId: string
  sessionId?: string
  message: string
}

/** 对话响应 */
export interface ChatResponseDTO {
  content: string
}

/** H4 权限确认请求 */
export interface ConfirmRequestDTO {
  agentId: string
  userId: string
  sessionId: string
  confirmResults: ConfirmResultDTO[]
}

/** 单个确认结果 */
export interface ConfirmResultDTO {
  toolCallId: string
  approved: boolean
}
