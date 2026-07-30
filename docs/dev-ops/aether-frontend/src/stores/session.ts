import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchSessions } from '@/api/session'
import type { SessionItemDTO } from '@/api/types'

export interface SessionInfo {
  sessionId: string
  agentId: string
  agentName: string
  title: string
  createdAt: number
}

const STORAGE_KEY = 'aether_sessions_v2'

export const useSessionStore = defineStore('session', () => {
  /** 按 agentId 分组的会话 */
  const sessionsByAgent = ref<Record<string, SessionInfo[]>>({})
  const currentSessionId = ref<string | null>(null)
  const isLoading = ref(false)

  const currentSession = computed(() => {
    if (!currentSessionId.value) return null
    for (const list of Object.values(sessionsByAgent.value)) {
      const found = list.find(s => s.sessionId === currentSessionId.value)
      if (found) return found
    }
    return null
  })

  /** 获取指定 Agent 的会话列表 */
  function getSessionsForAgent(agentId: string): SessionInfo[] {
    return sessionsByAgent.value[agentId] ?? []
  }

  /** 从后端加载指定 Agent 的会话列表 */
  async function loadSessions(agentId: string, userId: string): Promise<void> {
    isLoading.value = true
    try {
      const dtos: SessionItemDTO[] = await fetchSessions(agentId, userId)
      const infos: SessionInfo[] = dtos.map(dto => ({
        sessionId: dto.sessionId,
        agentId: dto.agentId,
        agentName: '',
        title: dto.title || '新对话',
        createdAt: new Date(dto.createdAt).getTime()
      }))
      sessionsByAgent.value[agentId] = infos
      saveToStorage()
    } catch {
      // 后端不可用时回退 localStorage
      loadFromStorage()
    } finally {
      isLoading.value = false
    }
  }

  /** 添加新会话到指定 Agent */
  function addSessionToAgent(session: SessionInfo): void {
    const list = sessionsByAgent.value[session.agentId]
    if (list) {
      list.unshift(session)
    } else {
      sessionsByAgent.value[session.agentId] = [session]
    }
    currentSessionId.value = session.sessionId
    saveToStorage()
  }

  /** 删除会话 */
  function deleteSession(sessionId: string, agentId: string): void {
    const list = sessionsByAgent.value[agentId]
    if (list) {
      sessionsByAgent.value[agentId] = list.filter(s => s.sessionId !== sessionId)
      if (sessionsByAgent.value[agentId].length === 0) {
        delete sessionsByAgent.value[agentId]
      }
    }
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null
    }
    saveToStorage()
  }

  /** 切换当前会话 */
  function switchSession(sessionId: string): void {
    currentSessionId.value = sessionId
  }

  /** 更新会话标题 */
  function updateSessionTitle(sessionId: string, title: string): void {
    for (const list of Object.values(sessionsByAgent.value)) {
      const session = list.find(s => s.sessionId === sessionId)
      if (session) {
        session.title = title
        saveToStorage()
        return
      }
    }
  }

  function clearAll(): void {
    sessionsByAgent.value = {}
    currentSessionId.value = null
    saveToStorage()
  }

  // -- localStorage persistence (offline cache) --
  function loadFromStorage() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) {
        const data = JSON.parse(raw)
        sessionsByAgent.value = data.sessionsByAgent ?? {}
        currentSessionId.value = data.currentSessionId ?? null
      }
    } catch { /* ignore */ }
  }

  function saveToStorage() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        sessionsByAgent: sessionsByAgent.value,
        currentSessionId: currentSessionId.value
      }))
    } catch { /* ignore */ }
  }

  // Auto-load on store creation
  loadFromStorage()

  return {
    sessionsByAgent,
    currentSessionId,
    currentSession,
    isLoading,
    getSessionsForAgent,
    loadSessions,
    addSessionToAgent,
    deleteSession,
    switchSession,
    updateSessionTitle,
    clearAll
  }
})
