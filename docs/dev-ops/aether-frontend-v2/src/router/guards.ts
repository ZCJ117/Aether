import type { Router } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

export function setupGuards(router: Router): void {
  router.beforeEach((to, _from, next) => {
    const auth = useAuthStore()

    if (to.meta.requiresAuth && !auth.isLoggedIn) {
      return next({ name: 'Login', query: { redirect: to.fullPath } })
    }

    if (to.name === 'Login' && auth.isLoggedIn) {
      return next({ name: 'Dashboard' })
    }

    next()
  })

  router.afterEach((to) => {
    document.title = (to.meta.title as string) || 'Aether'
  })
}
