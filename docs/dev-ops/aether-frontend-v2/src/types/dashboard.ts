// ============================================
// Aether 仪表盘 / 统计类型定义
// ============================================

/** 仪表盘概览统计 */
export interface DashboardStats {
  /** 智能体总数 */
  totalAgents: number
  /** 活跃会话数 */
  activeSessions: number
  /** 总 Token 消耗 */
  totalTokens: number
  /** 总费用 */
  totalCost: number
  /** 系统运行时长（秒） */
  uptime: number
}

/** Token 用量时序数据点 */
export interface TokenUsagePoint {
  /** 日期 */
  date: string
  /** 输入 Token 数 */
  inputTokens: number
  /** 输出 Token 数 */
  outputTokens: number
  /** 当日费用 */
  cost: number
}

/** 会话活跃度数据点 */
export interface SessionActivityPoint {
  /** 小时（0-23） */
  hour: number
  /** 星期几（0-6） */
  day: number
  /** 会话数 */
  count: number
}

/** 模型用量数据点 */
export interface ModelUsagePoint {
  /** 模型标识 */
  model: string
  /** Token 消耗量 */
  tokens: number
  /** 费用 */
  cost: number
  /** 调用次数 */
  calls: number
}

/** 工具调用统计 */
export interface ToolCallStat {
  /** 工具名称 */
  toolName: string
  /** 调用次数 */
  count: number
  /** 成功率（0-1） */
  successRate: number
}

/** 智能体统计 */
export interface AgentStat {
  /** 智能体 ID */
  agentId: string
  /** 智能体名称 */
  agentName: string
  /** 智能体状态 */
  status: string
  /** 会话数 */
  sessionCount: number
  /** Token 消耗量 */
  tokenCount: number
}

/** 后端返回的仪表盘统计（与后端 DashboardStatsDTO 对齐） */
export interface DashboardStatsDTO {
  totalAgents: number
  activeSessions: number
  totalTokens: number
  totalCost: number
  agentStats: Array<{
    agentId: string
    agentName: string
    sessionCount: number
  }>
}

/** 系统健康状态 */
export interface SystemHealth {
  /** CPU 使用率（0-100） */
  cpuPercent: number
  /** 内存使用率（0-100） */
  memoryPercent: number
  /** 磁盘使用率（0-100） */
  diskPercent: number
  /** 每秒事务数 */
  tps: number
}
