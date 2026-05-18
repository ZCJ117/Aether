import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { createSession } from '@/api/session'
import { sendMessageStream } from '@/api/chat'
import { formatTime } from '@/utils/time'

let msgIdCounter = 0
function nextId() {
  return `msg_${++msgIdCounter}_${Date.now()}`
}

/**
 * 清洗消息文本，修复中文换行问题：
 *   1. 合并3个以上连续换行为双换行（保留段落间距）
 *   2. 合并连续空格为一个
 *   3. 去除首尾空白
 *   4. 修复中文句子中间的孤立换行（中文字符后的 \n 再跟中文字符）
 */
function cleanMessageText(text) {
  if (!text) return text
  let cleaned = text

  // 合并3个以上连续换行为双换行
  cleaned = cleaned.replace(/\n{3,}/g, '\n\n')

  // 合并连续空格（但不处理换行）
  cleaned = cleaned.replace(/[^\S\n]{2,}/g, ' ')

  // 去掉中文句子中间的孤立 \n（前后都是中文字符的情况）
  cleaned = cleaned.replace(/([一-鿿　-〿＀-￯])\n([一-鿿　-〿＀-￯])/g, '$1$2')

  // 去掉中文标点后的孤立 \n（逗号、句号、问号等之后跟中文字符）
  cleaned = cleaned.replace(/([，。！？、；：」』）\]】])\n([一-鿿])/g, '$1$2')

  // 去掉首尾空白
  cleaned = cleaned.trim()

  return cleaned
}

function formatElapsed(ms) {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  const min = Math.floor(ms / 60000)
  const sec = ((ms % 60000) / 1000).toFixed(0)
  return `${min}m${sec}s`
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref([])
  const isSending = ref(false)
  const statusText = ref('')
  const statusType = ref('info')

  const isEmpty = computed(() => messages.value.length === 0)

  function addMessage(side, text, meta = null) {
    const id = nextId()
    messages.value.push({ id, side, text, meta })
    return id
  }

  function updateMessage(id, text, meta) {
    const msg = messages.value.find((m) => m.id === id)
    if (msg) {
      msg.text = cleanMessageText(text)
      if (meta !== undefined) msg.meta = meta
    } else {
      console.warn('[chat store] updateMessage: message not found for id=' + id)
    }
  }

  function setStatus(text, type = 'info') {
    statusText.value = text
    statusType.value = type
  }

  async function sendMessageAction(message, agentId, userId) {
    isSending.value = true
    setStatus('')

    // Add user bubble with timestamp
    addMessage('user', message, formatTime(Date.now()))

    // Add placeholder agent bubble — will be updated incrementally
    const placeholderId = addMessage('agent', '思考中…')
    const startTime = Date.now()
    let accumulated = ''
    let streamDone = false

    try {
      const { sessionId } = await createSession(agentId, userId)
      if (!sessionId) {
        throw new Error('创建会话失败：无 sessionId')
      }

      const { promise } = sendMessageStream(
        agentId, userId, sessionId, message,
        (event) => {
          switch (event.type) {
            case 'textDelta':
              accumulated += event.text || ''
              updateMessage(placeholderId, accumulated)
              break
            case 'toolCall':
              setStatus(`调用工具: ${event.toolName || ''}`)
              break
            case 'toolResult':
              setStatus(
                event.toolError
                  ? `工具错误: ${event.toolName || ''}`
                  : `工具完成: ${event.toolName || ''}`
              )
              break
            case 'done':
              streamDone = true
              break
            case 'error':
              throw new Error(event.errorMessage || '流式对话错误')
          }
        }
      )

      await promise

      const elapsed = Date.now() - startTime
      const timeLabel = formatElapsed(elapsed)

      updateMessage(
        placeholderId,
        accumulated || '(空响应)',
        `思考时间: ${timeLabel}  |  session: ${sessionId}`
      )
      setStatus('已完成')
    } catch (err) {
      const errMsg = err?.message || '请求失败'
      if (streamDone) {
        // Stream completed but had an error afterward
        updateMessage(placeholderId, accumulated || `请求失败：${errMsg}`)
      } else if (accumulated) {
        // Partial response received
        updateMessage(placeholderId, accumulated, `中断：${errMsg}`)
      } else {
        updateMessage(placeholderId, `请求失败：${errMsg}`)
      }
      setStatus(`失败：${errMsg}`, 'error')
    } finally {
      isSending.value = false
    }
  }

  function clearMessages() {
    messages.value = []
    setStatus('')
  }

  return {
    messages,
    isSending,
    statusText,
    statusType,
    isEmpty,
    addMessage,
    updateMessage,
    setStatus,
    sendMessage: sendMessageAction,
    clearMessages
  }
})
