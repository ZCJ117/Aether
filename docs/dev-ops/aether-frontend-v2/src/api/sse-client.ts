import type { StreamEvent, StreamHandle } from '@/types/sse'

export interface SSEOptions {
  url: string
  body?: unknown
  headers?: Record<string, string>
  onEvent: (event: StreamEvent) => void
  onError?: (error: Error) => void
  onComplete?: () => void
  signal?: AbortSignal
}

export function createSSEConnection(options: SSEOptions): StreamHandle {
  const { url, body, headers = {}, onEvent, onError, onComplete, signal } = options

  const controller = new AbortController()
  const combinedSignal = signal
    ? combineAbortSignals(signal, controller.signal)
    : controller.signal

  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null

  function cancel(): void {
    controller.abort()
    reader?.cancel().catch(() => {})
  }

  const promise = (async () => {
    try {
      const resp = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...headers },
        body: body ? JSON.stringify(body) : undefined,
        signal: combinedSignal,
      })

      if (!resp.ok) {
        const text = await resp.text().catch(() => '')
        throw new Error(`SSE 连接失败: HTTP ${resp.status} — ${text.slice(0, 200)}`)
      }
      if (!resp.body) {
        throw new Error('SSE 连接失败: 响应体为空')
      }

      reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { value, done } = await reader.read()
        if (value) buffer += decoder.decode(value, { stream: true })

        const parts = buffer.split('\n\n')
        buffer = parts.pop() || ''

        for (const part of parts) {
          const trimmed = part.trim()
          if (!trimmed) continue
          const dataLine = trimmed.startsWith('data: ') ? trimmed.slice(6) : trimmed
          try {
            const event = JSON.parse(dataLine) as StreamEvent
            onEvent(event)
            if (event.type === 'error') {
              onError?.(new Error((event as { errorMessage: string }).errorMessage))
              onComplete?.()
              return
            }
            if (event.type === 'done') {
              onComplete?.()
              return
            }
          } catch {
            console.warn('[SSE] 解析失败:', dataLine.slice(0, 120))
          }
        }
        if (done) break
      }

      // Flush remaining buffer
      buffer += decoder.decode()
      for (const part of buffer.split('\n\n')) {
        const trimmed = part.trim()
        if (!trimmed) continue
        const dataLine = trimmed.startsWith('data: ') ? trimmed.slice(6) : trimmed
        try {
          onEvent(JSON.parse(dataLine) as StreamEvent)
        } catch { /* ignore trailing */ }
      }
      onComplete?.()
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        onError?.(err as Error)
      } else {
        // 流被中止（用户取消/连接中断）时也要复位 UI 状态，否则 isSending 永不复位
        onComplete?.()
      }
    } finally {
      reader?.releaseLock()
    }
  })()

  return { promise, cancel }
}

/** 合并两个 AbortSignal */
function combineAbortSignals(a: AbortSignal, b: AbortSignal): AbortSignal {
  const controller = new AbortController()
  const onAbort = () => controller.abort()
  a.addEventListener('abort', onAbort, { once: true })
  b.addEventListener('abort', onAbort, { once: true })
  if (a.aborted || b.aborted) controller.abort()
  return controller.signal
}
