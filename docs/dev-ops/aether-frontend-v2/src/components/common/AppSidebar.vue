<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import {
  LayoutDashboard,
  MessageSquare,
  Bot,
  Cpu,
  Wrench,
  Settings,
} from 'lucide-vue-next'

const ui = useUiStore()
const route = useRoute()

interface NavItem {
  to: string
  label: string
  icon: typeof LayoutDashboard
}

const navItems: NavItem[] = [
  { to: '/app/dashboard', label: '仪表盘', icon: LayoutDashboard },
  { to: '/app/chat', label: '对话', icon: MessageSquare },
  { to: '/app/agents', label: '智能体', icon: Bot },
  { to: '/app/models', label: '模型', icon: Cpu },
  { to: '/app/skills', label: '技能', icon: Wrench },
  { to: '/app/settings', label: '设置', icon: Settings },
]

function isActive(item: NavItem): boolean {
  const currentPath = route.path
  if (currentPath === item.to) return true
  if (item.to !== '/app/dashboard') {
    return currentPath.startsWith(item.to)
  }
  return false
}
</script>

<template>
  <aside
    :class="ui.sidebarCollapsed ? 'w-[64px]' : 'w-[260px]'"
    class="flex flex-col flex-shrink-0 min-h-screen transition-all duration-300"
    style="background: rgba(28,28,30,0.85); backdrop-filter: blur(20px) saturate(180%); -webkit-backdrop-filter: blur(20px) saturate(180%); border-right: 0.5px solid rgba(255,255,255,0.06);"
  >
    <!-- Logo -->
    <div class="h-11 flex items-center px-4 flex-shrink-0">
      <div class="flex items-center gap-3">
        <div class="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0"
             style="background: rgba(90,200,250,0.12);">
          <span class="text-xs font-bold" style="color: #5AC8FA;">A</span>
        </div>
        <Transition name="fade">
          <span
            v-if="!ui.sidebarCollapsed"
            class="font-semibold text-[15px] whitespace-nowrap"
            style="color: #F5F5F7;"
          >
            Aether
          </span>
        </Transition>
      </div>
    </div>

    <!-- Navigation -->
    <nav class="flex-1 py-3 px-2 overflow-y-auto">
      <ul class="flex flex-col gap-0.5">
        <li v-for="item in navItems" :key="item.to">
          <router-link
            :to="item.to"
            :class="[
              'flex items-center gap-3 px-3 py-2 rounded-[10px] transition-colors group relative text-[13px]',
              isActive(item)
                ? 'font-medium'
                : 'font-normal',
              ui.sidebarCollapsed ? 'justify-center' : ''
            ]"
            :style="isActive(item)
              ? 'background: rgba(90,200,250,0.08); color: #F5F5F7; border-left: 3px solid #5AC8FA;'
              : 'color: #98989D; border-left: 3px solid transparent;'"
          >
            <component :is="item.icon" class="w-[18px] h-[18px] flex-shrink-0" />
            <Transition name="fade">
              <span v-if="!ui.sidebarCollapsed" class="whitespace-nowrap">
                {{ item.label }}
              </span>
            </Transition>

            <div
              v-if="ui.sidebarCollapsed"
              class="absolute left-full ml-2 px-2 py-1 rounded text-xs whitespace-nowrap opacity-0 group-hover:opacity-100 pointer-events-none z-50 transition-opacity"
              style="background: rgba(44,44,46,0.95); color: #F5F5F7;"
            >
              {{ item.label }}
            </div>
          </router-link>
        </li>
      </ul>
    </nav>
  </aside>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
