/** 获取/设置 API Token */
export function getApiToken(): string {
  return import.meta.env.VITE_API_TOKEN || localStorage.getItem('api_token') || ''
}

export function setApiToken(token: string): void {
  localStorage.setItem('api_token', token)
}

export function clearApiToken(): void {
  localStorage.removeItem('api_token')
}
