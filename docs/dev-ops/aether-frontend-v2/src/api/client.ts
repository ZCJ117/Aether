import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import type { ApiResponse } from '@/types/api'
import { ApiError, ResponseCode } from '@/types/api'

const ACCESS_TOKEN_KEY = 'aether_access_token'
const REFRESH_TOKEN_KEY = 'aether_refresh_token'

const apiClient: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
})

// ---- 请求拦截器：JWT Bearer 认证 ----
apiClient.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  // 兼容旧版：如果环境变量配了 API Token 且没有 JWT，保留旧机制
  const legacyToken = import.meta.env.VITE_API_TOKEN
  if (!token && legacyToken) {
    config.headers['X-API-Token'] = legacyToken
  }
  return config
})

// ---- 响应拦截器：401 自动刷新 JWT ----
let isRefreshing = false
let failedQueue: Array<{
  resolve: (token: string) => void
  reject: (err: unknown) => void
}> = []

function processQueue(error: unknown, token: string | null) {
  failedQueue.forEach(({ resolve, reject }) => {
    if (error) reject(error)
    else resolve(token!)
  })
  failedQueue = []
}

apiClient.interceptors.response.use(
  (response: AxiosResponse<ApiResponse<unknown>>) => {
    const { code, info, data } = response.data
    if (code !== ResponseCode.SUCCESS) {
      throw new ApiError(info, code, response.status, data)
    }
    return data as unknown as AxiosResponse
  },
  async (error) => {
    const originalRequest = error.config

    // 401 → 尝试用 refresh token 刷新
    if (error.response?.status === 401
        && !originalRequest._retry
        && !originalRequest.url?.includes('/auth/')) {

      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          failedQueue.push({
            resolve: (token: string) => {
              originalRequest.headers['Authorization'] = `Bearer ${token}`
              resolve(apiClient(originalRequest))
            },
            reject,
          })
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY)
      if (refreshToken) {
        try {
          const res = await axios.post(
            `${import.meta.env.VITE_API_BASE ?? ''}/api/v1/auth/refresh`,
            { refreshToken }
          )
          if (res.data?.code === '0000') {
            const { accessToken, refreshToken: newRefreshToken } = res.data.data
            setTokens(accessToken, newRefreshToken)
            processQueue(null, accessToken)
            originalRequest.headers['Authorization'] = `Bearer ${accessToken}`
            return apiClient(originalRequest)
          }
        } catch {
          processQueue(new Error('refresh_failed'), null)
        }
      }

      isRefreshing = false
      clearTokens()
      // 不在 Vue 组件内，无法用 router.push，直接 reload 到登录页
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
      return Promise.reject(error)
    }

    if (error.response) {
      const data = error.response.data as ApiResponse | undefined
      if (data?.code && data?.code !== ResponseCode.SUCCESS) {
        throw new ApiError(data.info, data.code, error.response.status, data.data)
      }
      throw new ApiError(
        error.message || '网络请求失败',
        'NETWORK_ERROR',
        error.response.status
      )
    }
    throw new ApiError(error.message || '网络连接失败', 'NETWORK_ERROR')
  }
)

// ---- JWT Token 工具函数 ----
export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setTokens(accessToken: string, refreshToken: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

/** 类型安全的请求方法 */
export const api = {
  get: <T>(url: string, params?: Record<string, string>): Promise<T> =>
    apiClient.get(url, { params }) as unknown as Promise<T>,

  post: <T>(url: string, data?: unknown): Promise<T> =>
    apiClient.post(url, data) as unknown as Promise<T>,

  put: <T>(url: string, data?: unknown): Promise<T> =>
    apiClient.put(url, data) as unknown as Promise<T>,

  delete: <T>(url: string): Promise<T> =>
    apiClient.delete(url) as unknown as Promise<T>,
}

export default apiClient
