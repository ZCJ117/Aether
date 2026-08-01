// ============================================
// Aether 长期记忆类型定义
// ============================================

/** 长期记忆条目（按 Agent 隔离，localStorage 持久化） */
export interface LongTermMemoryEntry {
  /** 条目唯一标识 */
  id: string
  /** 记忆内容（纯文本） */
  content: string
  /** 创建时间（Unix 毫秒时间戳） */
  createdAt: number
  /** 最后更新时间（Unix 毫秒时间戳） */
  updatedAt: number
}
