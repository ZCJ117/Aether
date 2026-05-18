import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getCookie, setCookie, deleteCookie } from '@/utils/cookie'
import { formatTime } from '@/utils/time'

const COOKIE_NAME = 'ai_agent_login'
const COOKIE_DAYS = 7

export const useAuthStore = defineStore('auth', () => {
  const userId = ref('')
  const loginTs = ref(null)
  const isChecking = ref(true)

  const isLoggedIn = computed(() => !!userId.value)
  const formattedLoginTime = computed(() =>
    loginTs.value ? formatTime(loginTs.value) : ''
  )
  const cookiePayload = computed(() => ({
    user: userId.value,
    ts: loginTs.value
  }))

  function checkLogin() {
    const raw = getCookie(COOKIE_NAME)
    if (!raw) {
      isChecking.value = false
      return false
    }
    try {
      const payload = JSON.parse(raw)
      if (payload && payload.user) {
        userId.value = String(payload.user)
        loginTs.value = payload.ts || null
        isChecking.value = false
        return true
      }
    } catch {
      // Invalid cookie
    }
    isChecking.value = false
    return false
  }

  function login(username, password) {
    const u = (username || '').trim()
    const p = password || ''

    if (!u || !p) return { ok: false, error: '请输入账号与密码。' }
    if (u !== 'admin' || p !== 'admin')
      return { ok: false, error: '账号或密码错误' }

    const ts = Date.now()
    const payload = JSON.stringify({ user: u, ts })
    setCookie(COOKIE_NAME, payload, COOKIE_DAYS)

    userId.value = u
    loginTs.value = ts
    return { ok: true }
  }

  function logout() {
    deleteCookie(COOKIE_NAME)
    userId.value = ''
    loginTs.value = null
  }

  return {
    userId,
    loginTs,
    isChecking,
    isLoggedIn,
    formattedLoginTime,
    cookiePayload,
    checkLogin,
    login,
    logout
  }
})
