/** 调用后端 /api/v1/auth/login 获取 JWT Token */
export async function loginApi(username: string, password: string): Promise<{
  accessToken: string
  refreshToken: string
  user: { id: number; username: string; role: string }
}> {
  const baseURL = import.meta.env.VITE_API_BASE ?? ''
  const res = await fetch(`${baseURL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const json = await res.json()
  if (json.code !== '0000') {
    throw new Error(json.info || '登录失败')
  }
  return json.data
}

export function getApiToken(): string {
  return import.meta.env.VITE_API_TOKEN || ''
}

export function setApiToken(token: string): void {
  localStorage.setItem('api_token', token)
}

export function clearApiToken(): void {
  localStorage.removeItem('api_token')
}
