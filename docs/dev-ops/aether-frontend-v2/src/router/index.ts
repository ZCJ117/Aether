import { createRouter, createWebHashHistory } from 'vue-router'
import { routes } from './routes'
import { setupGuards } from './guards'

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

setupGuards(router)
