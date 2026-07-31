// ============================================
// Aether 技能 / 工具类型定义
// ============================================

/** 技能 / 工具 */
export interface Skill {
  /** 技能唯一标识 */
  id: string
  /** 技能名称 */
  name: string
  /** 技能描述 */
  description: string
  /** 所属分类 */
  category: string
  /** 来源（如 mcp server 名称） */
  source: string
  /** 技能类型 */
  type: 'mcp' | 'resource' | 'directory'
  /** 是否启用 */
  enabled: boolean
  /** 版本号 */
  version?: string
}

/** 技能分类 */
export interface SkillCategory {
  /** 分类唯一标识 */
  id: string
  /** 分类名称 */
  name: string
  /** 该分类下的技能数量 */
  count: number
}

/** 技能筛选条件 */
export interface SkillFilter {
  /** 按分类筛选 */
  category?: string
  /** 按来源筛选 */
  source?: string
  /** 按启用状态筛选 */
  enabled?: boolean
}
