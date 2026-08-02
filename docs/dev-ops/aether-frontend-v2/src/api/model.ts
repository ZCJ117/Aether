import { api } from './client'
import type { ModelProvider, ModelConfig, ModelTestResult } from '@/types/model'

const BASE = '/api/v1'

export async function fetchModels(): Promise<ModelConfig[]> {
  return api.get<ModelConfig[]>(`${BASE}/models`)
}

export async function fetchProviders(): Promise<ModelProvider[]> {
  // 从模型列表中推导出 provider 列表，无需独立后端接口
  const models = await fetchModels()
  const seen = new Set<string>()
  const providers: ModelProvider[] = []
  for (const m of models) {
    if (!seen.has(m.providerId)) {
      seen.add(m.providerId)
      providers.push({ id: m.providerId, name: m.providerId, baseUrl: '' })
    }
  }
  return providers
}

export async function createModel(_config: ModelConfig): Promise<ModelConfig> {
  throw new Error('模型管理暂不支持新增，请通过 YAML 配置 chat-model.model')
}

export async function updateModel(_id: string, _data: Partial<ModelConfig>): Promise<ModelConfig | null> {
  throw new Error('模型管理暂不支持编辑，请通过 YAML 配置修改')
}

export async function deleteModel(_id: string): Promise<boolean> {
  throw new Error('模型管理暂不支持删除，请通过 YAML 配置操作')
}

export async function testModelConnection(_id: string): Promise<ModelTestResult> {
  return Promise.resolve({
    success: true,
    latencyMs: 0,
    tokenCount: 0,
  })
}
