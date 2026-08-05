import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { SessionInfo } from '@/types/session'
import { fetchSessions, createSession, deleteSession as deleteSessionApi } from '@/api/session'

export const useSessionStore = defineStore('session', () => {
  const sessionsByAgent = ref<Record<string, SessionInfo[]>>({})
  const currentSessionId = ref<string | null>(null)
  const isLoading = ref(false)
  const searchQuery = ref('')
  const sortOrder = ref<'latest' | 'oldest' | 'name'>('latest')

  const currentSession = computed(() => {
    if (!currentSessionId.value) return null
    for (const sessions of Object.values(sessionsByAgent.value)) {
      const found = sessions.find((s) => s.sessionId === currentSessionId.value)
      if (found) return found
    }
    return null
  })

  function filteredSessions(agentId: string): SessionInfo[] {
    const sessions = sessionsByAgent.value[agentId] || []
    let result = [...sessions]
    if (searchQuery.value) {
      const q = searchQuery.value.toLowerCase()
      result = result.filter((s) => s.title.toLowerCase().includes(q))
    }
    result.sort((a, b) => {
      if (sortOrder.value === 'latest') return b.createdAt - a.createdAt
      if (sortOrder.value === 'oldest') return a.createdAt - b.createdAt
      return a.title.localeCompare(b.title)
    })
    return result
  }

  // 防止并发重复请求
  const pendingRequests = new Map<string, Promise<void>>()

  async function loadSessions(agentId: string, userId: string): Promise<void> {
    if (!agentId || !userId) return
    // 如果已有相同 agentId 的请求在进行中，直接复用
    const key = `${agentId}:${userId}`
    if (pendingRequests.has(key)) {
      console.log('[session] loadSessions 跳过重复请求:', key)
      return pendingRequests.get(key)!
    }

    const promise = (async () => {
      isLoading.value = true
      try {
        const data = await fetchSessions(agentId, userId)
        console.log('[session] loadSessions API返回:', { agentId, userId, count: data.length, data })
        sessionsByAgent.value[agentId] = data.map((item) => ({
          sessionId: item.sessionId,
          agentId: item.agentId,
          agentName: '',
          title: item.title || '新对话',
          status: item.status,
          createdAt: new Date(item.createdAt).getTime(),
          updatedAt: new Date(item.updatedAt).getTime(),
        }))
        console.log('[session] sessionsByAgent 已更新:', agentId, '→', sessionsByAgent.value[agentId]?.length, '条')
      } catch (err) {
        console.error('[session] loadSessions 失败:', err)
      } finally {
        isLoading.value = false
        pendingRequests.delete(key)
      }
    })()

    pendingRequests.set(key, promise)
    return promise
  }

  async function newSession(agentId: string, userId: string): Promise<SessionInfo> {
    const resp = await createSession(agentId, userId)
    const session: SessionInfo = {
      sessionId: resp.sessionId,
      agentId,
      agentName: '',
      title: '新对话',
      status: 'ACTIVE',
      createdAt: Date.now(),
      updatedAt: Date.now(),
    }
    if (!sessionsByAgent.value[agentId]) {
      sessionsByAgent.value[agentId] = []
    }
    sessionsByAgent.value[agentId].unshift(session)
    currentSessionId.value = session.sessionId
    return session
  }

  function switchSession(sessionId: string): void {
    if (sessionId === currentSessionId.value) return
    currentSessionId.value = sessionId
  }

  async function deleteSession(sessionId: string, agentId: string): Promise<void> {
    // 先本地移除（即时 UI 反馈），后端异步软删除
    const sessions = sessionsByAgent.value[agentId]
    if (!sessions) return
    sessionsByAgent.value[agentId] = sessions.filter((s) => s.sessionId !== sessionId)
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null
    }
    try {
      await deleteSessionApi(sessionId)
    } catch {
      // 后端删除失败暂不回滚 UI，后续可通过 loadSessions 同步
    }
  }

  function updateSessionTitle(sessionId: string, title: string): void {
    for (const sessions of Object.values(sessionsByAgent.value)) {
      const found = sessions.find((s) => s.sessionId === sessionId)
      if (found) {
        found.title = title
        break
      }
    }
  }

  function clearAll(): void {
    sessionsByAgent.value = {}
    currentSessionId.value = null
    searchQuery.value = ''
  }

  return {
    sessionsByAgent,
    currentSessionId,
    isLoading,
    searchQuery,
    sortOrder,
    currentSession,
    filteredSessions,
    loadSessions,
    newSession,
    switchSession,
    deleteSession,
    updateSessionTitle,
    clearAll,
  }
})
