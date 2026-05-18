import { ref } from 'vue'
import { getCookie as readCookie, setCookie as writeCookie, deleteCookie as removeCookie } from '@/utils/cookie'

export function useCookie(name) {
  const value = ref(readCookie(name))

  function get() {
    const v = readCookie(name)
    value.value = v
    return v
  }

  function set(val, days) {
    writeCookie(name, val, days)
    value.value = val
  }

  function remove() {
    removeCookie(name)
    value.value = null
  }

  return { value, get, set, remove }
}
