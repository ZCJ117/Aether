export function required(msg = '此项为必填'): (v: string) => true | string {
  return (v) => (v && v.trim()) ? true : msg
}

export function minLength(min: number, msg?: string): (v: string) => true | string {
  return (v) => (v && v.length >= min) ? true : (msg || `至少 ${min} 个字符`)
}

export function maxLength(max: number, msg?: string): (v: string) => true | string {
  return (v) => (v && v.length <= max) ? true : (msg || `最多 ${max} 个字符`)
}

export function pattern(regex: RegExp, msg = '格式不正确'): (v: string) => true | string {
  return (v) => regex.test(v) ? true : msg
}

export const agentIdPattern = /^[a-zA-Z0-9_-]+$/
