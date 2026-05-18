import apiClient from './client'

/**
 * POST /api/v1/chat (阻塞式)
 * Body: { agentId, userId, sessionId, message }
 * Returns: { content }
 */
export function sendMessage(agentId, userId, sessionId, message) {
  return apiClient.post('/api/v1/chat', {
    agentId,
    userId,
    sessionId,
    message
  })
}

/**
 * POST /api/v1/chat_stream (SSE 流式)
 * Body: { agentId, userId, sessionId, message }
 *
 * 返回一个对象，包含 `cancel` 方法和一个 Promise，该 Promise 在流结束时 resolve。
 * 通过 onEvent 回调实时推送解析后的 SSE 事件。
 *
 * 用法:
 *   const { promise } = sendMessageStream(agentId, userId, sessionId, message, (event) => {
 *     if (event.type === 'textDelta') appendText(event.text)
 *     if (event.type === 'done') finish()
 *   })
 */
export function sendMessageStream(agentId, userId, sessionId, message, onEvent) {
  const baseURL = apiClient.defaults.baseURL || ''
  const url = `${baseURL}/api/v1/chat_stream`

  let reader = null
  let cancelled = false

  function cancel() {
    cancelled = true
    if (reader) {
      reader.cancel().catch(() => {})
    }
  }

  const promise = (async () => {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentId, userId, sessionId, message })
    })

    if (!resp.ok) {
      const errText = await resp.text().catch(() => '')
      throw new Error(`HTTP ${resp.status}: ${errText || resp.statusText}`)
    }

    reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    try {
      while (!cancelled) {
        const { value, done } = await reader.read()

        if (value) {
          buffer += decoder.decode(value, { stream: true })
        }

        // When stream ends, flush the TextDecoder and process remaining buffer
        if (done) {
          buffer += decoder.decode() // flush internal multi-byte buffer
          if (buffer.trim()) {
            const parts = buffer.split('\n\n')
            for (const part of parts) {
              if (!part.trim()) continue
              const dataLine = part.startsWith('data: ') ? part.slice(6) : part
              try {
                const event = JSON.parse(dataLine)
                if (onEvent) onEvent(event)
              } catch (e) {
                console.warn('SSE parse error:', dataLine?.slice(0, 120), e)
              }
            }
          }
          break
        }

        // Split by SSE double-newline boundary
        const parts = buffer.split('\n\n')
        // Keep the last (possibly incomplete) part in the buffer
        buffer = parts.pop() || ''

        for (const part of parts) {
          if (!part.trim()) continue
          // Extract JSON after "data: " prefix
          const dataLine = part.startsWith('data: ') ? part.slice(6) : part
          try {
            const event = JSON.parse(dataLine)
            if (onEvent) onEvent(event)
            if (event.type === 'done' || event.type === 'error') {
              return
            }
          } catch (e) {
            console.warn('SSE parse error:', dataLine?.slice(0, 120), e)
          }
        }
      }
    } finally {
      reader.releaseLock()
    }
  })()

  return { promise, cancel }
}
