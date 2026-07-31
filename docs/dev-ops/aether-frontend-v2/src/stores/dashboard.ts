import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchDashboardStats } from '@/api/dashboard'
import type { TokenUsagePoint, SessionActivityPoint, ToolCallStat, AgentStat, SystemHealth } from '@/types/dashboard'

export const useDashboardStore = defineStore('dashboard', () => {
  const totalAgents = ref(0)
  const activeSessions = ref(0)
  const totalTokens = ref(0)
  const totalCost = ref(0)
  const isLoading = ref(false)
  const loadError = ref<string | null>(null)
  const period = ref<'7d' | '30d' | '90d'>('7d')
  let refreshTimer: ReturnType<typeof setInterval> | null = null

  // 图表数据暂用 mock（Token 追踪表尚未实现）
  const tokenUsage = ref<TokenUsagePoint[]>(generateTokenData())
  const sessionActivity = ref<SessionActivityPoint[]>(generateSessionData())
  const toolCallStats = ref<ToolCallStat[]>([
    { toolName: 'web_search', count: 145, successRate: 0.95 },
    { toolName: 'weather_query', count: 89, successRate: 0.98 },
    { toolName: 'file_read', count: 67, successRate: 0.99 },
    { toolName: 'code_analyze', count: 42, successRate: 0.91 },
  ])
  const agentStats = ref<AgentStat[]>([])
  const systemHealth = ref<SystemHealth>({ cpuPercent: 23, memoryPercent: 45, diskPercent: 32, tps: 12 })

  async function loadStats(): Promise<void> {
    isLoading.value = true
    loadError.value = null
    try {
      const stats = await fetchDashboardStats()
      totalAgents.value = stats.totalAgents
      activeSessions.value = stats.activeSessions
      totalTokens.value = stats.totalTokens
      totalCost.value = stats.totalCost
      agentStats.value = stats.agentStats.map((a) => ({
        agentId: a.agentId,
        agentName: a.agentName,
        status: a.sessionCount > 0 ? 'active' : 'idle',
        sessionCount: a.sessionCount,
        tokenCount: 0, // Token 追踪表未实现
      }))
    } catch (err) {
      loadError.value = (err as Error).message || '加载失败'
    } finally {
      isLoading.value = false
    }
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
    isLoading, loadError, period, tokenUsage, sessionActivity, toolCallStats, agentStats, systemHealth,
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
