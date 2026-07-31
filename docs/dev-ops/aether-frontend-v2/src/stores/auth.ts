import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getCookie, setCookie, deleteCookie } from '@/utils/cookie'
import { getApiToken, setApiToken, clearApiToken } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const userId = ref('')
  const loginTs = ref<number | null>(null)
  const isChecking = ref(false)
  const apiToken = ref(getApiToken())

  const isLoggedIn = computed(() => !!loginTs.value && !!userId.value)
  const isValidToken = computed(() => apiToken.value.length > 0)

  function checkLogin(): boolean {
    isChecking.value = true
    const stored = getCookie('aether_user')
    if (stored) {
      try {
        const { id, ts } = JSON.parse(stored)
        userId.value = id
        loginTs.value = ts
        isChecking.value = false
        return true
      } catch {
        /* fall through */
      }
    }
    isChecking.value = false
    return false
  }

  function login(username: string, password: string): { ok: boolean; error?: string } {
    if (username === 'admin' && password === 'admin') {
      userId.value = username
      loginTs.value = Date.now()
      setCookie('aether_user', JSON.stringify({ id: username, ts: loginTs.value }))
      return { ok: true }
    }
    return { ok: false, error: '用户名或密码错误' }
  }

  function logout(): void {
    userId.value = ''
    loginTs.value = null
    deleteCookie('aether_user')
  }

  function setToken(token: string): void {
    apiToken.value = token
    setApiToken(token)
  }

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
    apiToken,
    isLoggedIn,
    isValidToken,
    checkLogin,
    login,
    logout,
    setToken,
    clearToken,
  }
})
