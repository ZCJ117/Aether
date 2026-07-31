// ============================================
// Aether 智能体类型定义
// ============================================

import type { AiAgentConfigDTO } from './api'

/** 智能体完整配置（扩展自后端 DTO） */
export interface AgentFullConfig extends AiAgentConfigDTO {
  /** 智能体类型 */
  agentType: string
  /** 系统指令 / prompt */
  instruction: string
  /** 输出键名 */
  outputKey: string
  /** 模型引用 */
  modelRef: string
  /** 绑定的工具名称列表 */
  toolNames: string[]
  /** 是否启用检查点 */
  checkpointEnabled: boolean
  /** 检查点间隔（回合数） */
  checkpointInterval: number
  /** 最大回合数 */
  maxTurns: number
  /** 智能体状态 */
  status: 'active' | 'inactive' | 'error'
  /** 模型名称（显示用） */
  modelName?: string
  /** 生效的模型 ID */
  effectiveModelId?: string
  /** 温度参数 */
  floatTemperature?: number
  /** Top-P 参数 */
  floatTopP?: number
  /** 最大输出 Token 数 */
  intMaxOutputTokens?: number
  /** 每次交互最大推理轮次 */
  maxTurnsPerInteraction?: number
  /** Token 预算 */
  intTokenBudget?: number
  /** 工具列表 */
  tools?: string[]
  /** 是否自动授予工具权限 */
  autoGrantToolPermissions?: boolean
  /** 是否允许所有工具 */
  allowAllTools?: boolean
  /** 是否允许写入操作 */
  allowWriteOperations?: boolean
}

/** 智能体筛选条件 */
export interface AgentFilter {
  /** 按状态筛选 */
  status?: 'active' | 'inactive' | 'error'
  /** 按类型筛选 */
  type?: string
  /** 按名称 / 描述搜索 */
  search?: string
}

/** 智能体创建 / 编辑表单数据 */
export interface AgentFormData {
  /** 智能体名称 */
  agentName: string
  /** 智能体描述 */
  agentDesc: string
  /** 智能体类型 */
  agentType: string
  /** 系统指令 */
  instruction: string
  /** 输出键名 */
  outputKey: string
  /** 模型引用 */
  modelRef: string
  /** 绑定的工具名称列表 */
  toolNames: string[]
  /** 是否启用检查点 */
  checkpointEnabled: boolean
  /** 检查点间隔（回合数） */
  checkpointInterval: number
  /** 最大回合数 */
  maxTurns: number
}

/** 工具绑定信息 */
export interface ToolBinding {
  /** 工具名称 */
  toolName: string
  /** 工具类型 */
  toolType: 'mcp' | 'skill' | 'builtin'
  /** 工具来源标识 */
  source?: string
}
