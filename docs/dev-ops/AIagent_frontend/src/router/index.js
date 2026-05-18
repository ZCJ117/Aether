import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/',
    redirect: '/chat'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { requiresGuest: true }
  },
  {
    path: '/chat',
    name: 'Chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/chat'
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach((to, from, next) => {
  const auth = useAuthStore()

  // Perform initial cookie check if not done yet
  if (auth.isChecking) {
    auth.checkLogin()
  }

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return next({ name: 'Login', replace: true })
  }

  if (to.meta.requiresGuest && auth.isLoggedIn) {
    return next({ name: 'Chat', replace: true })
  }

  next()
})

export default router
