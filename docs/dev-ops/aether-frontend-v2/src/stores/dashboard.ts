import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TokenUsagePoint, SessionActivityPoint, ToolCallStat, AgentStat, SystemHealth } from '@/types/dashboard'

export const useDashboardStore = defineStore('dashboard', () => {
  const totalAgents = ref(2)
  const activeSessions = ref(5)
  const totalTokens = ref(125000)
  const totalCost = ref(2.45)
  const isLoading = ref(false)
  const period = ref<'7d' | '30d' | '90d'>('7d')
  let refreshTimer: ReturnType<typeof setInterval> | null = null

  const tokenUsage = ref<TokenUsagePoint[]>(generateTokenData())
  const sessionActivity = ref<SessionActivityPoint[]>(generateSessionData())
  const toolCallStats = ref<ToolCallStat[]>([
    { toolName: 'web_search', count: 145, successRate: 0.95 },
    { toolName: 'weather_query', count: 89, successRate: 0.98 },
    { toolName: 'file_read', count: 67, successRate: 0.99 },
    { toolName: 'code_analyze', count: 42, successRate: 0.91 },
  ])
  const agentStats = ref<AgentStat[]>([
    { agentId: '000001', agentName: '旅游规划智能体', status: 'active', sessionCount: 12, tokenCount: 85000 },
    { agentId: '000002', agentName: '代码审查助手', status: 'active', sessionCount: 8, tokenCount: 40000 },
  ])
  const systemHealth = ref<SystemHealth>({ cpuPercent: 23, memoryPercent: 45, diskPercent: 32, tps: 12 })

  async function loadStats(): Promise<void> {
    isLoading.value = true
    await new Promise((r) => setTimeout(r, 300))
    isLoading.value = false
  }

  function startAutoRefresh(ms: number): void {
    stopAutoRefresh()
    refreshTimer = setInterval(() => loadStats(), ms)
  }

  function stopAutoRefresh(): void {
    if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null }
  }

  function setPeriod(p: '7d' | '30d' | '90d'): void {
    period.value = p
    tokenUsage.value = generateTokenData()
  }

  return {
    totalAgents, activeSessions, totalTokens, totalCost,
    isLoading, period, tokenUsage, sessionActivity, toolCallStats, agentStats, systemHealth,
    loadStats, startAutoRefresh, stopAutoRefresh, setPeriod,
  }
})

function generateTokenData(): TokenUsagePoint[] {
  const now = Date.now()
  return Array.from({ length: 30 }, (_, i) => ({
    date: new Date(now - (29 - i) * 86400000).toISOString().slice(0, 10),
    inputTokens: Math.floor(Math.random() * 5000) + 1000,
    outputTokens: Math.floor(Math.random() * 2000) + 500,
    cost: Math.random() * 0.5 + 0.01,
  }))
}

function generateSessionData(): SessionActivityPoint[] {
  return Array.from({ length: 24 }, (_, i) => ({
    hour: i,
    day: Date.now(),
    count: Math.floor(Math.random() * 10),
  }))
}
