// ============================================
// Aether 会话类型定义
// ============================================

/** 会话信息 */
export interface SessionInfo {
  /** 会话 ID */
  sessionId: string
  /** 关联的智能体 ID */
  agentId: string
  /** 智能体名称 */
  agentName: string
  /** 会话标题 */
  title: string
  /** 会话状态 */
  status: 'ACTIVE' | 'ARCHIVED' | 'ERROR'
  /** 创建时间（Unix 毫秒时间戳） */
  createdAt: number
  /** 最后更新时间（Unix 毫秒时间戳） */
  updatedAt?: number
}

/** 会话筛选条件 */
export interface SessionFilter {
  /** 按智能体 ID 筛选 */
  agentId?: string
  /** 按标题搜索 */
  search?: string
  /** 按状态筛选 */
  status?: 'ACTIVE' | 'ARCHIVED' | 'ERROR'
}
