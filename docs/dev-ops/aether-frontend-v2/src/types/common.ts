// ============================================
// Aether 通用类型 — 分页 / 排序 / 过滤
// ============================================

/** 分页状态 */
export interface PaginationState {
  /** 当前页码（从 1 开始） */
  page: number
  /** 每页条目数 */
  pageSize: number
  /** 总条数 */
  total: number
}

/** 排序状态 */
export interface SortState {
  /** 排序字段 */
  sortBy: string
  /** 排序方向 */
  sortOrder: 'asc' | 'desc'
}

/** 过滤选项 */
export interface FilterOption {
  /** 过滤字段 key */
  key: string
  /** 显示标签 */
  label: string
  /** 可选值列表 */
  options: { label: string; value: string }[]
}
