/**
 * Set a cookie with a max-age in days.
 */
export function setCookie(name, value, days = 7) {
  const maxAge = Math.max(0, Math.floor(days * 86400))
  document.cookie = `${name}=${encodeURIComponent(value)}; Max-Age=${maxAge}; Path=/; SameSite=Lax`
}

/**
 * Get a cookie value by name. Returns null if not found.
 */
export function getCookie(name) {
  const cookies = document.cookie ? document.cookie.split('; ') : []
  for (const item of cookies) {
    const eq = item.indexOf('=')
    const k = eq >= 0 ? item.slice(0, eq) : item
    const v = eq >= 0 ? item.slice(eq + 1) : ''
    if (k === name) return decodeURIComponent(v)
  }
  return null
}

/**
 * Delete a cookie by name.
 */
export function deleteCookie(name) {
  document.cookie = `${name}=; Max-Age=0; Path=/; SameSite=Lax`
}
