import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import type { ApiResponse } from '@/types/api'
import { ApiError, ResponseCode } from '@/types/api'

const apiClient: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '',
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
})

// ---- 请求拦截器 ----
apiClient.interceptors.request.use((config) => {
  const token =
    import.meta.env.VITE_API_TOKEN || localStorage.getItem('api_token')
  if (token) {
    config.headers['X-API-Token'] = token
  }
  return config
})

// ---- 响应拦截器：自动解包 ApiResponse<T> ----
apiClient.interceptors.response.use(
  (response: AxiosResponse<ApiResponse<unknown>>) => {
    const { code, info, data } = response.data
    if (code !== ResponseCode.SUCCESS) {
      throw new ApiError(info, code, response.status, data)
    }
    return data as unknown as AxiosResponse
  },
  (error) => {
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
