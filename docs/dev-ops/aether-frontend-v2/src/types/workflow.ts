// ============================================
// Aether 工作流编排类型定义
// ============================================

import type { Node, Edge } from '@vue-flow/core'

/** 工作流节点数据类型 */
export interface WorkflowNodeData {
  /** 节点类型 */
  type: 'start' | 'agent' | 'condition' | 'merge' | 'end' | 'loop'
  /** 节点显示标签 */
  label: string
  /** 关联的智能体 ID */
  agentId?: string
  /** 智能体指令 */
  instruction?: string
  /** 输出键名 */
  outputKey?: string
  /** 条件表达式 */
  condition?: string
  /** 最大迭代次数（循环节点） */
  maxIterations?: number
}

/** 工作流边数据类型 */
export interface WorkflowEdgeData {
  /** 边类型 */
  type: 'default' | 'condition'
  /** 条件匹配结果 */
  conditionResult?: string
  /** 边显示标签 */
  label?: string
}

/** 工作流节点（基于 @vue-flow/core） */
export type WorkflowNode = Node<WorkflowNodeData>

/** 工作流边（基于 @vue-flow/core） */
export type WorkflowEdge = Edge<WorkflowEdgeData>

/** 工作流摘要信息 */
export interface WorkflowSummary {
  /** 工作流唯一标识 */
  id: string
  /** 工作流名称 */
  name: string
  /** 工作流描述 */
  description: string
  /** 节点数量 */
  nodeCount: number
  /** 边数量 */
  edgeCount: number
  /** 最后更新时间 */
  updatedAt: string
}

/** 工作流完整定义 */
export interface WorkflowDefinition {
  /** 工作流唯一标识 */
  id: string
  /** 工作流名称 */
  name: string
  /** 工作流描述 */
  description: string
  /** 节点列表 */
  nodes: WorkflowNodeData[]
  /** 边列表 */
  edges: WorkflowEdgeData[]
}

/** DAG 有效性校验结果 */
export interface DAGValidationResult {
  /** 是否有效 */
  valid: boolean
  /** 校验错误列表 */
  errors: DAGValidationError[]
}

/** 单个 DAG 校验错误 */
export interface DAGValidationError {
  /** 关联的节点 ID */
  nodeId?: string
  /** 错误描述 */
  message: string
}
