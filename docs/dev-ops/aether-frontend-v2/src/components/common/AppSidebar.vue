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
  Brain,
  Workflow,
  Settings,
} from 'lucide-vue-next'
import ThemeToggle from './ThemeToggle.vue'
import LocaleToggle from './LocaleToggle.vue'

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
  { to: '/app/memory', label: '记忆', icon: Brain },
  { to: '/app/workflows', label: '工作流', icon: Workflow },
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
    :class="ui.sidebarCollapsed ? 'w-[60px]' : 'w-[280px]'"
    class="flex flex-col border-r border-white/5 bg-surface-card transition-all duration-300 flex-shrink-0 min-h-screen"
  >
    <!-- Logo / Brand -->
    <div class="h-14 flex items-center border-b border-white/5 px-4 flex-shrink-0">
      <div class="flex items-center gap-3">
        <div class="w-7 h-7 rounded-lg bg-accent-blue/20 flex items-center justify-center flex-shrink-0">
          <span class="text-accent-blue text-xs font-bold">A</span>
        </div>
        <Transition name="fade">
          <span v-if="!ui.sidebarCollapsed" class="text-primary font-semibold text-base whitespace-nowrap">
            Aether
          </span>
        </Transition>
      </div>
    </div>

    <!-- Navigation -->
    <nav class="flex-1 py-4 px-2 overflow-y-auto">
      <ul class="flex flex-col gap-1">
        <li v-for="item in navItems" :key="item.to">
          <router-link
            :to="item.to"
            :class="[
              'flex items-center gap-3 px-3 py-2.5 rounded-lg transition-colors group relative',
              isActive(item)
                ? 'bg-white/5 text-primary border-l-2 border-l-white'
                : 'text-secondary hover:bg-white/[0.03] hover:text-primary border-l-2 border-l-transparent',
              ui.sidebarCollapsed ? 'justify-center' : ''
            ]"
          >
            <component :is="item.icon" class="w-5 h-5 flex-shrink-0" />
            <Transition name="fade">
              <span v-if="!ui.sidebarCollapsed" class="text-sm font-medium whitespace-nowrap">
                {{ item.label }}
              </span>
            </Transition>

            <!-- Tooltip when collapsed -->
            <div
              v-if="ui.sidebarCollapsed"
              class="absolute left-full ml-2 px-2 py-1 bg-surface-card border border-white/5 rounded
                     text-xs text-primary whitespace-nowrap opacity-0 group-hover:opacity-100
                     pointer-events-none z-50 transition-opacity"
            >
              {{ item.label }}
            </div>
          </router-link>
        </li>
      </ul>
    </nav>

    <!-- Bottom toggles -->
    <div class="border-t border-white/5 px-4 py-3 flex-shrink-0">
      <div
        :class="ui.sidebarCollapsed ? 'flex-col' : 'flex-row'"
        class="flex items-center gap-2"
      >
        <ThemeToggle />
        <LocaleToggle />
      </div>
    </div>
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
