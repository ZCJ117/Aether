import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getCookie, setCookie, deleteCookie } from '@/utils/cookie'
import { loginApi, getApiToken, setApiToken, clearApiToken } from '@/api/auth'
import { setTokens, clearTokens } from '@/api/client'

export const useAuthStore = defineStore('auth', () => {
  const userId = ref('')
  const loginTs = ref<number | null>(null)
  const isChecking = ref(false)
  const isLoggingIn = ref(false)
  const loginError = ref('')
  const userRole = ref('')
  /** @deprecated 旧版共享 API Token（JWT 迁移后保留兼容） */
  const apiToken = ref(getApiToken())
  /** @deprecated 旧版 */
  const isValidToken = computed(() => apiToken.value.length > 0)

  const isLoggedIn = computed(() => !!loginTs.value && !!userId.value)
  const isAdmin = computed(() => userRole.value === 'ADMIN')

  function checkLogin(): boolean {
    isChecking.value = true
    const stored = getCookie('aether_user')
    if (stored) {
      try {
        const { id, ts, role } = JSON.parse(stored)
        userId.value = id
        loginTs.value = ts
        userRole.value = role || ''
        isChecking.value = false
        return true
      } catch {
        /* fall through */
      }
    }
    // 也检查 localStorage 中的 JWT（无痕模式 / cookie 被清除时）
    const token = localStorage.getItem('aether_access_token')
    if (token) {
      try {
        const payload = JSON.parse(atob(token.split('.')[1]))
        userId.value = payload.username || payload.sub
        loginTs.value = Date.now()
        userRole.value = payload.role || ''
        isChecking.value = false
        return true
      } catch {
        /* fall through */
      }
    }
    isChecking.value = false
    return false
  }

  /** 调用后端 JWT 登录 */
  async function login(username: string, password: string): Promise<{ ok: boolean; error?: string }> {
    isLoggingIn.value = true
    loginError.value = ''
    try {
      const data = await loginApi(username, password)
      setTokens(data.accessToken, data.refreshToken)
      userId.value = data.user.username
      loginTs.value = Date.now()
      userRole.value = data.user.role
      setCookie('aether_user', JSON.stringify({
        id: data.user.username,
        ts: loginTs.value,
        role: data.user.role,
      }))
      isLoggingIn.value = false
      return { ok: true }
    } catch (err: any) {
      isLoggingIn.value = false
      loginError.value = err.message || '登录失败'
      return { ok: false, error: loginError.value }
    }
  }

  function logout(): void {
    userId.value = ''
    loginTs.value = null
    userRole.value = ''
    clearTokens()
    deleteCookie('aether_user')
  }

  /** @deprecated 旧版 Token 设置（JWT 迁移后保留兼容） */
  function setToken(token: string): void {
    apiToken.value = token
    setApiToken(token)
  }

  /** @deprecated 旧版 Token 清除 */
  function clearToken(): void {
    apiToken.value = ''
    clearApiToken()
  }

  // Boot check
  checkLogin()

  return {
    userId,
    loginTs,
    isChecking,
    isLoggingIn,
    loginError,
    userRole,
    apiToken,
    isValidToken,
    isLoggedIn,
    isAdmin,
    checkLogin,
    login,
    logout,
    setToken,
    clearToken,
  }
})
