# Landing 导航栏 & 页面结构改造实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于 MateClaw 导航栏风格改造 Aether landing 页面的导航栏、内容 section、并新增路线图与文档子页面

**Architecture:** 最小修改策略 — 仅改 3 个文件（LandingNavbar、LandingView、routes.ts），新建 5 个组件/视图。所有新组件复用项目现有的 TailwindCSS 类名体系、`@vueuse/motion` 动效指令和 `LandingButton`/`AetherLogo` 组件。全局字体已由 `style.css` 设为微软雅黑，新组件无需额外设置。

**Tech Stack:** Vue 3 + TypeScript + TailwindCSS + @vueuse/motion + Pinia + vue-router (hash mode)

**Spec:** `docs/superpowers/specs/2026-08-03-landing-navbar-redesign.md`

---

## File Structure

```
src/
├── components/landing/
│   ├── LandingNavbar.vue          ← 修改：菜单项/交互重写
│   ├── HighlightsSection.vue      ← 新建：亮点卡片
│   ├── ArchitectureSection.vue    ← 新建：架构分层
│   └── PreviewSection.vue         ← 新建：产品预览
├── views/
│   ├── LandingView.vue            ← 修改：替换/删除 section 引用
│   ├── RoadmapView.vue            ← 新建：路线图时间线
│   └── DocsView.vue               ← 新建：文档首页
└── router/
    └── routes.ts                  ← 修改：新增 2 条路由
```

---

### Task 1: 重写 LandingNavbar.vue

**Files:**
- Modify: `src/components/landing/LandingNavbar.vue`

- [ ] **Step 1: 替换整个 `<script setup>` 和 `<template>` 块**

用以下完整内容替换 `LandingNavbar.vue`：

```vue
<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Menu, X, Github } from 'lucide-vue-next'
import AetherLogo from './AetherLogo.vue'
import LandingButton from './LandingButton.vue'

const NAV_ITEMS = [
  { label: '亮点',     href: '#highlights',   type: 'anchor' as const },
  { label: '架构',     href: '#architecture',  type: 'anchor' as const },
  { label: '产品预览', href: '#preview',       type: 'anchor' as const },
  { label: '路线图',   href: '/roadmap',       type: 'route' as const },
  { label: '文档',     href: '/docs',          type: 'external' as const },
]

const router = useRouter()
const route = useRoute()
const mobileOpen = ref(false)
const scrolled = ref(false)
const activeAnchor = ref('')

const emit = defineEmits<{
  cta: []
}>()

function handleNavClick(item: typeof NAV_ITEMS[number], e: Event) {
  mobileOpen.value = false

  if (item.type === 'anchor') {
    e.preventDefault()
    const el = document.querySelector(item.href)
    if (el) el.scrollIntoView({ behavior: 'smooth' })
    return
  }

  if (item.type === 'route') {
    e.preventDefault()
    router.push(item.href)
    return
  }

  if (item.type === 'external') {
    e.preventDefault()
    window.open(item.href, '_blank')
    return
  }
}

function isActive(item: typeof NAV_ITEMS[number]): boolean {
  if (item.type === 'route') return route.path === item.href
  if (item.type === 'anchor') return activeAnchor.value === item.href
  return false
}

function onScroll() {
  scrolled.value = window.scrollY > 0

  const anchors = NAV_ITEMS.filter(i => i.type === 'anchor').map(i => i.href)
  for (let i = anchors.length - 1; i >= 0; i--) {
    const el = document.querySelector(anchors[i])
    if (el) {
      const rect = el.getBoundingClientRect()
      if (rect.top <= 120) {
        activeAnchor.value = anchors[i]
        return
      }
    }
  }
  activeAnchor.value = ''
}

onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }))
onUnmounted(() => window.removeEventListener('scroll', onScroll))
</script>

<template>
  <header
    v-motion
    :initial="{ opacity: 0, y: -10 }"
    :enter="{ opacity: 1, y: 0, transition: { duration: 600, ease: 'easeOut' } }"
    class="fixed top-0 left-0 right-0 z-50 transition-all duration-300"
    :class="{ 'bg-black/70 backdrop-blur-xl border-b border-white/10': scrolled }"
  >
    <div class="nav-inner flex items-center justify-between px-6 md:px-10 h-16">
      <!-- Logo -->
      <a href="#" aria-label="Aether home" class="flex items-center gap-3 group">
        <AetherLogo className="w-8 h-8 text-white" />
        <span class="font-black text-lg tracking-tight text-white">Aether</span>
      </a>

      <!-- Desktop Nav -->
      <nav class="hidden md:flex items-center gap-1">
        <a
          v-for="item in NAV_ITEMS"
          :key="item.label"
          :href="item.href"
          @click="handleNavClick(item, $event)"
          class="nav-link text-sm font-medium px-3.5 py-2 rounded-lg transition-all duration-200"
          :class="isActive(item)
            ? 'text-white bg-white/10'
            : 'text-white/60 hover:text-white hover:bg-white/5'"
        >
          {{ item.label }}
        </a>
      </nav>

      <!-- Right actions -->
      <div class="flex items-center gap-2">
        <!-- GitHub -->
        <a
          href="https://github.com/your-org/aether"
          target="_blank"
          rel="noopener"
          class="hidden sm:flex w-9 h-9 rounded-lg items-center justify-center transition-all duration-200 text-white/60 hover:text-white hover:bg-white/5"
          aria-label="GitHub"
        >
          <Github class="w-4 h-4" />
        </a>

        <!-- CTA -->
        <div class="hidden md:block">
          <LandingButton label="开始使用" @click="emit('cta')" />
        </div>

        <!-- Mobile menu button -->
        <button
          type="button"
          class="md:hidden w-9 h-9 rounded-lg flex items-center justify-center transition-all duration-200 text-white/60 hover:text-white hover:bg-white/5"
          :aria-label="mobileOpen ? 'Close menu' : 'Open menu'"
          @click="mobileOpen = !mobileOpen"
        >
          <X v-if="mobileOpen" class="w-4 h-4" />
          <Menu v-else class="w-4 h-4" />
        </button>
      </div>
    </div>

    <!-- Mobile menu -->
    <Transition name="slide-down">
      <div
        v-if="mobileOpen"
        class="md:hidden border-t border-white/10 bg-black/90 backdrop-blur-xl"
      >
        <div class="px-6 py-4 flex flex-col gap-1">
          <a
            v-for="item in NAV_ITEMS"
            :key="item.label"
            :href="item.href"
            @click="handleNavClick(item, $event)"
            class="mobile-nav-link text-sm font-medium px-3 py-2.5 rounded-lg transition-all duration-200"
            :class="isActive(item)
              ? 'text-white bg-white/10'
              : 'text-white/60 hover:text-white hover:bg-white/5'"
          >
            {{ item.label }}
          </a>
          <div class="pt-3 mt-2 border-t border-white/10">
            <LandingButton label="开始使用" full @click="mobileOpen = false; emit('cta')" />
          </div>
        </div>
      </div>
    </Transition>
  </header>
</template>

<style scoped>
.slide-down-enter-active {
  transition: all 0.25s ease-out;
}
.slide-down-leave-active {
  transition: all 0.2s ease-in;
}
.slide-down-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}
.slide-down-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
```

- [ ] **Step 2: 验证构建**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vite build --mode development 2>&1 | tail -5
```

Expected: `✓ built in ...` 无报错

- [ ] **Step 3: 提交**

```bash
git add src/components/landing/LandingNavbar.vue
git commit -m "重写 LandingNavbar：5 导航项 + GitHub + 滚动背景 + 移动端折叠 + 高亮"
```

---

### Task 2: 创建 HighlightsSection.vue

**Files:**
- Create: `src/components/landing/HighlightsSection.vue`

- [ ] **Step 1: 创建组件文件**

```vue
<script setup lang="ts">
const highlights = [
  {
    icon: '🔄',
    title: '自研 ReActAgent 引擎',
    desc: '主循环 + 多层上下文压缩 + 指数退避重试，100 轮稳定运行，连续工具失败自动熔断',
    color: 'border-l-[#5AC8FA]',
  },
  {
    icon: '📊',
    title: 'YAML 配置驱动多 Agent 编排',
    desc: 'SEQUENTIAL / PARALLEL / LOOP 三种工作流模式，声明式定义 Agent 协作拓扑',
    color: 'border-l-[#30D158]',
  },
  {
    icon: '🔧',
    title: 'Agent 级工具作用域',
    desc: 'MCP + Skills 工具接入，per-agent 精确控制工具可见性，支持并发安全工作编排',
    color: 'border-l-[#FF9F0A]',
  },
  {
    icon: '🏗️',
    title: 'DDD 分层架构',
    desc: '6 模块清晰分层：trigger → api → domain → infrastructure，禁止反向依赖',
    color: 'border-l-[#BF5AF2]',
  },
  {
    icon: '⚡',
    title: '流式对话 & 工具编排',
    desc: 'SSE 实时推送，工具并发/串行可配，上下文自动压缩，Provider 级重试策略',
    color: 'border-l-[#FF453A]',
  },
]
</script>

<template>
  <section id="highlights" class="max-w-6xl mx-auto px-6 py-20 md:py-32">
    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
    >
      <h2 class="text-3xl md:text-5xl font-semibold tracking-tight text-white text-center mb-4">
        Aether 核心亮点
      </h2>
      <p class="text-white/60 text-center max-w-lg mx-auto mb-16 text-sm leading-relaxed">
        从单 Agent 到多 Agent 集群，Aether 提供企业级 AI 运行时所需的一切
      </p>
    </div>

    <div class="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="(item, i) in highlights"
        :key="item.title"
        v-motion
        :initial="{ opacity: 0, y: 24 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: i * 0.1, duration: 600, ease: [0.22, 1, 0.36, 1] } }"
        class="bg-white/[0.03] border border-white/10 rounded-2xl p-6 hover:bg-white/[0.06] transition-colors duration-300"
        :class="item.color"
      >
        <span class="text-2xl">{{ item.icon }}</span>
        <h3 class="text-white font-semibold mt-4 mb-2 text-base">{{ item.title }}</h3>
        <p class="text-white/50 text-sm leading-relaxed">{{ item.desc }}</p>
      </div>
    </div>
  </section>
</template>
```

- [ ] **Step 2: 验证构建**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vite build --mode development 2>&1 | tail -5
```

Expected: `✓ built in ...` 无报错（组件新建不影响构建，因为没有引用它）

- [ ] **Step 3: 提交**

```bash
git add src/components/landing/HighlightsSection.vue
git commit -m "新建 HighlightsSection：5 张特性卡片展示 Aether 核心亮点"
```

---

### Task 3: 创建 ArchitectureSection.vue

**Files:**
- Create: `src/components/landing/ArchitectureSection.vue`

- [ ] **Step 1: 创建组件文件**

```vue
<script setup lang="ts">
const domainComponents = [
  { name: 'ReActAgent', desc: '主循环 · 重试 · 熔断' },
  { name: 'GraphExecutor', desc: '顺序 · 并行 · 循环' },
  { name: 'ContextManager', desc: '压缩 · 截断 · 预算' },
  { name: 'ToolExecutor', desc: '并发 · 串行 · 适配' },
  { name: 'ArmoryService', desc: 'YAML → Bean 装配' },
  { name: 'MemoryStore', desc: '持久记忆读写' },
]
</script>

<template>
  <section id="architecture" class="max-w-6xl mx-auto px-6 py-20 md:py-32">
    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
    >
      <h2 class="text-3xl md:text-5xl font-semibold tracking-tight text-white text-center mb-4">
        系统架构
      </h2>
      <p class="text-white/60 text-center max-w-lg mx-auto mb-16 text-sm leading-relaxed">
        DDD 6 模块分层，自研 ReActAgent 引擎驱动
      </p>
    </div>

    <div class="max-w-2xl mx-auto space-y-4">
      <!-- Trigger -->
      <div
        v-motion
        :initial="{ opacity: 0, x: -20 }"
        :visible="{ opacity: 1, x: 0, transition: { delay: 0.1, duration: 600 } }"
        class="bg-white/[0.03] border border-white/10 rounded-xl p-5"
      >
        <div class="flex items-center gap-3 mb-2">
          <span class="text-xs font-bold px-2 py-0.5 rounded bg-[#5AC8FA] text-black">Trigger</span>
          <span class="text-white font-semibold text-sm">aether-trigger</span>
        </div>
        <p class="text-white/50 text-xs">REST 控制器层，Chat API / SSE 流式端点</p>
      </div>

      <div class="text-center text-white/20 text-sm">↓</div>

      <!-- API -->
      <div
        v-motion
        :initial="{ opacity: 0, x: -20 }"
        :visible="{ opacity: 1, x: 0, transition: { delay: 0.2, duration: 600 } }"
        class="bg-white/[0.03] border border-white/10 rounded-xl p-5"
      >
        <div class="flex items-center gap-3 mb-2">
          <span class="text-xs font-bold px-2 py-0.5 rounded bg-[#30D158] text-black">API</span>
          <span class="text-white font-semibold text-sm">aether-api</span>
        </div>
        <p class="text-white/50 text-xs">服务接口 & DTO 定义，跨模块契约</p>
      </div>

      <div class="text-center text-white/20 text-sm">↓</div>

      <!-- Domain Core -->
      <div
        v-motion
        :initial="{ opacity: 0, x: -20 }"
        :visible="{ opacity: 1, x: 0, transition: { delay: 0.3, duration: 600 } }"
        class="bg-white/[0.03] border-2 border-[#FF9F0A]/40 rounded-xl p-5"
      >
        <div class="flex items-center gap-3 mb-4">
          <span class="text-xs font-bold px-2 py-0.5 rounded bg-[#FF9F0A] text-black">Domain Core</span>
          <span class="text-white font-semibold text-sm">aether-domain</span>
        </div>
        <div class="grid grid-cols-2 sm:grid-cols-3 gap-3">
          <div
            v-for="comp in domainComponents"
            :key="comp.name"
            class="bg-white/[0.05] rounded-lg p-3 text-center"
          >
            <div class="text-white text-xs font-semibold">{{ comp.name }}</div>
            <div class="text-white/40 text-[10px] mt-1">{{ comp.desc }}</div>
          </div>
        </div>
      </div>

      <div class="text-center text-white/20 text-sm">↓</div>

      <!-- Infra + Types -->
      <div
        v-motion
        :initial="{ opacity: 0, x: -20 }"
        :visible="{ opacity: 1, x: 0, transition: { delay: 0.4, duration: 600 } }"
        class="grid grid-cols-2 gap-4"
      >
        <div class="bg-white/[0.03] border border-white/10 rounded-xl p-5">
          <div class="flex items-center gap-3 mb-2">
            <span class="text-xs font-bold px-2 py-0.5 rounded bg-[#BF5AF2] text-white">Infra</span>
            <span class="text-white font-semibold text-sm">aether-infrastructure</span>
          </div>
          <p class="text-white/50 text-xs">SessionStore · MCP Client · Adapter</p>
        </div>
        <div class="bg-white/[0.03] border border-white/10 rounded-xl p-5">
          <div class="flex items-center gap-3 mb-2">
            <span class="text-xs font-bold px-2 py-0.5 rounded bg-white/20 text-white">Types</span>
            <span class="text-white font-semibold text-sm">aether-types</span>
          </div>
          <p class="text-white/50 text-xs">枚举 · 异常 · 常量定义</p>
        </div>
      </div>
    </div>
  </section>
</template>
```

- [ ] **Step 2: 验证构建**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vite build --mode development 2>&1 | tail -5
```

Expected: `✓ built in ...` 无报错

- [ ] **Step 3: 提交**

```bash
git add src/components/landing/ArchitectureSection.vue
git commit -m "新建 ArchitectureSection：4 层模块卡片展示系统架构"
```

---

### Task 4: 创建 PreviewSection.vue

**Files:**
- Create: `src/components/landing/PreviewSection.vue`

- [ ] **Step 1: 创建组件文件**

```vue
<script setup lang="ts">
const previews = [
  {
    title: '仪表盘',
    desc: 'Token 用量 / Agent 状态 / 会话趋势',
    accent: 'border-[#5AC8FA]',
    bg: 'bg-[#5AC8FA]/10',
    label: '[仪表盘截图占位]',
  },
  {
    title: '多 Agent 对话',
    desc: '流式输出 / 工具调用可视化 / 会话管理',
    accent: 'border-[#30D158]',
    bg: 'bg-[#30D158]/10',
    label: '[对话界面截图占位]',
  },
  {
    title: 'Agent 管理',
    desc: 'YAML 配置 / 工具绑定 / 权限控制',
    accent: 'border-[#FF9F0A]',
    bg: 'bg-[#FF9F0A]/10',
    label: '[Agent 配置截图占位]',
  },
]
</script>

<template>
  <section id="preview" class="max-w-6xl mx-auto px-6 py-20 md:py-32">
    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
    >
      <h2 class="text-3xl md:text-5xl font-semibold tracking-tight text-white text-center mb-4">
        产品预览
      </h2>
      <p class="text-white/60 text-center max-w-lg mx-auto mb-16 text-sm leading-relaxed">
        Aether 控制台界面一览
      </p>
    </div>

    <div class="grid gap-8 md:grid-cols-3">
      <div
        v-for="(item, i) in previews"
        :key="item.title"
        v-motion
        :initial="{ opacity: 0, y: 24 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: i * 0.15, duration: 600, ease: [0.22, 1, 0.36, 1] } }"
        class="bg-white/[0.03] border border-white/10 rounded-2xl overflow-hidden hover:border-white/20 transition-colors duration-300"
      >
        <!-- Screenshot placeholder -->
        <div
          class="h-48 flex items-center justify-center"
          :class="[item.accent, item.bg, 'border-b']"
        >
          <span class="text-white/40 text-xs font-medium">{{ item.label }}</span>
        </div>
        <div class="p-5">
          <h3 class="text-white font-semibold text-base mb-1">{{ item.title }}</h3>
          <p class="text-white/50 text-sm leading-relaxed">{{ item.desc }}</p>
        </div>
      </div>
    </div>
  </section>
</template>
```

- [ ] **Step 2: 验证构建**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vite build --mode development 2>&1 | tail -5
```

Expected: `✓ built in ...` 无报错

- [ ] **Step 3: 提交**

```bash
git add src/components/landing/PreviewSection.vue
git commit -m "新建 PreviewSection：三列截图占位展示产品界面预览"
```

---

### Task 5: 创建 RoadmapView.vue

**Files:**
- Create: `src/views/RoadmapView.vue`

- [ ] **Step 1: 创建组件文件**

```vue
<script setup lang="ts">
import LandingNavbar from '@/components/landing/LandingNavbar.vue'

const milestones = [
  {
    version: 'v1.0 — 核心引擎',
    status: 'completed',
    statusLabel: '已完成',
    items: 'ReActAgent 主循环 · ChatModel 延迟注入 · MCP/Skills 工具集成 · DDD 6 模块分层 · SSE 流式对话',
  },
  {
    version: 'v1.1 — 多 Agent 编排',
    status: 'completed',
    statusLabel: '已完成',
    items: 'YAML 配置驱动 AgentGraph · SEQUENTIAL / PARALLEL / LOOP 工作流 · 上下文压缩与熔断 · Vue 3 前端',
  },
  {
    version: 'v1.2 — 工具生态 & 安全',
    status: 'in-progress',
    statusLabel: '进行中',
    items: 'Agent 级工具作用域 · 工具调用权限控制 · Skills 本地执行沙箱 · 审计日志 · 多 Provider 路由',
  },
  {
    version: 'v1.3 — 记忆 & 知识图谱',
    status: 'planned',
    statusLabel: '计划中',
    items: '向量化长期记忆 · 知识图谱自动构建 · 跨会话上下文继承 · RAG 检索增强 · 用户级记忆隔离',
  },
  {
    version: 'v2.0 — 团队协作',
    status: 'planned',
    statusLabel: '计划中',
    items: '多用户工作空间 · Agent 共享与权限 · 团队知识库 · Webhook 触发器 · 定时任务调度 · 企业 SSO',
  },
]

function statusClass(status: string) {
  switch (status) {
    case 'completed': return { dot: 'bg-[#30D158]', badge: 'bg-[#30D158]/20 text-[#30D158]' }
    case 'in-progress': return { dot: 'bg-[#5AC8FA]', badge: 'bg-[#5AC8FA]/20 text-[#5AC8FA]' }
    default: return { dot: 'bg-white/20', badge: 'bg-white/10 text-white/40' }
  }
}
</script>

<template>
  <div class="min-h-screen bg-[#0c0c0c] text-white">
    <LandingNavbar />
    <main class="max-w-4xl mx-auto px-6 pt-28 pb-20">
      <div
        v-motion
        :initial="{ opacity: 0, y: 24 }"
        :enter="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
      >
        <h1 class="text-4xl md:text-5xl font-semibold tracking-tight mb-3">Aether 路线图</h1>
        <p class="text-white/60 text-lg mb-16">企业级多智能体 AI 运行时演进路径</p>
      </div>

      <div class="border-l-2 border-white/10 pl-8 space-y-12">
        <div
          v-for="(m, i) in milestones"
          :key="m.version"
          v-motion
          :initial="{ opacity: 0, x: -20 }"
          :visible="{ opacity: 1, x: 0, transition: { delay: i * 0.15, duration: 600, ease: [0.22, 1, 0.36, 1] } }"
          class="relative"
        >
          <!-- Dot -->
          <div
            class="absolute -left-[calc(2rem+5px)] top-1.5 w-3 h-3 rounded-full border-2 border-[#0c0c0c]"
            :class="statusClass(m.status).dot"
          />

          <!-- Badge -->
          <span
            class="inline-block text-xs font-semibold px-2.5 py-0.5 rounded-full mb-3"
            :class="statusClass(m.status).badge"
          >
            {{ m.statusLabel }}
          </span>

          <h3 class="text-xl font-semibold text-white mb-2">{{ m.version }}</h3>
          <p class="text-white/50 text-sm leading-relaxed">{{ m.items }}</p>
        </div>
      </div>
    </main>
  </div>
</template>
```

- [ ] **Step 2: 验证构建**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vite build --mode development 2>&1 | tail -5
```

Expected: `✓ built in ...` 无报错

- [ ] **Step 3: 提交**

```bash
git add src/views/RoadmapView.vue
git commit -m "新建 RoadmapView：5 阶段垂直时间线展示 Aether 发展路线"
```

---

### Task 6: 创建 DocsView.vue

**Files:**
- Create: `src/views/DocsView.vue`

- [ ] **Step 1: 创建组件文件**

```vue
<script setup lang="ts">
import { ref } from 'vue'
import LandingNavbar from '@/components/landing/LandingNavbar.vue'

interface DocSection {
  label: string
  children?: { label: string }[]
}

const sections: DocSection[] = [
  {
    label: '快速开始',
    children: [
      { label: '环境要求' },
      { label: '克隆与构建' },
      { label: '最小配置模板' },
      { label: '验证 Agent 运行' },
    ],
  },
  {
    label: '核心概念',
    children: [
      { label: 'Agent 生命周期' },
      { label: 'ReActAgent 引擎详解' },
      { label: 'YAML 配置完整参考' },
      { label: 'AgentGraph 与工作流' },
      { label: '工具系统 (MCP/Skills)' },
      { label: '上下文管理策略' },
      { label: '记忆系统' },
    ],
  },
  {
    label: '开发指南',
    children: [
      { label: '6 模块 DDD 架构' },
      { label: '自定义 Agent 开发' },
      { label: '添加新工具' },
      { label: '前端开发' },
      { label: 'Docker 部署' },
      { label: '故障排查' },
    ],
  },
  {
    label: 'API 参考',
    children: [
      { label: 'Chat API' },
      { label: 'SSE 流式协议' },
      { label: 'Agent 管理 API' },
      { label: '模型管理 API' },
    ],
  },
]

const activeSection = ref('快速开始')
const expandedGroups = ref<string[]>(['快速开始'])
</script>

<template>
  <div class="min-h-screen bg-[#0c0c0c] text-white">
    <LandingNavbar />
    <div class="flex pt-16">
      <!-- Sidebar -->
      <aside class="hidden md:block w-56 lg:w-64 flex-shrink-0 border-r border-white/10 h-[calc(100vh-4rem)] overflow-y-auto sticky top-16">
        <nav class="px-5 py-6">
          <h2 class="text-sm font-bold text-white mb-6">📚 Aether 文档</h2>
          <ul class="space-y-5">
            <li v-for="group in sections" :key="group.label">
              <button
                class="w-full text-left text-sm font-semibold mb-2 flex items-center gap-1.5 transition-colors"
                :class="expandedGroups.includes(group.label) ? 'text-white' : 'text-white/60 hover:text-white'"
                @click="expandedGroups.includes(group.label)
                  ? expandedGroups = expandedGroups.filter(g => g !== group.label)
                  : expandedGroups.push(group.label)"
              >
                <span class="text-[10px] transition-transform" :class="{ 'rotate-90': expandedGroups.includes(group.label) }">▶</span>
                {{ group.label }}
              </button>
              <ul
                v-if="expandedGroups.includes(group.label) && group.children"
                class="ml-4 space-y-1"
              >
                <li v-for="child in group.children" :key="child.label">
                  <button
                    class="w-full text-left text-xs py-1.5 px-2 rounded transition-colors"
                    :class="activeSection === child.label
                      ? 'text-[#5AC8FA] bg-[#5AC8FA]/10'
                      : 'text-white/50 hover:text-white/80'"
                    @click="activeSection = child.label"
                  >
                    {{ child.label }}
                  </button>
                </li>
              </ul>
            </li>
          </ul>
        </nav>
      </aside>

      <!-- Content -->
      <main class="flex-1 px-6 md:px-12 py-10 max-w-3xl">
        <div
          v-motion
          :initial="{ opacity: 0, y: 24 }"
          :enter="{ opacity: 1, y: 0, transition: { duration: 600, ease: [0.22, 1, 0.36, 1] } }"
        >
          <h1 class="text-2xl md:text-3xl font-semibold mb-2">快速开始</h1>
          <p class="text-white/50 text-sm mb-10">5 分钟启动第一个 Aether Agent</p>

          <section class="mb-10">
            <h2 class="text-lg font-semibold text-white mb-3">环境要求</h2>
            <ul class="list-disc list-inside text-white/60 text-sm space-y-1">
              <li>JDK 17+</li>
              <li>Maven 3.8+</li>
              <li>Node.js 18+（前端开发）</li>
            </ul>
          </section>

          <section class="mb-10">
            <h2 class="text-lg font-semibold text-white mb-3">克隆与构建</h2>
            <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>git clone https://github.com/your-org/aether.git
cd aether
mvn clean install -DskipTests
cd aether-app && mvn spring-boot:run</code></pre>
          </section>

          <section class="mb-10">
            <h2 class="text-lg font-semibold text-white mb-3">最小配置模板</h2>
            <p class="text-white/50 text-sm mb-3">在 <code class="bg-white/10 px-1.5 py-0.5 rounded text-xs">aether-app/src/main/resources/agent/</code> 下创建 YAML 配置文件：</p>
            <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>ai-api:
  base-url: https://api.openai.com
  api-key: ${OPENAI_API_KEY}
chat-model:
  model: gpt-4o
agents:
  - name: assistant
    system-prompt: 你是一个有用的助手。
runner:
  agent-name: assistant</code></pre>
          </section>

          <section class="mb-10">
            <h2 class="text-lg font-semibold text-white mb-3">验证 Agent 运行</h2>
            <pre class="bg-white/[0.04] border border-white/10 rounded-lg p-4 text-sm text-white/70 overflow-x-auto"><code>curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello Aether!"}'</code></pre>
          </section>
        </div>
      </main>
    </div>
  </div>
</template>
```

- [ ] **Step 2: 验证构建**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vite build --mode development 2>&1 | tail -5
```

Expected: `✓ built in ...` 无报错

- [ ] **Step 3: 提交**

```bash
git add src/views/DocsView.vue
git commit -m "新建 DocsView：左目录右正文的文档首页，默认展示快速开始"
```

---

### Task 7: 修改 LandingView.vue

**Files:**
- Modify: `src/views/LandingView.vue`

- [ ] **Step 1: 替换 import 和 template 中的 section 引用**

将 `LandingView.vue` 中的原有内容替换为：

```vue
<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LandingNavbar from '@/components/landing/LandingNavbar.vue'
import LandingHero from '@/components/landing/LandingHero.vue'
import LandingAppBar from '@/components/landing/LandingAppBar.vue'
import HighlightsSection from '@/components/landing/HighlightsSection.vue'
import ArchitectureSection from '@/components/landing/ArchitectureSection.vue'
import PreviewSection from '@/components/landing/PreviewSection.vue'
import LandingCTA from '@/components/landing/LandingCTA.vue'

const router = useRouter()
const auth = useAuthStore()

function handleGetStarted() {
  if (auth.isLoggedIn) {
    router.push({ name: 'Dashboard' })
  } else {
    router.push({ name: 'Login' })
  }
}
</script>

<template>
  <div class="relative min-h-screen overflow-x-hidden bg-[#0c0c0c] text-white">
    <!-- Fixed full-screen background video -->
    <div class="fixed inset-0 z-0 pointer-events-none">
      <video
        autoplay
        loop
        muted
        playsinline
        class="w-full h-full object-cover pointer-events-none"
        src="https://d8j0ntlcm91z4.cloudfront.net/user_38xzZboKViGWJOttwIXH07lWA1P/hf_20260508_064122_c4750c0e-7476-4b44-94a2-a85a65c63bf2.mp4"
      />
    </div>

    <!-- Hidden-on-mobile fixed vertical guide lines at the 36rem container edges -->
    <div class="hidden md:block pointer-events-none fixed inset-y-0 left-1/2 -translate-x-[calc(50%+36rem)] w-px bg-white/10 z-[5]" />
    <div class="hidden md:block pointer-events-none fixed inset-y-0 left-1/2 translate-x-[calc(-50%+36rem)] w-px bg-white/10 z-[5]" />

    <!-- Root-level SVG noise filter (multiply blend) for the shiny headline -->
    <svg width="0" height="0" class="absolute" aria-hidden="true">
      <filter id="lp-noise">
        <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" stitchTiles="stitch" />
        <feColorMatrix type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 0.35 0" />
        <feComposite in2="SourceGraphic" operator="in" result="noise" />
        <feBlend in="SourceGraphic" in2="noise" mode="multiply" />
      </filter>
    </svg>

    <!-- Page content layered above the background -->
    <div class="relative z-10">
      <LandingNavbar @cta="handleGetStarted" />
      <LandingHero @cta="handleGetStarted" />
      <LandingAppBar />
      <HighlightsSection />
      <ArchitectureSection />
      <PreviewSection />
      <LandingCTA @cta="handleGetStarted" />
    </div>
  </div>
</template>
```

- [ ] **Step 2: 验证构建**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vite build --mode development 2>&1 | tail -5
```

Expected: `✓ built in ...` 无报错

- [ ] **Step 3: 提交**

```bash
git add src/views/LandingView.vue
git commit -m "修改 LandingView：替换为新的 3 个 section，删除 Testimonials 和 Pricing"
```

---

### Task 8: 修改 routes.ts

**Files:**
- Modify: `src/router/routes.ts`

- [ ] **Step 1: 新增 /roadmap 和 /docs 路由**

在 `routes.ts` 的 `routes` 数组中，在 `NotFound` 路由**之前**插入两条新路由。具体修改：找到以下行：

```ts
  {
    path: '/:pathMatch(.*)*',
```

在其**上方**插入：

```ts
  {
    path: '/roadmap',
    name: 'Roadmap',
    component: () => import('@/views/RoadmapView.vue'),
    meta: { title: '路线图 — Aether', group: 'public' },
  },
  {
    path: '/docs',
    name: 'Docs',
    component: () => import('@/views/DocsView.vue'),
    meta: { title: '文档 — Aether', group: 'public' },
  },
```

- [ ] **Step 2: 验证构建**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vite build --mode development 2>&1 | tail -5
```

Expected: `✓ built in ...` 无报错

- [ ] **Step 3: 提交**

```bash
git add src/router/routes.ts
git commit -m "新增 /roadmap 和 /docs 路由"
```

---

### Task 9: 全量构建验证

**Files:** 无新建文件

- [ ] **Step 1: 全量构建**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vite build 2>&1
```

Expected: `✓ built in ...` 无报错，无 TypeScript 类型错误

- [ ] **Step 2: 检查构建产物**

```bash
ls -la aether/docs/dev-ops/aether-frontend-v2/dist/
```

Expected: `index.html` + `assets/` 目录存在

- [ ] **Step 3: 最终提交**

```bash
git add -A
git diff --cached --stat
```

确认变更文件列表与 spec 第 8 节一致（3 修改 + 5 新建），无多余文件。

---

## 验证清单

- [ ] `npx vite build` 无报错
- [ ] 导航栏 5 个菜单项全部显示
- [ ] 亮点/架构/产品预览 点击后平滑滚动到对应 section
- [ ] 路线图 点击后跳转到 `/#/roadmap` 页面
- [ ] 文档 点击后在新标签页打开 `/#/docs`
- [ ] GitHub 图标链接存在
- [ ] CTA 按钮功能正常
- [ ] 导航栏在页面顶部固定
- [ ] 滚动后导航栏出现毛玻璃背景
- [ ] 移动端（<768px）显示汉堡菜单，点击展开下拉
- [ ] 背景视频、滤镜、辅助线保持不变
- [ ] LandingHero、LandingAppBar、LandingCTA 保持不变
