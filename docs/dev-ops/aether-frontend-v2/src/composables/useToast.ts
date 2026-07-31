import { ref, type Ref } from 'vue'

export interface Toast {
  id: string
  message: string
  type: 'success' | 'error' | 'warning' | 'info'
  duration?: number
}

const toasts: Ref<Toast[]> = ref([])

let toastId = 0

function nextId(): string {
  return `toast_${++toastId}_${Date.now()}`
}

function add(toast: Omit<Toast, 'id'>): string {
  const id = nextId()
  const duration = toast.duration ?? defaultDuration(toast.type)
  toasts.value.push({ ...toast, id })

  if (duration > 0) {
    setTimeout(() => remove(id), duration)
  }

  return id
}

function remove(id: string): void {
  const idx = toasts.value.findIndex((t) => t.id === id)
  if (idx !== -1) {
    toasts.value.splice(idx, 1)
  }
}

function defaultDuration(type: Toast['type']): number {
  switch (type) {
    case 'success':
      return 3000
    case 'error':
      return 5000
    case 'warning':
      return 5000
    case 'info':
      return 3000
  }
}

function success(message: string, duration?: number): string {
  return add({ message, type: 'success', duration })
}

function error(message: string, duration?: number): string {
  return add({ message, type: 'error', duration })
}

function warning(message: string, duration?: number): string {
  return add({ message, type: 'warning', duration })
}

function info(message: string, duration?: number): string {
  return add({ message, type: 'info', duration })
}

export function useToast() {
  return {
    toasts,
    add,
    remove,
    success,
    error,
    warning,
    info,
  }
}
