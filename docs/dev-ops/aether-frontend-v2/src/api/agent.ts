import { api } from './client'
import type { AiAgentConfigDTO } from '@/types/api'
import type { AgentFullConfig, AgentFormData } from '@/types/agent'

const BASE = '/api/v1'

// ---- 核心端点 (已实现) ----

export function fetchAgents(): Promise<AiAgentConfigDTO[]> {
  return api.get<AiAgentConfigDTO[]>(`${BASE}/query_ai_agent_config_list`)
}

// ---- 管理端点 (调用真实后端 API) ----

export async function fetchManagementAgents(): Promise<AgentFullConfig[]> {
  const list = await fetchAgents()
  return list.map((a) => ({
    ...a,
    agentType: 're_act',
    instruction: '',
    outputKey: '',
    modelRef: a.modelRef || '—',
    toolNames: [],
    checkpointEnabled: true,
    checkpointInterval: 5,
    maxTurns: 100,
    status: 'active' as const,
  }))
}

export async function fetchAgentDetail(_id: string): Promise<AgentFullConfig | null> {
  // 管理端详情暂未实现后端接口，返回 null 表示不支持
  return Promise.resolve(null)
}

export async function createAgent(_data: AgentFormData): Promise<AgentFullConfig> {
  throw new Error('创建智能体暂不支持，请通过 YAML 配置文件添加')
}

export async function updateAgent(
  _id: string,
  _data: Partial<AgentFormData>
): Promise<AgentFullConfig | null> {
  throw new Error('编辑智能体暂不支持，请通过 YAML 配置文件修改')
}

export async function deleteAgent(_id: string): Promise<boolean> {
  throw new Error('删除智能体暂不支持，请通过 YAML 配置文件操作')
}
