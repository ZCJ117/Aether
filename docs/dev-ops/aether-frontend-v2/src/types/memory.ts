// ============================================
// Aether 记忆系统类型定义
// ============================================

/** 记忆记录 */
export interface MemoryRecord {
  /** 记录唯一标识 */
  id: string
  /** 记录标题 */
  title: string
  /** 记录内容 */
  content: string
  /** 记忆作用域 */
  scope: 'working' | 'short_term' | 'long_term'
  /** 记忆类型 */
  type: 'semantic' | 'episodic' | 'procedural'
  /** 父记录 ID（树形结构） */
  parentId?: string
  /** 从根到当前节点的路径 */
  path: string
  /** 创建时间（Unix 毫秒时间戳） */
  createdAt: number
  /** 最后更新时间（Unix 毫秒时间戳） */
  updatedAt: number
}

/** 记忆树节点 */
export interface MemoryTreeNode {
  /** 节点唯一标识 */
  id: string
  /** 节点显示标签 */
  label: string
  /** 子节点列表 */
  children?: MemoryTreeNode[]
  /** 关联的记忆记录（叶子节点） */
  record?: MemoryRecord
}
