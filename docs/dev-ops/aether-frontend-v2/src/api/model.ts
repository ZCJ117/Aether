import type { ModelProvider, ModelConfig, ModelTestResult } from '@/types/model'

// ---- Mock 数据 ----
const mockProviders: ModelProvider[] = [
  { id: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com' },
  { id: 'anthropic', name: 'Anthropic', baseUrl: 'https://api.anthropic.com' },
  { id: 'dashscope', name: 'DashScope (阿里云)', baseUrl: 'https://dashscope.aliyuncs.com' },
  { id: 'deepseek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com' },
]

const mockModels: ModelConfig[] = [
  { id: 'm1', providerId: 'deepseek', modelId: 'deepseek-v4-pro', status: 'ACTIVE' },
  { id: 'm2', providerId: 'deepseek', modelId: 'deepseek-v4-flash', status: 'ACTIVE' },
  { id: 'm3', providerId: 'openai', modelId: 'gpt-4o', status: 'ACTIVE' },
  { id: 'm4', providerId: 'anthropic', modelId: 'claude-sonnet-4', status: 'ACTIVE' },
]

export async function fetchModels(): Promise<ModelConfig[]> {
  return Promise.resolve([...mockModels])
}

export async function fetchProviders(): Promise<ModelProvider[]> {
  return Promise.resolve([...mockProviders])
}

export async function createModel(config: ModelConfig): Promise<ModelConfig> {
  mockModels.push(config)
  return Promise.resolve(config)
}

export async function updateModel(id: string, data: Partial<ModelConfig>): Promise<ModelConfig | null> {
  const idx = mockModels.findIndex((m) => m.id === id)
  if (idx < 0) return Promise.resolve(null)
  mockModels[idx] = { ...mockModels[idx], ...data }
  return Promise.resolve(mockModels[idx])
}

export async function deleteModel(id: string): Promise<boolean> {
  const idx = mockModels.findIndex((m) => m.id === id)
  if (idx < 0) return Promise.resolve(false)
  mockModels.splice(idx, 1)
  return Promise.resolve(true)
}

export async function testModelConnection(_id: string): Promise<ModelTestResult> {
  return Promise.resolve({
    success: Math.random() > 0.2,
    latencyMs: Math.floor(Math.random() * 2000) + 200,
    tokenCount: Math.floor(Math.random() * 500),
  })
}
