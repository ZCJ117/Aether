# Landing Page Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the React landing page from `fronted` into `aether-frontend-v2` as Vue 3 SFCs, replacing the current `LandingView.vue` with a polished multi-section landing page using Aether AI Agent branding.

**Architecture:** Single-page scrollable landing at `/` composed of 9 section components + 3 shared utility components, all using `@vueuse/motion` for scroll-triggered animations. No router changes; LoginView + all `/app/*` routes untouched.

**Tech Stack:** Vue 3 SFC (`<script setup lang="ts">`), Tailwind CSS, `@vueuse/motion`, `lucide-vue-next`

---

### Task 1: Install @vueuse/motion dependency

**Files:**
- Modify: `package.json`

- [ ] **Step 1: Add @vueuse/motion to dependencies**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npm install @vueuse/motion
```

- [ ] **Step 2: Verify installation**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && node -e "require('@vueuse/motion/package.json').version"
```

Expected: prints version number (e.g., `2.x.x`)

---

### Task 2: Create shared landing components (AetherLogo, LandingButton, SectionEyebrow)

**Files:**
- Create: `src/components/landing/AetherLogo.vue`
- Create: `src/components/landing/LandingButton.vue`
- Create: `src/components/landing/SectionEyebrow.vue`

- [ ] **Step 1: Create AetherLogo.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/AetherLogo.vue`

```vue
<script setup lang="ts">
defineProps<{
  className?: string
}>()
</script>

<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 256 256"
    fill="currentColor"
    :class="className || 'w-8 h-8'"
    aria-label="Aether"
  >
    <!-- Abstract 4-quadrant curve mark — inherits color from parent -->
    <path d="M 0 128 C 70.692 128 128 185.308 128 256 L 64 256 C 64 220.654 35.346 192 0 192 Z" />
    <path d="M 256 192 C 220.654 192 192 220.654 192 256 L 128 256 C 128 185.308 185.308 128 256 128 Z" />
    <path d="M 128 0 C 128 70.692 70.692 128 0 128 L 0 64 C 35.346 64 64 35.346 64 0 Z" />
    <path d="M 192 0 C 192 35.346 220.654 64 256 64 L 256 128 C 185.308 128 128 70.692 128 0 Z" />
  </svg>
</template>
```

- [ ] **Step 2: Create LandingButton.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/LandingButton.vue`

```vue
<script setup lang="ts">
import { ChevronRight } from 'lucide-vue-next'

withDefaults(defineProps<{
  label?: string
  full?: boolean
}>(), {
  label: '开始使用',
  full: false,
})

const emit = defineEmits<{
  click: []
}>()
</script>

<template>
  <button
    type="button"
    :class="[
      'group inline-flex items-center justify-center gap-2 rounded-full bg-white text-black font-medium text-sm px-5 py-3 transition-all hover:bg-white/90 active:scale-[0.98]',
      full ? 'w-full' : '',
    ]"
    @click="emit('click')"
  >
    <AetherLogo className="w-4 h-4 text-black" />
    <span>{{ label }}</span>
    <ChevronRight
      class="w-4 h-4 transition-transform duration-200 group-hover:translate-x-[1px]"
      :stroke-width="2"
    />
  </button>
</template>
```

- [ ] **Step 3: Create SectionEyebrow.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/SectionEyebrow.vue`

```vue
<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  tag?: string
}>(), {
  tag: undefined,
})
</script>

<template>
  <div class="inline-flex items-center gap-2 text-xs text-white/50">
    <span class="w-1.5 h-1.5 rounded-full bg-white" />
    <span>{{ label }}</span>
    <span
      v-if="tag"
      class="px-2 py-0.5 rounded-full border border-white/10 text-white/50"
    >
      {{ tag }}
    </span>
  </div>
</template>
```

- [ ] **Step 4: Commit shared components**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/components/landing/AetherLogo.vue docs/dev-ops/aether-frontend-v2/src/components/landing/LandingButton.vue docs/dev-ops/aether-frontend-v2/src/components/landing/SectionEyebrow.vue docs/dev-ops/aether-frontend-v2/package.json docs/dev-ops/aether-frontend-v2/package-lock.json
git commit -m "新增 landing 共享组件（AetherLogo, LandingButton, SectionEyebrow）"
```

---

### Task 3: Append landing page CSS utilities to style.css

**Files:**
- Modify: `src/style.css`

- [ ] **Step 1: Append landing-specific CSS to style.css**

Open `aether/docs/dev-ops/aether-frontend-v2/src/style.css`. Append the following block at the **end** of the file:

```css
/* ====================================================================
   Landing page utilities (ported from fronted project)
   ==================================================================== */

/* Shiny animated gradient text */
.animate-shiny {
  background-size: 200% auto;
  animation: shiny 6s linear infinite;
}

@keyframes shiny {
  0% {
    background-position: -200% center;
  }
  100% {
    background-position: 200% center;
  }
}

/* Liquid Glass utility */
.liquid-glass {
  background: rgba(255, 255, 255, 0.01);
  background-blend-mode: luminosity;
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  border: none;
  box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.1);
  position: relative;
  overflow: hidden;
}

.liquid-glass::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  padding: 1.4px;
  background: linear-gradient(
    180deg,
    rgba(255, 255, 255, 0.45) 0%,
    rgba(255, 255, 255, 0.15) 20%,
    rgba(255, 255, 255, 0) 40%,
    rgba(255, 255, 255, 0) 60%,
    rgba(255, 255, 255, 0.15) 80%,
    rgba(255, 255, 255, 0.45) 100%
  );
  -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  pointer-events: none;
}

/* ====================================================================
   Landing pricing section (lp-* namespace)
   ==================================================================== */

.lp-pricing-section {
  position: relative;
  padding: 40px 20px 80px;
  display: flex;
  flex-direction: column;
  align-items: center;
  overflow-x: hidden;
}

.lp-watermark-container {
  position: relative;
  width: 100%;
  max-width: 1100px;
  text-align: center;
  margin-top: 40px;
  z-index: 2;
}

.lp-watermark-main {
  font-size: 9rem;
  font-weight: 800;
  line-height: 0.9;
  letter-spacing: -0.05em;
  filter: url(#lp-noise);
  display: flex;
  flex-direction: column;
  align-items: center;
}

.lp-watermark-line-1 {
  color: #fff;
}

.lp-watermark-line-2 {
  background: linear-gradient(
    to right,
    #091020 0%,
    #0B2551 25%,
    #A4F4FD 65%,
    #00d2ff 100%
  );
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  -webkit-text-fill-color: transparent;
}

.lp-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
  width: 100%;
  max-width: 1100px;
  margin-top: 60px;
  transform: translateX(20px);
  position: relative;
  z-index: 3;
}

.lp-card {
  background: linear-gradient(135deg, rgba(0, 0, 0, 0.7), rgba(0, 0, 0, 0.4));
  backdrop-filter: blur(14px) brightness(0.91);
  -webkit-backdrop-filter: blur(14px) brightness(0.91);
  border: 1px solid rgba(255, 255, 255, 1);
  border-radius: 44px;
  padding: 50px 24px;
  min-height: 580px;
  display: flex;
  flex-direction: column;
  transition: all 0.6s cubic-bezier(0.22, 1, 0.36, 1);
  overflow: hidden;
  position: relative;
}

.lp-card::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(
    135deg,
    rgba(255, 255, 255, 0.1) 0%,
    rgba(255, 255, 255, 0) 50%
  );
  pointer-events: none;
}

.lp-card:hover {
  background: rgba(15, 15, 15, 0.6);
  border-color: rgba(34, 211, 238, 0.7);
  transform: translateY(-12px) scale(1.01);
}

.lp-card-pro {
  background: linear-gradient(135deg, rgba(0, 0, 0, 0.85), rgba(0, 0, 0, 0.55));
}

.lp-tier-small {
  font-size: 1.1rem;
  font-weight: 400;
  color: rgba(255, 255, 255, 0.6);
}

.lp-tier-large {
  font-size: 2.8rem;
  font-weight: 500;
  letter-spacing: -0.02em;
  color: #fff;
  margin-top: 8px;
}

.lp-desc {
  font-size: 0.88rem;
  color: rgba(255, 255, 255, 0.45);
  min-height: 3.2em;
  margin-top: 16px;
  margin-bottom: 40px;
  line-height: 1.5;
}

.lp-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.lp-list li {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  font-size: 0.92rem;
  color: rgba(255, 255, 255, 0.8);
  margin-bottom: 18px;
  line-height: 1.4;
}

.lp-check {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.15);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.lp-btn {
  background: #fff;
  color: #000;
  padding: 10px 32px;
  border-radius: 100px;
  font-weight: 600;
  font-size: 0.88rem;
  margin-top: auto;
  border: none;
  cursor: pointer;
  align-self: center;
  transition: all 0.3s cubic-bezier(0.22, 1, 0.36, 1);
}

.lp-btn:hover {
  background: #f5f5f5;
  transform: scale(1.02);
  box-shadow: 0 8px 24px rgba(255, 255, 255, 0.15);
}

.lp-toggle-wrap {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  width: 100%;
  max-width: 1100px;
  margin-top: 32px;
  padding-right: 20px;
}

.lp-toggle {
  width: 52px;
  height: 28px;
  background: #fff;
  border-radius: 100px;
  position: relative;
  cursor: pointer;
  border: none;
  transition: background 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  padding: 0;
}

.lp-toggle-knob {
  width: 20px;
  height: 20px;
  background: #000;
  border-radius: 50%;
  position: absolute;
  top: 4px;
  left: 4px;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.lp-toggle.active {
  background: rgba(255, 255, 255, 0.2);
}

.lp-toggle.active .lp-toggle-knob {
  transform: translateX(24px);
  background: #fff;
}

@media (max-width: 1024px) {
  .lp-watermark-main {
    font-size: 3.5rem;
    filter: none;
  }
  .lp-watermark-line-2 {
    background: none;
    -webkit-text-fill-color: #00d2ff;
    color: #00d2ff;
  }
  .lp-grid {
    display: flex;
    overflow-x: auto;
    scroll-snap-type: x mandatory;
    transform: none;
    width: 100vw;
    padding: 0 20px;
    gap: 16px;
    scrollbar-width: none;
  }
  .lp-grid::-webkit-scrollbar {
    display: none;
  }
  .lp-card {
    flex: 0 0 320px;
    scroll-snap-align: center;
  }
  .lp-toggle-wrap {
    justify-content: center;
    padding-right: 0;
  }
}
```

- [ ] **Step 2: Commit CSS changes**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/style.css
git commit -m "新增 landing 页面 CSS 工具类（liquid-glass, 定价卡片, 光泽动画）"
```

---

### Task 4: Create LandingNavbar component

**Files:**
- Create: `src/components/landing/LandingNavbar.vue`

- [ ] **Step 1: Create LandingNavbar.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/LandingNavbar.vue`

```vue
<script setup lang="ts">
import { Menu } from 'lucide-vue-next'
import { useMotion } from '@vueuse/motion'
import AetherLogo from './AetherLogo.vue'
import LandingButton from './LandingButton.vue'

const NAV_LINKS = [
  { label: '特性', href: '#features' },
  { label: '定价', href: '#pricing' },
  { label: '文档', href: '#docs' },
  { label: '关于', href: '#about' },
]

const emit = defineEmits<{
  cta: []
}>()

const navMotion = useMotion(
  { initial: { opacity: 0, y: -10 }, enter: { opacity: 1, y: 0, transition: { duration: 600, ease: 'easeOut' } } }
)
</script>

<template>
  <nav
    v-motion="navMotion"
    class="relative z-20 max-w-6xl mx-auto px-6 py-6 flex items-center justify-between"
  >
    <a href="#" aria-label="Aether home" class="text-white">
      <AetherLogo className="w-8 h-8" />
    </a>

    <div class="hidden md:flex gap-8">
      <a
        v-for="(link, i) in NAV_LINKS"
        :key="link.label"
        :href="link.href"
        :style="{ animationDelay: `${0.1 + i * 0.05}s` }"
        class="text-white/70 text-sm font-medium hover:text-white transition-colors animate-fade-in"
      >
        {{ link.label }}
      </a>
    </div>

    <div class="hidden md:block">
      <LandingButton label="开始使用" @click="emit('cta')" />
    </div>

    <button
      type="button"
      aria-label="Open menu"
      class="md:hidden w-10 h-10 rounded-full border border-white/10 bg-white/5 flex items-center justify-center text-white/80 hover:text-white"
    >
      <Menu class="w-4 h-4" />
    </button>
  </nav>
</template>
```

- [ ] **Step 2: Commit**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/components/landing/LandingNavbar.vue
git commit -m "新增 LandingNavbar 组件"
```

---

### Task 5: Create LandingHero component

**Files:**
- Create: `src/components/landing/LandingHero.vue`

- [ ] **Step 1: Create LandingHero.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/LandingHero.vue`

```vue
<script setup lang="ts">
import LandingButton from './LandingButton.vue'

const emit = defineEmits<{
  cta: []
}>()

const gradientStyle = {
  backgroundImage:
    'linear-gradient(to right, #091020 0%, #0B2551 12.5%, #A4F4FD 32.5%, #00d2ff 50%, #0B2551 67.5%, #091020 87.5%, #091020 100%)',
  backgroundSize: '200% auto',
  WebkitBackgroundClip: 'text',
  backgroundClip: 'text',
  color: 'transparent',
  WebkitTextFillColor: 'transparent',
  filter: 'url(#lp-noise)',
}
</script>

<template>
  <section class="pt-16 md:pt-28 pb-20 text-center flex flex-col items-center px-6">
    <h1
      v-motion
      :initial="{ opacity: 0, y: 20 }"
      :enter="{ opacity: 1, y: 0, transition: { delay: 300, duration: 800, ease: [0.22, 1, 0.36, 1] } }"
      class="text-4xl md:text-7xl font-semibold tracking-tight leading-[0.9]"
    >
      <span class="block text-white">你的 AI 智能体。</span>
      <span class="block animate-shiny" :style="gradientStyle">即刻编排</span>
    </h1>

    <p
      v-motion
      :initial="{ opacity: 0, y: 20 }"
      :enter="{ opacity: 1, y: 0, transition: { delay: 500, duration: 800, ease: [0.22, 1, 0.36, 1] } }"
      class="mt-8 text-white/60 max-w-md text-base leading-[1.5]"
    >
      Aether 是企业级多智能体 AI 运行时平台。构建、编排和部署可扩展的多智能体系统，从原型到生产一站式交付。
    </p>

    <div
      v-motion
      :initial="{ opacity: 0, y: 20 }"
      :enter="{ opacity: 1, y: 0, transition: { delay: 700, duration: 800, ease: [0.22, 1, 0.36, 1] } }"
      class="mt-8 flex flex-col items-center gap-2"
    >
      <LandingButton label="开始使用" @click="emit('cta')" />
      <p class="text-xs text-white/40">开源 · 企业级 · 生产就绪</p>
    </div>
  </section>
</template>
```

- [ ] **Step 2: Commit**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/components/landing/LandingHero.vue
git commit -m "新增 LandingHero 组件"
```

---

### Task 6: Create LandingAppBar component (Mac-style menu bar mockup)

**Files:**
- Create: `src/components/landing/LandingAppBar.vue`

- [ ] **Step 1: Create LandingAppBar.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/LandingAppBar.vue`

```vue
<script setup lang="ts">
import { Search } from 'lucide-vue-next'
import AetherLogo from './AetherLogo.vue'

const MENU_ITEMS = ['File', 'Edit', 'View', 'Agent', 'Window', 'Help']

function visibilityClass(index: number): string {
  if (index > 3) return 'hidden md:inline'
  if (index > 2) return 'hidden sm:inline'
  return 'inline'
}
</script>

<template>
  <div
    v-motion
    :initial="{ opacity: 0, y: -8 }"
    :enter="{ opacity: 1, y: 0, transition: { delay: 900, duration: 600, ease: 'easeOut' } }"
    class="w-full h-10 bg-black/40 backdrop-blur-md border-t border-b border-white/10"
  >
    <div class="max-w-6xl mx-auto px-6 h-full flex items-center justify-between text-xs">
      <div class="flex items-center gap-4 text-white/80">
        <AetherLogo className="w-3.5 h-3.5" />
        <span class="font-bold text-white">Aether</span>
        <span
          v-for="(item, i) in MENU_ITEMS"
          :key="item"
          :class="[visibilityClass(i), 'hover:text-white cursor-default']"
        >
          {{ item }}
        </span>
      </div>
      <div class="flex items-center gap-3 text-white/80">
        <Search class="w-3.5 h-3.5" :stroke-width="2" />
        <span>Aether Agent Console</span>
      </div>
    </div>
  </div>
</template>
```

- [ ] **Step 2: Commit**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/components/landing/LandingAppBar.vue
git commit -m "新增 LandingAppBar 组件（Mac 风格菜单栏）"
```

---

### Task 7: Create LandingConsole component (Agent Console mockup, ported from InboxMockup)

**Files:**
- Create: `src/components/landing/LandingConsole.vue`

- [ ] **Step 1: Create LandingConsole.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/LandingConsole.vue`

```vue
<script setup lang="ts">
import { ref } from 'vue'
import {
  Sparkles, Terminal, Activity, Wrench, FileText, Archive, Trash2,
  Search, Play, CheckCircle, MoreHorizontal,
} from 'lucide-vue-next'

type AgentTask = {
  id: string
  agent: string
  task: string
  status: string
  time: string
  active?: boolean
}

const TASKS = ref<AgentTask[]>([
  { id: '1', agent: 'CodeAgent', task: '代码审查与重构 · 3 文件已分析', status: 'completed', time: '9:41 AM', active: true },
  { id: '2', agent: 'DataAgent', task: '数据管道 ETL · Q3 报表生成中', status: 'running', time: '8:12 AM' },
  { id: '3', agent: 'DocAgent', task: 'PRD 文档生成 · v2.1 已发布', status: 'completed', time: 'Yesterday' },
  { id: '4', agent: 'OpsAgent', task: '集群健康检查 · 所有节点正常', status: 'completed', time: 'Yesterday' },
  { id: '5', agent: 'TestAgent', task: '回归测试套件 · 142/142 通过', status: 'completed', time: 'Mon' },
  { id: '6', agent: 'MailAgent', task: '邮件摘要 · 12 封待处理', status: 'pending', time: 'Mon' },
])

const SIDEBAR_ITEMS = [
  { icon: Terminal, label: 'Agent 控制台', count: 6, active: true },
  { icon: Activity, label: '运行中', count: 1 },
  { icon: CheckCircle, label: '已完成' },
  { icon: FileText, label: '日志', count: 24 },
  { icon: Archive, label: '归档' },
  { icon: Trash2, label: '回收站' },
]

const LABELS = [
  { name: '生产环境', color: '#00d2ff' },
  { name: '开发环境', color: '#A4F4FD' },
  { name: '测试环境', color: '#f59e0b' },
  { name: '沙箱环境', color: '#10b981' },
]

const activeTask = ref(TASKS.value[0])
</script>

<template>
  <section class="max-w-6xl mx-auto px-6 py-16 md:py-24">
    <div
      v-motion
      :initial="{ opacity: 0, y: 40 }"
      :enter="{ opacity: 1, y: 0, transition: { delay: 1100, duration: 900, ease: [0.22, 1, 0.36, 1] } }"
      class="relative rounded-2xl overflow-hidden border border-white/10 bg-[#0e1014]/90 backdrop-blur-2xl"
    >
      <!-- Title bar -->
      <div class="flex items-center justify-between px-4 h-9 bg-black/40 border-b border-white/10">
        <div class="flex items-center gap-2">
          <span class="w-3 h-3 rounded-full" style="background-color: #ff5f57" />
          <span class="w-3 h-3 rounded-full" style="background-color: #febc2e" />
          <span class="w-3 h-3 rounded-full" style="background-color: #28c840" />
        </div>
        <div class="text-xs text-white/50">Aether — Agent Console</div>
        <div class="w-12" />
      </div>

      <!-- Body -->
      <div class="grid grid-cols-12 h-[520px] text-sm">
        <!-- Sidebar -->
        <aside class="col-span-3 border-r border-white/10 bg-black/30 p-4 flex flex-col gap-1">
          <button
            type="button"
            class="w-full inline-flex items-center gap-2 rounded-lg bg-white text-black text-xs font-semibold px-3 py-2 hover:bg-white/90 transition-colors"
          >
            <Sparkles class="w-3.5 h-3.5" />
            <span>部署智能体</span>
          </button>

          <nav class="mt-4 flex flex-col gap-0.5">
            <a
              v-for="item in SIDEBAR_ITEMS"
              :key="item.label"
              href="#"
              :class="[
                'flex items-center gap-2 px-2 py-1.5 rounded-md text-xs transition-colors',
                item.active ? 'bg-white/10 text-white' : 'text-white/60 hover:bg-white/5',
              ]"
            >
              <component :is="item.icon" class="w-3.5 h-3.5" />
              <span class="flex-1">{{ item.label }}</span>
              <span v-if="item.count !== undefined" class="text-[10px] text-white/40">{{ item.count }}</span>
            </a>
          </nav>

          <div class="mt-6">
            <p class="text-[10px] uppercase tracking-widest text-white/40 mb-2 px-2">环境</p>
            <ul class="flex flex-col gap-0.5">
              <li
                v-for="label in LABELS"
                :key="label.name"
                class="flex items-center gap-2 px-2 py-1.5 rounded-md text-xs text-white/60 hover:bg-white/5"
              >
                <span class="w-2.5 h-2.5 rounded-full" :style="{ backgroundColor: label.color }" />
                <span>{{ label.name }}</span>
              </li>
            </ul>
          </div>
        </aside>

        <!-- Task list -->
        <div class="col-span-4 border-r border-white/10 overflow-y-auto">
          <div class="flex items-center gap-2 px-3 h-10 border-b border-white/10 text-white/50">
            <Search class="w-3.5 h-3.5" />
            <span class="text-xs">搜索任务</span>
          </div>
          <ul>
            <li
              v-for="t in TASKS"
              :key="t.id"
              :class="[
                'px-3 py-3 border-b border-white/5 cursor-pointer',
                t.active ? 'bg-white/[0.06]' : 'hover:bg-white/[0.03]',
              ]"
              @click="activeTask = t"
            >
              <div class="flex items-center justify-between mb-0.5">
                <span :class="['text-xs', t.active ? 'text-white font-semibold' : 'text-white/80 font-medium']">
                  {{ t.agent }}
                </span>
                <span class="text-[10px] text-white/40">{{ t.time }}</span>
              </div>
              <div class="text-[11px] text-white/70 truncate">{{ t.task }}</div>
              <div class="text-[11px] text-white/40">{{ t.status }}</div>
            </li>
          </ul>
        </div>

        <!-- Detail panel -->
        <div class="col-span-5 flex flex-col">
          <div class="flex items-center justify-between px-4 h-10 border-b border-white/10">
            <div class="flex items-center gap-1">
              <button
                v-for="btn in [
                  { icon: Play, label: '运行' },
                  { icon: Archive, label: '归档' },
                  { icon: Trash2, label: '删除' },
                ]"
                :key="btn.label"
                type="button"
                :aria-label="btn.label"
                class="w-7 h-7 rounded-md flex items-center justify-center text-white/60 hover:bg-white/5 hover:text-white transition-colors"
              >
                <component :is="btn.icon" class="w-3.5 h-3.5" />
              </button>
            </div>
            <button
              type="button"
              aria-label="More"
              class="w-7 h-7 rounded-md flex items-center justify-center text-white/60 hover:bg-white/5 hover:text-white transition-colors"
            >
              <MoreHorizontal class="w-3.5 h-3.5" />
            </button>
          </div>

          <div class="flex-1 overflow-y-auto px-5 py-4">
            <h3 class="text-base font-semibold text-white">{{ activeTask.task }}</h3>

            <div class="mt-3 flex items-center gap-2.5">
              <div class="w-7 h-7 rounded-full bg-gradient-to-br from-[#00d2ff] to-[#0B2551] flex items-center justify-center text-[10px] font-semibold text-white">
                {{ activeTask.agent.charAt(0) }}
              </div>
              <div class="flex-1 min-w-0">
                <div class="text-xs text-white">
                  <span class="font-medium">{{ activeTask.agent }}</span>
                  <span class="text-white/50"> · {{ activeTask.time }}</span>
                </div>
              </div>
              <span class="px-2 py-0.5 rounded-full border border-white/10 text-[10px] text-white/70">
                {{ activeTask.status }}
              </span>
            </div>

            <div class="mt-5 rounded-lg border border-white/10 bg-white/[0.03] p-3">
              <div class="flex items-center gap-2 text-[11px] font-medium text-[#A4F4FD]">
                <Sparkles class="w-3.5 h-3.5" />
                <span>摘要 · Aether Agent</span>
              </div>
              <p class="mt-2 text-xs leading-[1.6] text-white/70">
                Agent 已完成任务执行。共处理 3 个文件，调用 5 个工具，无错误发生。
                输出结果已保存至工作目录。
              </p>
            </div>

            <div class="mt-5 space-y-3 text-xs leading-[1.7] text-white">
              <p>任务执行详情：</p>
              <p>
                本次 Agent 运行使用了 ReAct 推理循环，经过 3 轮迭代完成目标。
                工具调用链：read_file → analyze → edit_file，全程流式输出。
              </p>
              <p>
                上下文窗口使用率 34%，Token 消耗 12,480，预估成本 $0.03。
              </p>
              <p class="text-white/50">— Aether Agent Runtime</p>
            </div>

            <div class="mt-5 inline-flex items-center gap-2 px-3 py-1.5 rounded-full border border-white/10 text-[11px] text-white/80">
              <Wrench class="w-3 h-3" />
              <span>tools-invoked.json</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
```

- [ ] **Step 2: Commit**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/components/landing/LandingConsole.vue
git commit -m "新增 LandingConsole 组件（Agent 控制台模拟界面）"
```

---

### Task 8: Create LandingOrchestration component (ported from FeatureTriage)

**Files:**
- Create: `src/components/landing/LandingOrchestration.vue`

- [ ] **Step 1: Create LandingOrchestration.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/LandingOrchestration.vue`

```vue
<script setup lang="ts">
import SectionEyebrow from './SectionEyebrow.vue'

const CHIPS = ['自动路由', '并行执行', '循环迭代', '工具编排']

const ORCH_GROUPS = [
  {
    label: '执行中',
    count: 4,
    dot: '#ffffff',
    items: ['CodeAgent — 代码审查', 'DataAgent — 数据清洗'],
  },
  {
    label: '等待',
    count: 7,
    dot: '#e5e5e5',
    items: ['DocAgent — 等待上游完成', 'TestAgent — 排队中'],
  },
  {
    label: '完成',
    count: 18,
    dot: '#a3a3a3',
    items: ['OpsAgent — 健康检查通过', 'MailAgent — 摘要已生成'],
  },
  {
    label: '归档',
    count: 13,
    dot: '#525252',
    items: ['历史任务 · 批量归档 · 日志转储'],
  },
]
</script>

<template>
  <section class="max-w-6xl mx-auto px-6 py-20 md:py-28">
    <div class="grid md:grid-cols-2 gap-10 md:gap-16 items-start">
      <div
        v-motion
        :initial="{ opacity: 0, y: 20 }"
        :visible="{ opacity: 1, y: 0, transition: { duration: 700, ease: [0.22, 1, 0.36, 1] } }"
      >
        <SectionEyebrow label="编排" tag="AI 原生" />
        <h2 class="mt-5 text-3xl md:text-5xl font-semibold tracking-tight leading-[1.02]">
          一个工作流，
          <br />
          编排所有智能体。
        </h2>
        <p class="mt-6 text-white/60 text-base leading-[1.6] max-w-md">
          Aether 的 DAG 工作流引擎支持串行、并行、循环三种编排模式。
          定义节点间的数据流转，智能体协作从未如此简单。
        </p>
        <div class="mt-6 flex flex-wrap gap-2">
          <span
            v-for="chip in CHIPS"
            :key="chip"
            class="text-xs text-white/70 px-3 py-1.5 rounded-full border border-white/10 bg-white/[0.03]"
          >
            {{ chip }}
          </span>
        </div>
      </div>

      <div
        v-motion
        :initial="{ opacity: 0, y: 20 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: 100, duration: 700, ease: [0.22, 1, 0.36, 1] } }"
        class="liquid-glass rounded-2xl p-5"
      >
        <p class="text-xs text-white/50 mb-4">今日 · 42 个任务已执行</p>
        <div class="flex flex-col gap-3">
          <div v-for="group in ORCH_GROUPS" :key="group.label" class="liquid-glass rounded-lg p-3">
            <div class="flex items-center gap-2 mb-2">
              <span class="w-2 h-2 rounded-full" :style="{ backgroundColor: group.dot }" />
              <span class="text-xs text-white/80 font-medium">
                {{ group.label }}
                <span class="text-white/40">({{ group.count }})</span>
              </span>
            </div>
            <ul class="flex flex-col gap-1">
              <li v-for="item in group.items" :key="item" class="text-[11px] text-white/50 pl-4">
                · {{ item }}
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
```

- [ ] **Step 2: Commit**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/components/landing/LandingOrchestration.vue
git commit -m "新增 LandingOrchestration 组件（智能体编排展示）"
```

---

### Task 9: Create LandingIntegrations component (ported from LogoCloud)

**Files:**
- Create: `src/components/landing/LandingIntegrations.vue`

- [ ] **Step 1: Create LandingIntegrations.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/LandingIntegrations.vue`

```vue
<script setup lang="ts">
const PARTNERS = ['OpenAI', 'Anthropic', 'DeepSeek', 'Qwen', 'Llama', 'MCP', 'LangChain', 'Spring AI']
</script>

<template>
  <section class="max-w-6xl mx-auto px-6 py-16 md:py-20">
    <p class="text-center text-xs uppercase tracking-widest text-white/40">
      与业界领先的 AI 生态深度集成
    </p>
    <div class="mt-10 grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-8 gap-6">
      <div
        v-for="(name, i) in PARTNERS"
        :key="name"
        v-motion
        :initial="{ opacity: 0, y: 8 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: i * 50, duration: 500, ease: 'easeOut' } }"
        class="flex items-center justify-center"
      >
        <span class="text-sm font-semibold tracking-tight text-white/50 hover:text-white transition-colors cursor-default">
          {{ name }}
        </span>
      </div>
    </div>
  </section>
</template>
```

- [ ] **Step 2: Commit**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/components/landing/LandingIntegrations.vue
git commit -m "新增 LandingIntegrations 组件（生态集成展示）"
```

---

### Task 10: Create LandingTestimonials component

**Files:**
- Create: `src/components/landing/LandingTestimonials.vue`

- [ ] **Step 1: Create LandingTestimonials.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/LandingTestimonials.vue`

```vue
<script setup lang="ts">
const TESTIMONIALS = [
  {
    quote: 'Aether 的 DAG 编排引擎让我们的多 Agent 协作系统从概念验证到生产部署只用了两周。远超预期。',
    name: '张明',
    role: 'AI 平台技术负责人',
    company: '某头部金融科技公司',
  },
  {
    quote: 'ReAct 推理循环 + 工具作用域的设计非常优雅。我们团队现在可以专注于业务逻辑，而不是 Agent 基础设施。',
    name: '李华',
    role: '高级算法工程师',
    company: '某 AI 独角兽企业',
  },
  {
    quote: '上下文管理和自动压缩机制解决了我们长期以来的 Token 成本问题。生产环境的稳定性令人放心。',
    name: '王芳',
    role: '工程效能负责人',
    company: '某大型互联网公司',
  },
]
</script>

<template>
  <section class="max-w-6xl mx-auto px-6 py-20 md:py-28 border-t border-white/10">
    <div class="grid md:grid-cols-3 gap-6">
      <figure
        v-for="(t, i) in TESTIMONIALS"
        :key="t.name"
        v-motion
        :initial="{ opacity: 0, y: 24 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: i * 80, duration: 600, ease: [0.22, 1, 0.36, 1] } }"
        class="liquid-glass rounded-2xl p-6"
      >
        <blockquote class="text-sm text-white/80 leading-[1.6]">
          "{{ t.quote }}"
        </blockquote>
        <figcaption class="mt-6 pt-5 border-t border-white/10">
          <div class="text-sm font-semibold text-white">{{ t.name }}</div>
          <div class="text-xs text-white/50 mt-0.5">{{ t.role }}</div>
          <div class="text-xs text-white font-semibold tracking-wide mt-2">{{ t.company }}</div>
        </figcaption>
      </figure>
    </div>
  </section>
</template>
```

- [ ] **Step 2: Commit**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/components/landing/LandingTestimonials.vue
git commit -m "新增 LandingTestimonials 组件（用户评价）"
```

---

### Task 11: Create LandingPricing component

**Files:**
- Create: `src/components/landing/LandingPricing.vue`

- [ ] **Step 1: Create LandingPricing.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/LandingPricing.vue`

```vue
<script setup lang="ts">
import { ref } from 'vue'

type Plan = {
  name: string
  tier: string
  monthly: string
  yearly: string
  desc: string
  features: string[]
  pro?: boolean
}

const PLANS: Plan[] = [
  {
    name: 'Community',
    tier: '社区版',
    monthly: '免费',
    yearly: '免费',
    desc: '适合个人开发者和学习探索，体验 Aether 核心能力。',
    features: [
      '最多 3 个智能体',
      '基础 ReAct 推理循环',
      'MCP 工具集成',
      '社区支持',
      'Web 管理界面',
    ],
  },
  {
    name: 'Team',
    tier: '团队版',
    monthly: '¥999/月',
    yearly: '¥9,999/年',
    desc: '适合中小团队，需要多 Agent 协作和 DAG 工作流编排。',
    features: [
      '最多 50 个智能体',
      'DAG 工作流编排引擎',
      '上下文自动压缩',
      '团队协作（最多 5 人）',
      '优先技术支持',
    ],
  },
  {
    name: 'Enterprise',
    tier: '企业版',
    monthly: '¥1,999/月',
    yearly: '¥19,999/年',
    desc: '适合大型组织和关键业务场景，需要高级安全与定制能力。',
    features: [
      '无限智能体',
      '自定义工作流模板',
      'SSO + RBAC 权限控制',
      '私有化部署支持',
      '专属客户成功经理',
    ],
    pro: true,
  },
]

const yearly = ref(false)
</script>

<template>
  <section id="pricing" class="lp-pricing-section">
    <!-- Pricing-area SVG noise filter -->
    <svg width="0" height="0" class="absolute" aria-hidden="true">
      <filter id="lp-noise">
        <feTurbulence type="fractalNoise" baseFrequency="0.5" numOctaves="2" stitchTiles="stitch" />
        <feComponentTransfer>
          <feFuncA type="linear" slope="0.075" />
        </feComponentTransfer>
        <feComposite in2="SourceGraphic" operator="in" result="noise" />
        <feBlend in="SourceGraphic" in2="noise" mode="overlay" />
      </filter>
    </svg>

    <!-- Watermark backdrop -->
    <div class="lp-watermark-container">
      <div class="lp-watermark-main">
        <span class="lp-watermark-line-1">智能体协作。</span>
        <span class="lp-watermark-line-2">生产级</span>
      </div>
    </div>

    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 700, ease: [0.22, 1, 0.36, 1] } }"
      class="lp-grid"
    >
      <div
        v-for="plan in PLANS"
        :key="plan.name"
        :class="['lp-card', plan.pro ? 'lp-card-pro' : '']"
      >
        <div class="lp-tier-small">{{ plan.tier }}</div>
        <div class="lp-tier-large">{{ yearly ? plan.yearly : plan.monthly }}</div>
        <p class="lp-desc">{{ plan.desc }}</p>
        <ul class="lp-list">
          <li v-for="feature in plan.features" :key="feature">
            <span class="lp-check">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <polyline points="5 12 10 17 19 7" />
              </svg>
            </span>
            <span>{{ feature }}</span>
          </li>
        </ul>
        <button type="button" class="lp-btn">选择方案</button>
      </div>
    </div>

    <div class="lp-toggle-wrap">
      <span class="text-xs text-white/60">年付</span>
      <button
        type="button"
        aria-label="Toggle yearly pricing"
        :aria-pressed="yearly"
        :class="['lp-toggle', yearly ? 'active' : '']"
        @click="yearly = !yearly"
      >
        <span class="lp-toggle-knob" />
      </button>
    </div>
  </section>
</template>
```

- [ ] **Step 2: Commit**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/components/landing/LandingPricing.vue
git commit -m "新增 LandingPricing 组件（定价方案）"
```

---

### Task 12: Create LandingCTA component (ported from FinalCTA)

**Files:**
- Create: `src/components/landing/LandingCTA.vue`

- [ ] **Step 1: Create LandingCTA.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/components/landing/LandingCTA.vue`

```vue
<script setup lang="ts">
import { ChevronRight } from 'lucide-vue-next'
import LandingButton from './LandingButton.vue'

const emit = defineEmits<{
  cta: []
}>()
</script>

<template>
  <section class="max-w-6xl mx-auto px-6 py-20 md:py-32">
    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
      class="liquid-glass relative overflow-hidden rounded-3xl px-8 py-16 md:py-24 text-center"
    >
      <!-- Radial glow overlay -->
      <div
        class="pointer-events-none absolute inset-0"
        style="
          background: radial-gradient(600px circle at 50% 0%, rgba(255,255,255,0.15), transparent 70%);
          opacity: 0.3;
        "
      />

      <div class="relative">
        <h2 class="text-4xl md:text-6xl font-semibold tracking-tight leading-[1.02]">
          告别重复劳动。
          <br />
          开启智能协作。
        </h2>
        <p class="mt-6 text-white/60 max-w-md mx-auto text-sm leading-[1.6]">
          加入数千名开发者、架构师和技术负责人，用 Aether 构建下一代 AI 智能体系统。
        </p>
        <div class="mt-8 flex flex-col sm:flex-row items-center justify-center gap-3">
          <LandingButton label="开始使用" @click="emit('cta')" />
          <button
            type="button"
            class="group inline-flex items-center justify-center gap-1.5 rounded-full border border-white/15 text-white text-sm font-medium px-5 py-3 hover:bg-white/5 transition-colors"
          >
            <span>联系我们</span>
            <ChevronRight
              class="w-4 h-4 transition-transform duration-200 group-hover:translate-x-[1px]"
              :stroke-width="2"
            />
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
```

- [ ] **Step 2: Commit**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/components/landing/LandingCTA.vue
git commit -m "新增 LandingCTA 组件（底部行动号召）"
```

---

### Task 13: Replace LandingView.vue (page shell)

**Files:**
- Replace: `src/views/LandingView.vue`

- [ ] **Step 1: Replace LandingView.vue**

Path: `aether/docs/dev-ops/aether-frontend-v2/src/views/LandingView.vue`

**Full replacement.** Overwrite the entire file with:

```vue
<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LandingNavbar from '@/components/landing/LandingNavbar.vue'
import LandingHero from '@/components/landing/LandingHero.vue'
import LandingAppBar from '@/components/landing/LandingAppBar.vue'
import LandingConsole from '@/components/landing/LandingConsole.vue'
import LandingOrchestration from '@/components/landing/LandingOrchestration.vue'
import LandingIntegrations from '@/components/landing/LandingIntegrations.vue'
import LandingTestimonials from '@/components/landing/LandingTestimonials.vue'
import LandingPricing from '@/components/landing/LandingPricing.vue'
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
      <LandingConsole />
      <LandingOrchestration />
      <LandingIntegrations />
      <LandingTestimonials />
      <LandingPricing />
      <LandingCTA @cta="handleGetStarted" />
    </div>
  </div>
</template>
```

- [ ] **Step 2: Commit**

```bash
cd aether && git add docs/dev-ops/aether-frontend-v2/src/views/LandingView.vue
git commit -m "替换 LandingView 为多段落地落地页（移植自 fronted 项目）"
```

---

### Task 14: Verify build and fix issues

**Files:**
- (no new files — verification only)

- [ ] **Step 1: Run TypeScript type check**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vue-tsc --noEmit 2>&1
```

Expected: zero type errors.

- [ ] **Step 2: Run Vite build**

```bash
cd aether/docs/dev-ops/aether-frontend-v2 && npx vite build 2>&1
```

Expected: successful build with no errors.

- [ ] **Step 3: Verify LoginView is untouched**

```bash
cd aether && git diff HEAD -- docs/dev-ops/aether-frontend-v2/src/views/LoginView.vue
```

Expected: no diff output (file unchanged).

- [ ] **Step 4: Verify router is untouched**

```bash
cd aether && git diff HEAD -- docs/dev-ops/aether-frontend-v2/src/router/
```

Expected: no diff output (files unchanged).

- [ ] **Step 5: Check for any remaining "Aura" text in landing components**

```bash
cd aether && grep -r "Aura" docs/dev-ops/aether-frontend-v2/src/components/landing/ docs/dev-ops/aether-frontend-v2/src/views/LandingView.vue
```

Expected: zero matches.

- [ ] **Step 6: Final commit (if any fixes were needed)**

```bash
cd aether && git add -A && git status
```
