// ============================================
// Aether 模型提供者与配置类型定义
// ============================================

/** 模型提供者 */
export interface ModelProvider {
  /** 提供者唯一标识 */
  id: string
  /** 提供者名称 */
  name: string
  /** API 基础地址 */
  baseUrl: string
}

/** 模型配置 */
export interface ModelConfig {
  /** 配置唯一标识 */
  id: string
  /** 关联的提供者 ID */
  providerId: string
  /** 模型 ID（如 gpt-4、deepseek-v3） */
  modelId: string
  /** API 密钥 */
  apiKey?: string
  /** API 基础地址（覆盖提供者默认值） */
  apiBase?: string
  /** 最大 Token 数 */
  maxTokens?: number
  /** 请求超时（毫秒） */
  timeout?: number
  /** 配置状态 */
  status: 'ACTIVE' | 'INACTIVE' | 'ERROR'
}

/** 模型连通性测试结果 */
export interface ModelTestResult {
  /** 测试是否成功 */
  success: boolean
  /** 延迟（毫秒） */
  latencyMs?: number
  /** 消耗 Token 数 */
  tokenCount?: number
  /** 错误信息（失败时） */
  error?: string
}
