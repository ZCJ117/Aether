import axios from 'axios'
import { useAgentStore } from '@/stores/agent'

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || undefined,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// Response interceptor — unwrap envelope, detect errors
apiClient.interceptors.response.use(
  (response) => {
    const data = response.data

    // Check application-level success code
    if (data && data.code && data.code !== '0000') {
      const err = new Error(data.info || `接口失败: ${data.code}`)
      err.code = data.code
      return Promise.reject(err)
    }

    // Unwrap data payload
    return data.data
  },
  (error) => {
    const msg = error.message || ''
    const isNetworkError =
      msg.includes('Network Error') ||
      msg.includes('Failed to fetch') ||
      msg.includes('fetch') ||
      !error.response

    if (isNetworkError) {
      // Show backend-down modal via store
      try {
        const agentStore = useAgentStore()
        agentStore.$patch({
          backendDown: true,
          backendError: `API_BASE: ${apiClient.defaults.baseURL}\n${msg}`
        })
      } catch {
        // Store not available (e.g. during boot)
      }
    }

    return Promise.reject(error)
  }
)

export default apiClient
