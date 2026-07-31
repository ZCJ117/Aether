import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export type NotificationType = 'info' | 'success' | 'warning' | 'error'

export interface Notification {
  id: string
  title: string
  message: string
  type: NotificationType
  read: boolean
  timestamp: number
}

function generateId(): string {
  return crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
}

export const useNotificationStore = defineStore('notification', () => {
  // ========== State ==========
  const notifications = ref<Notification[]>([])

  // ========== Getters ==========
  const unreadCount = computed(() => notifications.value.filter((n) => !n.read).length)

  // ========== Actions ==========

  function add(title: string, message: string, type: NotificationType = 'info'): string {
    const id = generateId()
    notifications.value.push({
      id,
      title,
      message,
      type,
      read: false,
      timestamp: Date.now(),
    })
    return id
  }

  function markAsRead(id: string): void {
    const notification = notifications.value.find((n) => n.id === id)
    if (notification) {
      notification.read = true
    }
  }

  function markAllAsRead(): void {
    notifications.value.forEach((n) => {
      n.read = true
    })
  }

  function dismiss(id: string): void {
    notifications.value = notifications.value.filter((n) => n.id !== id)
  }

  function clearAll(): void {
    notifications.value = []
  }

  return {
    notifications,
    unreadCount,
    add,
    markAsRead,
    markAllAsRead,
    dismiss,
    clearAll,
  }
})
