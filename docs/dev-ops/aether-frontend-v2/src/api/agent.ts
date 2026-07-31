import { api } from './client'
import type { AiAgentConfigDTO } from '@/types/api'
import type { AgentFullConfig, AgentFormData } from '@/types/agent'

const BASE = '/api/v1'

// ---- 核心端点 (已实现) ----

export function fetchAgents(): Promise<AiAgentConfigDTO[]> {
  return api.get<AiAgentConfigDTO[]>(`${BASE}/query_ai_agent_config_list`)
}

// ---- 管理端点 (mock 占位) ----

let mockAgents: AgentFullConfig[] = [
  {
    agentId: '000001',
    agentName: '旅游规划智能体',
    agentDesc: '多工具协同的旅游规划助手',
    agentType: 're_act',
    instruction: '你是一个专业的旅游规划助手...',
    outputKey: 'travel_plan',
    modelRef: 'deepseek-v4',
    toolNames: ['web_search', 'weather_query', 'route_planner'],
    checkpointEnabled: true,
    checkpointInterval: 5,
    maxTurns: 100,
    status: 'active',
  },
  {
    agentId: '000002',
    agentName: '代码审查助手',
    agentDesc: '专注于代码质量审查的智能体',
    agentType: 're_act',
    instruction: '你是一个代码审查专家...',
    outputKey: 'review_report',
    modelRef: 'deepseek-v4',
    toolNames: ['file_read', 'code_analyze', 'git_diff'],
    checkpointEnabled: true,
    checkpointInterval: 5,
    maxTurns: 50,
    status: 'active',
  },
]

export async function fetchManagementAgents(): Promise<AgentFullConfig[]> {
  return Promise.resolve([...mockAgents])
}

export async function fetchAgentDetail(id: string): Promise<AgentFullConfig | null> {
  const found = mockAgents.find((a) => a.agentId === id)
  return Promise.resolve(found ?? null)
}

export async function createAgent(data: AgentFormData): Promise<AgentFullConfig> {
  const agent: AgentFullConfig = {
    agentId: crypto.randomUUID(),
    agentName: data.agentName,
    agentDesc: data.agentDesc || '',
    agentType: data.agentType || 're_act',
    instruction: data.instruction || '',
    outputKey: data.outputKey || '',
    modelRef: data.modelRef || '',
    toolNames: data.toolNames || [],
    checkpointEnabled: true,
    checkpointInterval: 5,
    maxTurns: data.maxTurns || 100,
    status: 'active',
  }
  mockAgents.push(agent)
  return Promise.resolve(agent)
}

export async function updateAgent(
  id: string,
  data: Partial<AgentFormData>
): Promise<AgentFullConfig | null> {
  const idx = mockAgents.findIndex((a) => a.agentId === id)
  if (idx < 0) return Promise.resolve(null)
  mockAgents[idx] = { ...mockAgents[idx], ...data }
  return Promise.resolve(mockAgents[idx])
}

export async function deleteAgent(id: string): Promise<boolean> {
  const idx = mockAgents.findIndex((a) => a.agentId === id)
  if (idx < 0) return Promise.resolve(false)
  mockAgents.splice(idx, 1)
  return Promise.resolve(true)
}
