import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { sendMessageStream, confirmToolCalls } from '@/api/chat'
import { fetchSessionMessages } from '@/api/session'
import type {
  TextDeltaEvent, ToolCallEvent, ToolResultEvent, CompactBoundaryEvent,
  TurnCompleteEvent, TokenBudgetEvent, CheckpointEvent, InternalLlmCallEvent,
  PermissionAskingEvent, AgentPausedEvent, DoneEvent, MaxTurnsReachedEvent,
  ErrorEvent, StreamHandle, PendingToolCall, StreamEvent,
} from '@/types/sse'
import type {
  ChatMessage, ToolCallState, TokenBudgetData,
  PermissionEventData, CompactBoundary, CheckpointInfo, LlmCallLog,
} from '@/types/chat'

export const useChatStore = defineStore('chat', () => {
  // ---- 消息（按会话分组缓存，切换会话时保留） ----
  const sessionMessages = ref<Record<string, ChatMessage[]>>({})
  const messages = ref<ChatMessage[]>([])
  const streamingMessages = ref<Map<string, string>>(new Map())

  function getSessionKey(): string {
    return sessionId.value || '__default__'
  }

  function switchToSession(sid: string | null): void {
    // 保存当前会话消息
    if (sessionId.value) {
      sessionMessages.value[sessionId.value] = [...messages.value]
    }
    sessionId.value = sid
    // 加载目标会话消息（优先缓存，否则从后端加载）
    const key = sid || '__default__'
    if (sessionMessages.value[key] && sessionMessages.value[key].length > 0) {
      messages.value = [...sessionMessages.value[key]]
    } else {
      messages.value = []
      if (sid) {
        loadHistoryFromBackend(sid)
      }
    }
    activeToolCalls.value.clear()
    permissionEvent.value = null
    compactBoundaries.value = []
    llmCallLogs.value = []
    turnCount.value = 0
    tokenBudget.value = { budgetUsed: 0, budgetTotal: 0, budgetPercent: 0 }
  }

  async function loadHistoryFromBackend(sid: string): Promise<void> {
    try {
      const raw = await fetchSessionMessages(sid)
      if (!raw || raw.length === 0) return
      const loaded: ChatMessage[] = raw
        .filter((m) => m.content && m.content.trim())
        .map((m) => ({
          id: crypto.randomUUID(),
          side: m.role === 'user' ? 'user' : 'agent',
          text: m.content,
          meta: {},
          streaming: false,
          timestamp: Date.now(),
        }))
      if (loaded.length > 0) {
        sessionMessages.value[sid] = loaded
        if (sessionId.value === sid) {
          messages.value = loaded
        }
      }
    } catch {
      // 后端不可用时静默失败，使用本地缓存
    }
  }

  // ---- 流状态 ----
  const isSending = ref(false)
  const statusText = ref('')
  const statusType = ref<'info' | 'error' | 'success' | 'warning'>('info')
  const currentStream = ref<StreamHandle | null>(null)
  const sessionId = ref<string | null>(null)

  // ---- SSE 事件状态 ----
  const activeToolCalls = ref<Map<string, ToolCallState>>(new Map())
  const tokenBudget = ref<TokenBudgetData>({ budgetUsed: 0, budgetTotal: 0, budgetPercent: 0 })
  const turnCount = ref(0)
  const maxTurns = ref(100)
  const permissionEvent = ref<PermissionEventData | null>(null)
  const compactBoundaries = ref<CompactBoundary[]>([])
  const checkpoint = ref<CheckpointInfo | null>(null)
  const llmCallLogs = ref<LlmCallLog[]>([])

  // ---- Getters ----
  const isEmpty = computed(() => messages.value.length === 0)
  const lastUserMessage = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].side === 'user') return messages.value[i]
    }
    return null
  })
  const latestToolCall = computed(() => {
    const calls = Array.from(activeToolCalls.value.values())
    return calls.length > 0 ? calls[calls.length - 1] : null
  })
  const hasActivePermissionRequest = computed(() => permissionEvent.value !== null)

  // ---- 消息操作 ----
  function addMessage(side: ChatMessage['side'], text: string, meta?: Record<string, unknown>): string {
    const id = crypto.randomUUID()
    messages.value.push({ id, side, text, meta: meta ?? {}, streaming: side === 'agent', timestamp: Date.now() })
    return id
  }

  function updateMessage(id: string, text: string, meta?: Record<string, unknown>): void {
    const msg = messages.value.find((m) => m.id === id)
    if (msg) {
      msg.text = text
      if (meta) msg.meta = { ...msg.meta, ...meta }
    }
  }

  function appendMessageDelta(id: string, delta: string): void {
    const msg = messages.value.find((m) => m.id === id)
    if (msg) msg.text += delta
  }

  function finalizeMessage(id: string): void {
    const msg = messages.value.find((m) => m.id === id)
    if (msg) msg.streaming = false
  }

  function clearMessages(): void {
    messages.value = []
    sessionMessages.value[getSessionKey()] = []
    activeToolCalls.value.clear()
    compactBoundaries.value = []
    llmCallLogs.value = []
    turnCount.value = 0
    permissionEvent.value = null
  }

  // ---- Status ----
  function setStatus(text: string, type: 'info' | 'error' | 'success' | 'warning' = 'info'): void {
    statusText.value = text
    statusType.value = type
  }

  // ---- 核心: SSE 流式对话 ----
  async function sendMessage(message: string, agentId: string, userId: string): Promise<void> {
    isSending.value = true
    setStatus('思考中...', 'info')
    addMessage('user', message)            // 用户消息加入消息列表
    const placeholderId = addMessage('agent', '')

    const stream = sendMessageStream(
      agentId,
      userId,
      sessionId.value,
      message,
      (event) => handleStreamEvent(event, placeholderId),
      (err) => {
        setStatus(err.message, 'error')
        isSending.value = false
        finalizeMessage(placeholderId)
      },
      () => {
        finalizeMessage(placeholderId)
        isSending.value = false
        setStatus('', 'info')
      }
    )
    currentStream.value = stream
    await stream.promise
  }

  function cancelStream(): void {
    currentStream.value?.cancel()
    isSending.value = false
  }

  // ---- 13 种 SSE 事件处理 ----
  function handleStreamEvent(event: StreamEvent, placeholderId: string): void {
    switch (event.type) {
      case 'textDelta':
        handleTextDelta(event as unknown as TextDeltaEvent, placeholderId)
        break
      case 'toolCall':
        handleToolCall(event as unknown as ToolCallEvent)
        break
      case 'toolResult':
        handleToolResult(event as unknown as ToolResultEvent)
        break
      case 'compactBoundary':
        handleCompactBoundary(event as unknown as CompactBoundaryEvent)
        break
      case 'turnComplete':
        handleTurnComplete(event as unknown as TurnCompleteEvent)
        break
      case 'tokenBudget':
        handleTokenBudget(event as unknown as TokenBudgetEvent)
        break
      case 'checkpoint':
        handleCheckpoint(event as unknown as CheckpointEvent)
        break
      case 'internalLlmCall':
        handleInternalLlmCall(event as unknown as InternalLlmCallEvent)
        break
      case 'permissionAsking':
        handlePermissionAsking(event as unknown as PermissionAskingEvent)
        break
      case 'agentPaused':
        handleAgentPaused(event as unknown as AgentPausedEvent)
        break
      case 'done':
        handleDone(event as unknown as DoneEvent)
        break
      case 'maxTurnsReached':
        handleMaxTurnsReached(event as unknown as MaxTurnsReachedEvent)
        break
      case 'error':
        handleError(event as unknown as ErrorEvent)
        break
    }
  }

  function handleTextDelta(event: TextDeltaEvent, placeholderId: string): void {
    appendMessageDelta(placeholderId, event.text)
  }

  function handleToolCall(event: ToolCallEvent): void {
    const id = event.toolCallId || crypto.randomUUID()
    activeToolCalls.value.set(id, {
      toolCallId: id,
      toolName: event.toolName || '',
      toolInput: event.toolInput,
      status: 'running',
    })
    setStatus(`调用工具: ${event.toolName}`, 'info')
  }

  function handleToolResult(event: ToolResultEvent): void {
    const id = event.toolCallId || ''
    const existing = activeToolCalls.value.get(id)
    if (existing) {
      existing.toolOutput = event.toolOutput
      existing.toolError = event.toolError
      existing.status = event.toolError ? 'error' : 'success'
    }
    setStatus(`工具完成: ${event.toolName}`, event.toolError ? 'warning' : 'success')
  }

  function handleCompactBoundary(event: CompactBoundaryEvent): void {
    compactBoundaries.value.push({ summary: event.summary || '', timestamp: Date.now() })
    updateMessage(messages.value[messages.value.length - 1]?.id || '', '', {
      compactBoundary: event.summary,
    })
  }

  function handleTurnComplete(event: TurnCompleteEvent): void {
    turnCount.value = event.turnCount
  }

  function handleTokenBudget(event: TokenBudgetEvent): void {
    tokenBudget.value = {
      budgetUsed: event.budgetUsed || 0,
      budgetTotal: event.budgetTotal || 0,
      budgetPercent: event.budgetPercent || 0,
    }
  }

  function handleCheckpoint(event: CheckpointEvent): void {
    checkpoint.value = {
      sessionId: event.sessionId || '',
      turnNumber: event.turnNumber || 0,
    }
  }

  function handleInternalLlmCall(event: InternalLlmCallEvent): void {
    llmCallLogs.value.push({
      source: event.source || 'unknown',
      model: event.model || 'unknown',
      durationMs: event.durationMs || 0,
      success: event.success ?? true,
      timestamp: Date.now(),
    })
  }

  function handlePermissionAsking(event: PermissionAskingEvent): void {
    let pending: PendingToolCall[] = []
    if (event.pendingToolCalls) {
      try {
        pending = typeof event.pendingToolCalls === 'string'
          ? JSON.parse(event.pendingToolCalls)
          : (event.pendingToolCalls as unknown as PendingToolCall[])
      } catch { /* ignore parse error */ }
    }
    permissionEvent.value = {
      replyId: event.replyId || '',
      pendingToolCalls: pending,
    }
    setStatus('等待工具调用确认...', 'warning')
  }

  function handleAgentPaused(event: AgentPausedEvent): void {
    setStatus(event.reason || 'Agent 已暂停', 'warning')
  }

  function handleDone(_event: DoneEvent): void {
    setStatus('完成', 'success')
  }

  function handleMaxTurnsReached(_event: MaxTurnsReachedEvent): void {
    setStatus('已达最大推理轮次', 'warning')
  }

  function handleError(event: ErrorEvent): void {
    setStatus(event.errorMessage, 'error')
  }

  // ---- 权限确认 ----
  async function confirmPermission(
    agentId: string,
    userId: string,
    sid: string,
    results: { toolCallId: string; approved: boolean }[]
  ): Promise<void> {
    await confirmToolCalls(agentId, userId, sid, results)
    permissionEvent.value = null
  }

  async function denyAllPermissions(
    agentId: string,
    userId: string,
    sid: string
  ): Promise<void> {
    if (!permissionEvent.value) return
    const results = permissionEvent.value.pendingToolCalls.map((tc) => ({
      toolCallId: tc.toolCallId,
      approved: false,
    }))
    await confirmPermission(agentId, userId, sid, results)
  }

  return {
    messages,
    sessionMessages,
    isSending,
    statusText,
    statusType,
    sessionId,
    currentStream,
    activeToolCalls,
    tokenBudget,
    turnCount,
    maxTurns,
    permissionEvent,
    compactBoundaries,
    checkpoint,
    llmCallLogs,
    isEmpty,
    lastUserMessage,
    latestToolCall,
    hasActivePermissionRequest,
    addMessage,
    updateMessage,
    appendMessageDelta,
    finalizeMessage,
    clearMessages,
    setStatus,
    sendMessage,
    cancelStream,
    switchToSession,
    confirmPermission,
    denyAllPermissions,
  }
})
