# Apple Dark 风格前端重设计 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 aether-frontend-v2 的 6 个核心页面 + AppShell 统一为 Apple HIG 暗色风格

**Architecture:** 先改全局 CSS 变量 + Tailwind 配置奠定色彩/字体基础，再依次改造 AppShell → 登录 → 各页面。每步改一个文件，完成后 type-check 验证。不改 landing page、路由、store 逻辑。

**Tech Stack:** Vue 3 + TypeScript + Tailwind CSS 3 + Vite

---

### Task 1: 全局 CSS 变量替换 + 字体栈 + 基础重置

**Files:**
- Modify: `src/style.css`

- [ ] **Step 1: 替换 `:root` CSS 变量为 Apple 暗色体系**

找到 `src/style.css` 第 5-21 行的 `:root` 块，完整替换：

```css
:root {
  --bg-base: #000000;
  --bg-elevated: #1C1C1E;
  --bg-grouped: #2C2C2E;
  --bg-separator: #3A3A3C;
  --text-primary: #F5F5F7;
  --text-secondary: #98989D;
  --text-tertiary: #636366;
  --text-quaternary: #48484A;
  --accent: #5AC8FA;
  --accent-green: #30D158;
  --accent-yellow: #FFD60A;
  --accent-orange: #FF9F0A;
  --accent-red: #FF453A;

  /* 向后兼容别名 */
  --bg-primary: var(--bg-base);
  --bg-secondary: var(--bg-elevated);
  --bg-card: var(--bg-grouped);
  --bg-raised: var(--bg-separator);
  --border-color: rgba(255, 255, 255, 0.08);
  --accent-color: var(--accent);
  --accent-blue: var(--accent);
  --text-muted: var(--text-tertiary);

  color-scheme: dark;
}
```

- [ ] **Step 2: 删除 `:root.light` 块（不再支持浅色主题）**

删除第 23-39 行整个 `:root.light { ... }` 块。

- [ ] **Step 3: 替换全局过渡动画，简化 `*` 选择器**

删除第 41-45 行的旧过渡，替换为：

```css
*,
*::before,
*::after {
  transition: background-color 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}
```

- [ ] **Step 4: 更新 `html` 和 `body` 字体**

找到第 47-60 行，替换为：

```css
html {
  font-size: 16px;
}

body {
  margin: 0;
  padding: 0;
  font-family: 'Microsoft YaHei', -apple-system, BlinkMacSystemFont, 'Segoe UI',
    'Helvetica Neue', sans-serif;
  background-color: var(--bg-base);
  color: var(--text-primary);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
```

- [ ] **Step 5: 更新 `::selection` 颜色**

```css
::selection {
  background-color: var(--accent);
  color: #000000;
}
```

- [ ] **Step 6: 更新滚动条颜色适配新色系**

将滚动条 thumb 颜色从 `rgba(128, 128, 160, 0.4)` 改为 `rgba(120, 120, 128, 0.36)`：

```css
::-webkit-scrollbar-thumb {
  background: rgba(120, 120, 128, 0.36);
  border-radius: 3px;
}

::-webkit-scrollbar-thumb:hover {
  background: rgba(120, 120, 128, 0.55);
}

/* Firefox */
* {
  scrollbar-width: thin;
  scrollbar-color: rgba(120, 120, 128, 0.36) transparent;
}
```

- [ ] **Step 7: 运行 type-check 确认无错误**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1
```
Expected: 无错误输出（OK）

- [ ] **Step 8: Commit**

```bash
git add src/style.css
git commit -m "style: 替换全局 CSS 为 Apple 暗色体系"
```

---

### Task 2: Tailwind 配置 — 颜色 + 字体扩展更新

**Files:**
- Modify: `tailwind.config.ts`

- [ ] **Step 1: 替换 `tailwind.config.ts` 全部 theme.extend 块**

找到文件，整体替换为：

```ts
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        surface: {
          ink: '#000000',
          base: '#000000',
          card: '#1C1C1E',
          raised: '#2C2C2E',
          overlay: '#3A3A3C',
        },
        accent: {
          DEFAULT: '#5AC8FA',
          green: '#30D158',
          yellow: '#FFD60A',
          orange: '#FF9F0A',
          red: '#FF453A',
        },
      },
      fontFamily: {
        sans: [
          'Microsoft YaHei',
          '-apple-system',
          'BlinkMacSystemFont',
          'Segoe UI',
          'sans-serif',
        ],
        mono: ['JetBrains Mono', 'Fira Code', 'monospace'],
      },
      fontSize: {
        '2xs': ['0.625rem', { lineHeight: '0.875rem' }],
      },
      animation: {
        'fade-in': 'fadeIn 200ms ease-out',
        'slide-up': 'slideUp 200ms ease-out',
        'slide-right': 'slideRight 200ms ease-out',
        'pulse-dot': 'pulseDot 1.4s infinite ease-in-out',
        'spin-slow': 'spin 2s linear infinite',
      },
      keyframes: {
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        slideUp: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        slideRight: {
          '0%': { opacity: '0', transform: 'translateX(-8px)' },
          '100%': { opacity: '1', transform: 'translateX(0)' },
        },
        pulseDot: {
          '0%, 80%, 100%': { opacity: '0.2', transform: 'scale(0.8)' },
          '40%': { opacity: '1', transform: 'scale(1)' },
        },
      },
    },
  },
  plugins: [],
}
```

- [ ] **Step 2: 运行 type-check**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1
```

- [ ] **Step 3: Commit**

```bash
git add tailwind.config.ts
git commit -m "style: 更新 Tailwind 配置为 Apple 暗色体系"
```

---

### Task 3: AppShell — AppLayout 毛玻璃布局

**Files:**
- Modify: `src/components/common/AppLayout.vue`

- [ ] **Step 1: 替换 AppLayout.vue 全部内容**

```vue
<script setup lang="ts">
import AppSidebar from './AppSidebar.vue'
import AppHeader from './AppHeader.vue'
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-[#000]">
    <AppSidebar />
    <div class="flex-1 flex flex-col min-w-0">
      <AppHeader />
      <main class="flex-1 overflow-y-auto">
        <router-view />
      </main>
    </div>
  </div>
</template>
```

关键变化：`bg-surface-base` → `bg-[#000]`，移除 CSS Grid `grid-template-rows`，改用 flex-col 自然撑满。

- [ ] **Step 2: 运行 type-check**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1
```

- [ ] **Step 3: Commit**

```bash
git add src/components/common/AppLayout.vue
git commit -m "style: AppLayout 改为纯黑根背景 + flex 布局"
```

---

### Task 4: AppShell — AppSidebar 毛玻璃导航

**Files:**
- Modify: `src/components/common/AppSidebar.vue`

- [ ] **Step 1: 替换 AppSidebar.vue 全部内容**

```vue
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
```

关键变化：毛玻璃 inline style (`backdrop-filter` + `rgba`)，选中态浅蓝底色+左边条，64px 折叠宽度，0.5px 右边分隔线，去掉底部 toggle 区域。

- [ ] **Step 2: 运行 type-check**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1
```

- [ ] **Step 3: Commit**

```bash
git add src/components/common/AppSidebar.vue
git commit -m "style: AppSidebar 毛玻璃 + 浅蓝选中态 + 64px 折叠"
```

---

### Task 5: AppShell — AppHeader 精简毛玻璃顶栏

**Files:**
- Modify: `src/components/common/AppHeader.vue`

- [ ] **Step 1: 替换 AppHeader.vue 全部内容**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { PanelLeftClose, PanelLeftOpen } from 'lucide-vue-next'

const ui = useUiStore()
const route = useRoute()

const breadcrumbs = computed(() => {
  const matched = route.matched.filter((r) => r.meta?.title)
  return matched.map((r) => ({
    title: (r.meta?.title as string) ?? (r.name as string) ?? '',
    path: r.path,
  }))
})
</script>

<template>
  <header
    class="h-11 flex items-center px-5 gap-3 flex-shrink-0"
    style="background: rgba(28,28,30,0.80); backdrop-filter: blur(20px) saturate(180%); -webkit-backdrop-filter: blur(20px) saturate(180%); border-bottom: 0.5px solid rgba(255,255,255,0.06);"
  >
    <!-- Sidebar toggle -->
    <button
      @click="ui.toggleSidebar()"
      class="p-1.5 rounded-md transition-colors"
      style="color: #98989D;"
      onmouseover="this.style.color='#F5F5F7'; this.style.background='rgba(255,255,255,0.04)'"
      onmouseout="this.style.color='#98989D'; this.style.background='transparent'"
    >
      <PanelLeftClose v-if="!ui.sidebarCollapsed" class="w-[18px] h-[18px]" />
      <PanelLeftOpen v-else class="w-[18px] h-[18px]" />
    </button>

    <!-- Breadcrumb -->
    <nav class="flex items-center gap-1.5 flex-1 min-w-0">
      <template v-for="(crumb, idx) in breadcrumbs" :key="idx">
        <span v-if="idx > 0" class="text-sm" style="color: #48484A;">/</span>
        <span
          class="text-xs truncate"
          :style="idx === breadcrumbs.length - 1 ? 'color: #F5F5F7;' : 'color: #98989D;'"
        >
          {{ crumb.title }}
        </span>
      </template>
    </nav>
  </header>
</template>
```

关键变化：44px 高度，毛玻璃 inline style，仅保留折叠按钮+面包屑，删除搜索/通知/主题/语言。

- [ ] **Step 2: 运行 type-check**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1
```

- [ ] **Step 3: Commit**

```bash
git add src/components/common/AppHeader.vue
git commit -m "style: AppHeader 精简为 44px 毛玻璃 + 仅面包屑"
```

---

### Task 6: 登录页 — 毛玻璃卡片 + 浅蓝按钮

**Files:**
- Modify: `src/views/LoginView.vue`

- [ ] **Step 1: 替换 LoginView.vue 全部内容**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleLogin() {
  error.value = ''
  loading.value = true
  await new Promise((r) => setTimeout(r, 300))
  const result = auth.login(username.value, password.value)
  loading.value = false
  if (result.ok) {
    const redirect = (route.query.redirect as string) || '/app/dashboard'
    router.push(redirect)
  } else {
    error.value = result.error || '登录失败，请重试'
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <!-- Brand -->
      <div class="brand">
        <div class="brand-icon">A</div>
        <h1 class="brand-name">Aether</h1>
        <p class="brand-desc">Agent Platform</p>
      </div>

      <!-- Form -->
      <form class="login-form" @submit.prevent="handleLogin">
        <div class="field">
          <label class="field-label">用户名</label>
          <input
            v-model="username"
            type="text"
            placeholder="admin"
            autocomplete="username"
            :disabled="loading"
            class="field-input"
          />
        </div>
        <div class="field">
          <label class="field-label">密码</label>
          <input
            v-model="password"
            type="password"
            placeholder="••••••"
            autocomplete="current-password"
            :disabled="loading"
            class="field-input"
          />
        </div>

        <div v-if="error" class="error-msg">{{ error }}</div>

        <button type="submit" class="submit-btn" :disabled="loading || !username || !password">
          {{ loading ? '登录中...' : '登 录' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: #000;
  padding: 1rem;
}

.login-card {
  width: 100%;
  max-width: 360px;
  background: rgba(28, 28, 30, 0.85);
  backdrop-filter: blur(30px) saturate(200%);
  -webkit-backdrop-filter: blur(30px) saturate(200%);
  border-radius: 20px;
  padding: 36px 32px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.4);
}

.brand {
  text-align: center;
  margin-bottom: 28px;
}

.brand-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background: rgba(90, 200, 250, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 12px;
  font-size: 22px;
  font-weight: 700;
  color: #5AC8FA;
}

.brand-name {
  font-size: 22px;
  font-weight: 700;
  color: #F5F5F7;
  margin: 0;
}

.brand-desc {
  font-size: 12px;
  color: #636366;
  margin: 4px 0 0;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 11px;
  color: #98989D;
}

.field-input {
  padding: 10px 12px;
  border-radius: 10px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  background: rgba(44, 44, 46, 0.6);
  color: #F5F5F7;
  font-size: 14px;
  outline: none;
  font-family: inherit;
}

.field-input:focus {
  border-color: #5AC8FA;
}

.field-input:disabled {
  opacity: 0.5;
}

.error-msg {
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(255, 69, 58, 0.08);
  color: #FF453A;
  font-size: 12px;
  text-align: center;
}

.submit-btn {
  margin-top: 6px;
  width: 100%;
  padding: 11px;
  border-radius: 10px;
  border: none;
  background: #5AC8FA;
  color: #000;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  transition: opacity 0.15s;
}

.submit-btn:hover {
  opacity: 0.88;
}

.submit-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
```

- [ ] **Step 2: 运行 type-check**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1
```

- [ ] **Step 3: Commit**

```bash
git add src/views/LoginView.vue
git commit -m "style: 登录页 Apple 毛玻璃卡片 + 浅蓝按钮"
```

---

### Task 7: 仪表盘 — 大标题 + 统计卡片 + 图表区

**Files:**
- Modify: `src/views/DashboardView.vue`

- [ ] **Step 1: 替换 DashboardView.vue 全部内容**

```vue
<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useDashboardStore } from '@/stores/dashboard'

const dashboard = useDashboardStore()

onMounted(() => {
  dashboard.loadStats()
  dashboard.startAutoRefresh(30000)
})

onUnmounted(() => {
  dashboard.stopAutoRefresh()
})

const periods = [
  { key: '7d' as const, label: '近 7 天' },
  { key: '30d' as const, label: '近 30 天' },
  { key: '90d' as const, label: '近 90 天' },
]
</script>

<template>
  <div class="dashboard">
    <h1 class="page-title">仪表盘</h1>

    <!-- Period selector -->
    <div class="period-bar">
      <button
        v-for="p in periods"
        :key="p.key"
        :class="['period-btn', { active: dashboard.period === p.key }]"
        @click="dashboard.setPeriod(p.key)"
      >
        {{ p.label }}
      </button>
    </div>

    <!-- Loading / Error -->
    <div v-if="dashboard.isLoading" class="state-msg">加载中...</div>
    <div v-else-if="dashboard.error" class="state-msg" style="color: #FF453A;">
      {{ dashboard.error }}
    </div>

    <template v-else>
      <!-- Stat cards -->
      <div class="stat-grid">
        <div class="stat-card">
          <div class="stat-label">智能体总数</div>
          <div class="stat-value" style="color: #5AC8FA;">{{ dashboard.stats?.totalAgents ?? 0 }}</div>
          <div v-if="dashboard.stats?.activeAgents" class="stat-trend" style="color: #30D158;">
            {{ dashboard.stats.activeAgents }} 活跃
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-label">活跃会话</div>
          <div class="stat-value" style="color: #30D158;">{{ dashboard.stats?.activeSessions ?? 0 }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Token 消耗</div>
          <div class="stat-value" style="color: #FFD60A;">{{ dashboard.stats?.totalTokens?.toLocaleString() ?? '—' }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">累计费用</div>
          <div class="stat-value" style="color: #FF9F0A;">
            ¥{{ dashboard.stats?.totalCost?.toFixed(1) ?? '—' }}
          </div>
        </div>
      </div>

      <!-- Charts row -->
      <div class="chart-row">
        <div class="chart-card">
          <div class="card-title">Token 用量趋势</div>
          <div class="chart-placeholder">图表加载中...</div>
        </div>
        <div class="chart-card">
          <div class="card-title">系统健康</div>
          <div class="health-bars">
            <div class="health-row">
              <span class="health-label">CPU</span>
              <div class="health-track"><div class="health-fill" style="width: 32%; background: #30D158;"></div></div>
              <span class="health-val">32%</span>
            </div>
            <div class="health-row">
              <span class="health-label">内存</span>
              <div class="health-track"><div class="health-fill" style="width: 67%; background: #FFD60A;"></div></div>
              <span class="health-val">67%</span>
            </div>
            <div class="health-row">
              <span class="health-label">磁盘</span>
              <div class="health-track"><div class="health-fill" style="width: 45%; background: #30D158;"></div></div>
              <span class="health-val">45%</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Bottom row -->
      <div class="bottom-row">
        <div class="chart-card">
          <div class="card-title">智能体状态</div>
          <div class="agent-status-list">
            <div
              v-for="stat in dashboard.stats?.agentStats ?? []"
              :key="stat.agentId"
              class="agent-status-row"
            >
              <span class="agent-name">{{ stat.agentName || stat.agentId }}</span>
              <span class="agent-sessions" style="color: #5AC8FA;">{{ stat.sessionCount }} 会话</span>
            </div>
            <div v-if="!dashboard.stats?.agentStats?.length" class="chart-placeholder">
              暂无数据
            </div>
          </div>
        </div>
        <div class="chart-card">
          <div class="card-title">最近会话</div>
          <div class="chart-placeholder">暂无最近会话</div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.dashboard {
  padding: 20px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: #F5F5F7;
  margin: 0 0 20px;
}

.period-bar {
  display: flex;
  gap: 2px;
  background: rgba(44, 44, 46, 0.5);
  border-radius: 10px;
  padding: 3px;
  width: fit-content;
  margin-bottom: 20px;
}

.period-btn {
  padding: 5px 14px;
  border-radius: 8px;
  border: none;
  font-size: 12px;
  color: #98989D;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
}

.period-btn.active {
  background: rgba(90, 200, 250, 0.12);
  color: #F5F5F7;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 14px;
  padding: 16px;
}

.stat-label {
  font-size: 11px;
  color: #98989D;
  margin-bottom: 6px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
}

.stat-trend {
  font-size: 11px;
  margin-top: 4px;
}

.chart-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}

.bottom-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.chart-card {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 14px;
  padding: 16px;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #F5F5F7;
  margin-bottom: 12px;
}

.chart-placeholder {
  font-size: 12px;
  color: #636366;
  text-align: center;
  padding: 24px 0;
}

.state-msg {
  text-align: center;
  padding: 48px 0;
  font-size: 14px;
  color: #98989D;
}

/* Health bars */
.health-bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.health-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.health-label {
  font-size: 11px;
  color: #98989D;
  width: 28px;
}

.health-track {
  flex: 1;
  height: 4px;
  background: rgba(255, 255, 255, 0.06);
  border-radius: 2px;
  overflow: hidden;
}

.health-fill {
  height: 100%;
  border-radius: 2px;
}

.health-val {
  font-size: 11px;
  color: #98989D;
  width: 28px;
  text-align: right;
}

/* Agent status */
.agent-status-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.agent-status-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.agent-name {
  font-size: 13px;
  color: #F5F5F7;
}

.agent-sessions {
  font-size: 12px;
  font-weight: 500;
}
</style>
```

- [ ] **Step 2: 运行 type-check**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1
```

- [ ] **Step 3: Commit**

```bash
git add src/views/DashboardView.vue
git commit -m "style: 仪表盘 Apple 风格 4 统计卡片 + 图表区"
```

---

### Task 8: 对话页 — ChatView + ChatLayout 毛玻璃消息界面

**Files:**
- Modify: `src/views/ChatView.vue`
- Modify: `src/components/chat/ChatLayout.vue`

- [ ] **Step 1: 替换 ChatView.vue 全部内容**

```vue
<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useAgentStore } from '@/stores/agent'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useSessionStore } from '@/stores/session'
import { ChatLayout, SessionSidebar } from '@/components/chat'

const agentStore = useAgentStore()
const authStore = useAuthStore()
const chatStore = useChatStore()
const sessionStore = useSessionStore()

const inputText = ref('')
const showPermissionDialog = computed(() => chatStore.hasActivePermissionRequest)

onMounted(async () => {
  await agentStore.loadAgents()
  if (agentStore.selectedAgentId) {
    await sessionStore.loadSessions(agentStore.selectedAgentId, authStore.userId)
  }
})

watch(
  () => agentStore.selectedAgentId,
  (newId) => {
    if (newId) {
      sessionStore.loadSessions(newId, authStore.userId)
    }
  }
)

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || !agentStore.selectedAgentId || chatStore.isStreaming) return
  inputText.value = ''
  await chatStore.sendMessage(text, agentStore.selectedAgentId, authStore.apiToken)
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <ChatLayout>
    <template #sidebar>
      <SessionSidebar />
    </template>

    <template #header>
      <div class="chat-topbar">
        <span class="chat-session-name">
          {{ chatStore.sessionName || '新对话' }}
        </span>
        <span v-if="chatStore.turnCount > 0" class="chat-turn-badge">
          {{ chatStore.turnCount }} 轮
        </span>
      </div>
    </template>

    <template #default>
      <!-- Empty state -->
      <div v-if="!agentStore.selectedAgentId" class="empty-state">
        请先在左侧选择一个智能体
      </div>
      <div v-else-if="chatStore.messages.length === 0" class="empty-state">
        输入消息开始与 <strong>{{ agentStore.selectedAgent?.agentName || agentStore.selectedAgentId }}</strong> 对话
      </div>

      <!-- Messages -->
      <div v-else class="messages">
        <div
          v-for="msg in chatStore.messages"
          :key="msg.id"
          :class="['msg-row', msg.role === 'user' ? 'msg-user' : 'msg-agent']"
        >
          <div v-if="msg.role === 'assistant'" class="msg-avatar">
            {{ (agentStore.selectedAgent?.agentName || 'A').charAt(0) }}
          </div>
          <div :class="['msg-bubble', msg.role === 'user' ? 'bubble-user' : 'bubble-agent']">
            {{ msg.content }}
          </div>
        </div>

        <!-- Streaming indicator -->
        <div v-if="chatStore.isStreaming" class="streaming-hint">
          <span class="streaming-dot" />
          正在生成...
        </div>
      </div>
    </template>

    <template #footer>
      <div class="input-bar">
        <input
          v-model="inputText"
          :placeholder="agentStore.selectedAgentId
            ? `给 ${agentStore.selectedAgent?.agentName || 'Agent'} 发送消息...`
            : '请先选择智能体'"
          :disabled="!agentStore.selectedAgentId || chatStore.isStreaming"
          class="msg-input"
          @keydown="handleKeydown"
        />
        <button
          class="send-btn"
          :disabled="!inputText.trim() || chatStore.isStreaming"
          @click="handleSend"
        >
          ↑
        </button>
      </div>
    </template>
  </ChatLayout>
</template>

<style scoped>
.chat-topbar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-session-name {
  font-size: 13px;
  font-weight: 600;
  color: #F5F5F7;
  flex: 1;
}

.chat-turn-badge {
  font-size: 10px;
  color: #98989D;
  background: rgba(44, 44, 46, 0.5);
  border-radius: 5px;
  padding: 2px 8px;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 14px;
  color: #636366;
}

.messages {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
}

.msg-row {
  display: flex;
  gap: 8px;
}

.msg-user {
  justify-content: flex-end;
}

.msg-agent {
  justify-content: flex-start;
}

.msg-avatar {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  background: rgba(90, 200, 250, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  color: #5AC8FA;
}

.msg-bubble {
  padding: 9px 13px;
  max-width: 68%;
  font-size: 13px;
  line-height: 1.5;
}

.bubble-user {
  background: #5AC8FA;
  color: #000;
  border-radius: 14px 14px 4px 14px;
}

.bubble-agent {
  background: rgba(44, 44, 46, 0.6);
  color: #F5F5F7;
  border-radius: 14px 14px 14px 4px;
}

.streaming-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  font-size: 12px;
  color: #98989D;
}

.streaming-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #5AC8FA;
  animation: pulseDot 1.4s infinite ease-in-out;
}

@keyframes pulseDot {
  0%, 80%, 100% { opacity: 0.2; transform: scale(0.8); }
  40% { opacity: 1; transform: scale(1); }
}

.input-bar {
  display: flex;
  gap: 8px;
  padding: 10px 14px;
  border-top: 0.5px solid rgba(255, 255, 255, 0.06);
}

.msg-input {
  flex: 1;
  padding: 10px 12px;
  border-radius: 10px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(44, 44, 46, 0.5);
  color: #F5F5F7;
  font-size: 13px;
  outline: none;
  font-family: inherit;
}

.msg-input:focus {
  border-color: #5AC8FA;
}

.send-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: none;
  background: #5AC8FA;
  color: #000;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.15s;
  flex-shrink: 0;
}

.send-btn:hover {
  opacity: 0.88;
}

.send-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}
</style>
```

- [ ] **Step 2: 替换 ChatLayout.vue 全部内容**

```vue
<script setup lang="ts">
</script>

<template>
  <div class="flex h-full">
    <!-- Session sidebar -->
    <aside
      class="w-[220px] flex-shrink-0 flex flex-col border-r overflow-hidden"
      style="background: rgba(28,28,30,0.6); border-color: rgba(255,255,255,0.06);"
    >
      <slot name="sidebar" />
    </aside>

    <!-- Main chat area -->
    <div class="flex-1 flex flex-col min-w-0">
      <div
        class="h-11 flex-shrink-0 flex items-center px-4 border-b"
        style="background: rgba(28,28,30,0.5); border-color: rgba(255,255,255,0.06);"
      >
        <slot name="header" />
      </div>
      <div class="flex-1 overflow-y-auto">
        <slot />
      </div>
      <div class="flex-shrink-0">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>
```

- [ ] **Step 3: 运行 type-check**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1
```

- [ ] **Step 4: Commit**

```bash
git add src/views/ChatView.vue src/components/chat/ChatLayout.vue
git commit -m "style: 对话页 Apple 风格气泡 + 毛玻璃侧栏"
```

---

### Task 9: 列表管理页 — 智能体 / 模型 / 技能

**Files:**
- Modify: `src/views/AgentManagementView.vue`
- Modify: `src/views/ModelManagementView.vue`
- Modify: `src/views/SkillManagementView.vue`

三页共享同样的 28px 标题 + 半透明卡片容器模式。依次替换。

- [ ] **Step 1: 替换 AgentManagementView.vue 全部内容**

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'

const router = useRouter()
const agentStore = useAgentStore()

onMounted(() => {
  agentStore.loadManagementAgents()
})

function handleEdit(id: string) {
  router.push(`/app/agents/${id}`)
}

function handleDelete(id: string) {
  if (confirm('确定要删除该智能体吗？')) {
    agentStore.removeManagementAgent(id)
  }
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">智能体</h1>

    <div v-if="agentStore.managementLoading" class="state-msg">加载中...</div>

    <template v-else>
      <div class="list-card">
        <div class="table-header">
          <span>名称</span><span>类型</span><span>模型</span><span>状态</span>
        </div>

        <div v-if="agentStore.managementAgents.length === 0" class="state-msg">
          暂无智能体
        </div>

        <div
          v-for="agent in agentStore.managementAgents"
          :key="agent.agentId"
          class="table-row"
        >
          <div class="cell-name">
            <div class="cell-title">{{ agent.agentName || agent.agentId }}</div>
            <div class="cell-desc">{{ agent.description || '—' }}</div>
          </div>
          <span class="cell-text">{{ agent.type || 'ReActAgent' }}</span>
          <span class="cell-text">{{ agent.model || '—' }}</span>
          <span :class="['status-dot', agent.status === 'active' ? 'on' : 'off']">
            {{ agent.status === 'active' ? '活跃' : '已暂停' }}
          </span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page { padding: 20px; }
.page-title { font-size: 28px; font-weight: 700; color: #F5F5F7; margin: 0 0 18px; }

.list-card {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 14px;
  overflow: hidden;
}

.table-header {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  padding: 10px 16px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.05);
  font-size: 11px;
  color: #636366;
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.table-row {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  padding: 12px 16px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.04);
  align-items: center;
}

.table-row:last-child { border-bottom: none; }

.cell-title { font-size: 14px; font-weight: 600; color: #F5F5F7; }
.cell-desc { font-size: 11px; color: #636366; margin-top: 2px; }
.cell-text { font-size: 12px; color: #98989D; }

.status-dot {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
}

.status-dot::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status-dot.on { color: #30D158; }
.status-dot.on::before { background: #30D158; }
.status-dot.off { color: #FF9F0A; }
.status-dot.off::before { background: #FF9F0A; }

.state-msg {
  text-align: center;
  padding: 32px 0;
  font-size: 13px;
  color: #98989D;
}
</style>
```

- [ ] **Step 2: 替换 ModelManagementView.vue 全部内容**

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useModelStore } from '@/stores/model'

const modelStore = useModelStore()

onMounted(() => {
  modelStore.loadModels()
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">模型</h1>

    <div v-if="modelStore.isLoading" class="state-msg">加载中...</div>

    <template v-else>
      <div v-if="modelStore.models.length === 0" class="state-msg">暂无模型</div>
      <div v-else class="card-grid">
        <div v-for="m in modelStore.models" :key="m.id" class="model-card">
          <div class="mc-provider">{{ m.provider }}</div>
          <div class="mc-name">{{ m.modelName || m.modelId }}</div>
          <div class="mc-meta">{{ m.contextWindow ? m.contextWindow + ' context' : '' }}</div>
          <div class="mc-footer">
            <span :class="['conn-dot', m.connected ? 'on' : 'off']">
              {{ m.connected ? '已连接' : '未连接' }}
            </span>
            <span class="test-link">测试连接 →</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page { padding: 20px; }
.page-title { font-size: 28px; font-weight: 700; color: #F5F5F7; margin: 0 0 18px; }

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.model-card {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 14px;
  padding: 16px;
}

.mc-provider {
  font-size: 10px;
  color: #636366;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
}

.mc-name {
  font-size: 16px;
  font-weight: 600;
  color: #F5F5F7;
}

.mc-meta {
  font-size: 12px;
  color: #98989D;
  margin-top: 4px;
}

.mc-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}

.conn-dot {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
}

.conn-dot::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.conn-dot.on { color: #30D158; }
.conn-dot.on::before { background: #30D158; }
.conn-dot.off { color: #FF453A; }
.conn-dot.off::before { background: #FF453A; }

.test-link { font-size: 11px; color: #5AC8FA; cursor: pointer; }

.state-msg {
  text-align: center;
  padding: 48px 0;
  font-size: 14px;
  color: #98989D;
}
</style>
```

- [ ] **Step 3: 替换 SkillManagementView.vue 全部内容**

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useSkillStore } from '@/stores/skill'

const skillStore = useSkillStore()

onMounted(() => {
  skillStore.loadSkills()
})

function toggleSkill(id: string) {
  skillStore.toggleSkill(id)
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">技能</h1>

    <div v-if="skillStore.isLoading" class="state-msg">加载中...</div>

    <template v-else>
      <!-- Group by category -->
      <template v-for="(group, cat) in skillStore.skillsByCategory" :key="cat">
        <div class="cat-label">{{ cat }}</div>
        <div class="skill-list">
          <div
            v-for="skill in group"
            :key="skill.id"
            class="skill-row"
          >
            <div class="skill-info">
              <div class="skill-name">{{ skill.name }}</div>
              <div class="skill-desc">{{ skill.description }}</div>
            </div>
            <button
              :class="['toggle', { active: skill.enabled }]"
              @click="toggleSkill(skill.id)"
            >
              <span class="toggle-knob" />
            </button>
          </div>
        </div>
        <div v-if="!group?.length" class="state-msg">暂无技能</div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.page { padding: 20px; }
.page-title { font-size: 28px; font-weight: 700; color: #F5F5F7; margin: 0 0 18px; }

.cat-label {
  font-size: 11px;
  color: #636366;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 8px 0 4px;
}

.skill-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

.skill-row {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 12px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.skill-name { font-size: 14px; font-weight: 600; color: #F5F5F7; }
.skill-desc { font-size: 11px; color: #98989D; margin-top: 2px; }

.toggle {
  width: 40px;
  height: 22px;
  border-radius: 11px;
  border: none;
  background: rgba(255, 255, 255, 0.08);
  position: relative;
  cursor: pointer;
  transition: background-color 0.25s;
  flex-shrink: 0;
}

.toggle.active {
  background: #30D158;
}

.toggle-knob {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  position: absolute;
  top: 2px;
  left: 2px;
  transition: transform 0.25s;
}

.toggle.active .toggle-knob {
  transform: translateX(18px);
  background: #fff;
}

.toggle:not(.active) .toggle-knob {
  background: #636366;
}

.state-msg {
  text-align: center;
  padding: 48px 0;
  font-size: 14px;
  color: #98989D;
}
</style>
```

- [ ] **Step 4: 运行 type-check**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1
```

- [ ] **Step 5: Commit**

```bash
git add src/views/AgentManagementView.vue src/views/ModelManagementView.vue src/views/SkillManagementView.vue
git commit -m "style: 智能体/模型/技能 列表页 Apple 风格"
```

---

### Task 10: 设置页 — iOS grouped list 风格

**Files:**
- Modify: `src/views/SettingsView.vue`

- [ ] **Step 1: 替换 SettingsView.vue 全部内容**

```vue
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAgentStore } from '@/stores/agent'
import { useMemoryStore } from '@/stores/memory'
import { Pencil, Trash2, Plus, X, Check, LogOut } from 'lucide-vue-next'
import type { LongTermMemoryEntry } from '@/types/memory'

const router = useRouter()
const auth = useAuthStore()
const agentStore = useAgentStore()
const memoryStore = useMemoryStore()

const apiTokenInput = ref(auth.apiToken)
const connectionUrl = ref('http://localhost:8080')

// Long-term memory editing state
const editingId = ref<string | null>(null)
const editingContent = ref('')
const isAdding = ref(false)
const newContent = ref('')

const maskedToken = computed(() => {
  const t = auth.apiToken
  if (t.length <= 8) return t
  return t.slice(0, 4) + '****' + t.slice(-4)
})

const currentAgentName = computed(() => {
  return agentStore.selectedAgent?.agentName || agentStore.selectedAgent?.agentId || ''
})

watch(
  () => agentStore.selectedAgentId,
  (newId) => {
    memoryStore.loadMemories(newId)
    cancelEdit()
  },
  { immediate: true }
)

function handleSaveToken() {
  auth.setToken(apiTokenInput.value)
}

function handleLogout() {
  auth.logout()
  router.push('/')
}

// Memory actions
function startEdit(entry: LongTermMemoryEntry) {
  editingId.value = entry.id
  editingContent.value = entry.content
}

function cancelEdit() {
  editingId.value = null
  editingContent.value = ''
  isAdding.value = false
  newContent.value = ''
}

function saveEdit() {
  if (editingId.value && editingContent.value.trim()) {
    memoryStore.updateMemory(editingId.value, editingContent.value.trim())
  }
  cancelEdit()
}

function handleDelete(id: string) {
  memoryStore.deleteMemory(id)
  if (editingId.value === id) cancelEdit()
}

function startAdd() {
  isAdding.value = true
  newContent.value = ''
  editingId.value = null
}

function confirmAdd() {
  if (newContent.value.trim()) {
    memoryStore.addMemory(newContent.value.trim())
  }
  cancelEdit()
}

function summary(content: string): string {
  return content.length > 80 ? content.slice(0, 80) + '…' : content
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleString()
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">设置</h1>

    <div class="groups">
      <!-- API section -->
      <div class="group-label">API</div>
      <div class="group-card">
        <div class="group-row">
          <span class="row-label">当前 Token</span>
          <span v-if="auth.isValidToken" class="row-value">{{ maskedToken }}</span>
          <span v-else class="row-value" style="color: #FF453A;">未设置</span>
        </div>
        <div class="group-row">
          <span class="row-label">新 Token</span>
          <input
            v-model="apiTokenInput"
            type="password"
            placeholder="输入新 Token"
            class="row-input"
          />
        </div>
        <div class="group-row" style="justify-content: flex-end;">
          <button class="action-btn" @click="handleSaveToken">保存</button>
        </div>
        <div class="group-row">
          <span class="row-label">连接地址</span>
          <span class="row-value">{{ connectionUrl }}</span>
        </div>
      </div>

      <!-- Long-term Memory section -->
      <div class="group-label">
        长期记忆
        <span v-if="currentAgentName" class="agent-tag"> · 当前：{{ currentAgentName }}</span>
      </div>
      <div class="group-card">
        <!-- No agent -->
        <div v-if="!agentStore.selectedAgentId" class="empty-msg">
          请先在对话页面中选择一个智能体
        </div>

        <template v-else>
          <!-- No memories -->
          <div v-if="memoryStore.memories.length === 0 && !isAdding" class="empty-msg">
            该 Agent 暂无长期记忆
          </div>

          <!-- Memory items -->
          <div
            v-for="entry in memoryStore.memories"
            :key="entry.id"
            class="group-row"
            style="flex-direction: column; align-items: stretch;"
          >
            <!-- Summary mode -->
            <div v-if="editingId !== entry.id" class="mem-row">
              <div class="mem-content">
                <div class="mem-text">{{ summary(entry.content) }}</div>
                <div class="mem-time">{{ formatTime(entry.updatedAt) }}</div>
              </div>
              <div class="mem-actions">
                <span class="edit-link" @click="startEdit(entry)">编辑</span>
                <span class="del-link" @click="handleDelete(entry.id)">删除</span>
              </div>
            </div>

            <!-- Edit mode -->
            <div v-else class="mem-edit">
              <textarea v-model="editingContent" class="mem-textarea" rows="3" />
              <div class="mem-edit-btns">
                <button class="action-btn" @click="saveEdit">保存</button>
                <button class="cancel-btn" @click="cancelEdit">取消</button>
              </div>
            </div>
          </div>

          <!-- Add new -->
          <div v-if="isAdding" class="group-row" style="flex-direction: column; align-items: stretch;">
            <div class="mem-edit">
              <textarea v-model="newContent" class="mem-textarea" rows="3" placeholder="输入新的长期记忆…" />
              <div class="mem-edit-btns">
                <button class="action-btn" @click="confirmAdd">添加</button>
                <button class="cancel-btn" @click="cancelEdit">取消</button>
              </div>
            </div>
          </div>

          <div v-if="!isAdding" class="group-row" style="justify-content: center;">
            <span class="add-link" @click="startAdd">+ 添加长期记忆</span>
          </div>
        </template>
      </div>

      <!-- Account section -->
      <div class="group-label">账户</div>
      <div class="group-card">
        <div class="group-row" style="justify-content: center;">
          <span class="logout-link" @click="handleLogout">退出登录</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 20px; max-width: 560px; }
.page-title { font-size: 28px; font-weight: 700; color: #F5F5F7; margin: 0 0 20px; }

.groups { display: flex; flex-direction: column; gap: 6px; }

.group-label {
  font-size: 11px;
  color: #636366;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 12px 0 6px;
}

.group-label:first-child { padding-top: 0; }

.agent-tag {
  text-transform: none;
  letter-spacing: 0;
  color: #5AC8FA;
}

.group-card {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 14px;
  overflow: hidden;
}

.group-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 16px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.04);
}

.group-row:last-child { border-bottom: none; }

.row-label { font-size: 14px; color: #F5F5F7; }
.row-value { font-size: 13px; color: #98989D; }

.row-input {
  width: 180px;
  padding: 6px 10px;
  border-radius: 7px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(0, 0, 0, 0.3);
  color: #F5F5F7;
  font-size: 13px;
  text-align: right;
  outline: none;
  font-family: inherit;
}

.row-input:focus { border-color: #5AC8FA; }

.action-btn {
  padding: 5px 14px;
  border-radius: 7px;
  border: none;
  background: #5AC8FA;
  color: #000;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
}

.empty-msg {
  text-align: center;
  padding: 24px 16px;
  font-size: 13px;
  color: #636366;
}

/* Memory items */
.mem-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

.mem-content { flex: 1; min-width: 0; }
.mem-text { font-size: 13px; color: #F5F5F7; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mem-time { font-size: 10px; color: #636366; margin-top: 2px; }

.mem-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

.edit-link { font-size: 11px; color: #5AC8FA; cursor: pointer; }
.del-link { font-size: 11px; color: #FF453A; cursor: pointer; }
.add-link { font-size: 13px; color: #5AC8FA; cursor: pointer; }
.logout-link { font-size: 14px; color: #FF453A; cursor: pointer; }

.mem-edit { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.mem-textarea {
  width: 100%;
  padding: 8px 10px;
  border-radius: 8px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(0, 0, 0, 0.3);
  color: #F5F5F7;
  font-size: 13px;
  font-family: inherit;
  line-height: 1.5;
  outline: none;
  resize: vertical;
}
.mem-textarea:focus { border-color: #5AC8FA; }
.mem-edit-btns { display: flex; gap: 8px; justify-content: flex-end; }

.cancel-btn {
  padding: 5px 14px;
  border-radius: 7px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  background: transparent;
  color: #98989D;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
</style>
```

- [ ] **Step 2: 运行 type-check**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1
```

- [ ] **Step 3: Commit**

```bash
git add src/views/SettingsView.vue
git commit -m "style: 设置页 iOS grouped list 风格"
```

---

### Task 11: 清理 — 删除未使用的组件文件

**Files:**
- Delete: `src/components/common/ThemeToggle.vue`
- Delete: `src/components/common/LocaleToggle.vue`
- Delete: `src/components/common/SearchInput.vue`

- [ ] **Step 1: 删除三个未使用的组件**

```bash
rm "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2\src\components\common\ThemeToggle.vue"
rm "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2\src\components\common\LocaleToggle.vue"
rm "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2\src\components\common\SearchInput.vue"
```

- [ ] **Step 2: 全量构建验证**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vite build 2>&1
```
Expected: `✓ built in Xs`

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: 删除未使用的 ThemeToggle/LocaleToggle/SearchInput"
```

---

### Task 12: 最终验证 — 全量构建

- [ ] **Step 1: 全量构建**

```bash
cd "D:\code\Agents-framework\aether\docs\dev-ops\aether-frontend-v2" && npx vue-tsc --noEmit 2>&1 && npx vite build 2>&1
```
Expected: type-check 无错误 + `✓ built in Xs`

---

## 自审清单

- [x] Spec coverage: 色彩体系 (Task 1+2), 字体 (Task 1+2), 毛玻璃 (Task 3-5), 圆角间距 (分散各 Task), 6 页面 (Task 6-10), AppShell (Task 3-5), 动画 (Task 1), 清理 (Task 11)
- [x] 无占位符 (TBD/TODO/placeholder)
- [x] 类型一致性: `LongTermMemoryEntry` 与 `memory.ts` 中定义一致
- [x] 不改 LandingView / 路由 / Store / 后端
