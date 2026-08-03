# Aether Landing 导航栏 & 页面结构改造设计

**日期**: 2026-08-03
**状态**: 已确认
**范围**: `aether/docs/dev-ops/aether-frontend-v2/`

## 1. 目标

基于 MateClaw 导航栏交互模式与视觉风格，改造 Aether 前端的 Landing 页面导航栏及内容结构。保持所有现有背景视觉元素不变，仅修改导航栏菜单项、锚点逻辑、路由跳转行为，以及对应的内容 section。

## 2. 约束

- **背景不动**：`LandingView.vue` 中的视频背景、SVG 噪音滤镜、固定辅助线等所有视觉元素保持不变
- **手术式修改**：只改导航相关的 3 个文件 + 新建 5 个组件/视图，不对项目其余部分进行任何修改
- **字体**：所有新增组件字体统一使用 `'Microsoft YaHei', '微软雅黑', sans-serif`
- **Reference 风格**：MateClaw 导航栏的固定定位、滚动背景过渡、当前项高亮、移动端折叠菜单

## 3. 导航栏设计 (LandingNavbar.vue)

### 3.1 导航项

```ts
const NAV_ITEMS = [
  { label: '亮点',     href: '#highlights',   type: 'anchor' },
  { label: '架构',     href: '#architecture',  type: 'anchor' },
  { label: '产品预览', href: '#preview',       type: 'anchor' },
  { label: '路线图',   href: '/roadmap',       type: 'route' },
  { label: '文档',     href: '/docs',          type: 'external' },
]
```

### 3.2 跳转规则

| type | 行为 |
|---|---|
| `anchor` | `e.preventDefault()` + `document.querySelector(href).scrollIntoView({ behavior: 'smooth' })` |
| `route` | `router.push(href)` |
| `external` | `window.open(href, '_blank')` |

### 3.3 交互细节

- **固定定位**：`fixed top-0 left-0 right-0 z-50`
- **滚动背景过渡**：`window.scrollY > 0` → 添加 `bg-black/70 backdrop-blur-xl border-b border-white/10`；CSS `transition-all duration-300`
- **当前项高亮**：锚点项监听 scroll 位置匹配当前可见 section；路由项匹配 `route.path`。高亮样式：`text-white bg-white/10`
- **默认悬停**：`text-white/60 hover:text-white hover:bg-white/5` + `rounded-lg`
- **桌面端**：`hidden md:flex items-center gap-1`
- **移动端**：`md:hidden` 汉堡按钮 → 点击展开 `mobileOpen` ref → `<Transition name="slide-down">` 下拉菜单，点击导航项后自动关闭
- **右侧**：GitHub 图标链接（`target="_blank"`）+ CTA 按钮（`LandingButton`，emit `cta` 事件）

### 3.4 现有功能保留

保留当前 `LandingNavbar` 的 `v-motion` 入场动画（`opacity: 0→1, y: -10→0`），保留 `@cta` emit 事件签名。

## 4. 页面 Section 结构 (LandingView.vue)

### 4.1 变更后的 Section 顺序

```
LandingNavbar        ← 修改（菜单项替换）
LandingHero          ← 保留不动
LandingAppBar        ← 保留不动
HighlightsSection    ← 🆕 替换 LandingIntegrations (id="highlights")
ArchitectureSection  ← 🆕 替换 LandingOrchestration (id="architecture")
PreviewSection       ← 🆕 替换 LandingConsole (id="preview")
LandingCTA           ← 保留不动
```

删除：`LandingTestimonials`、`LandingPricing`

### 4.2 亮点 Section (HighlightsSection.vue)

5 张特性卡片，左侧彩色边框，垂直堆叠排列：

1. 🔄 自研 ReActAgent 引擎 — 主循环 + 多层上下文压缩 + 指数退避重试，100 轮稳定运行
2. 📊 YAML 配置驱动多 Agent 编排 — SEQUENTIAL / PARALLEL / LOOP 三种工作流模式
3. 🔧 Agent 级工具作用域 — MCP + Skills 工具接入，per-agent 精确控制工具可见性
4. 🏗️ DDD 分层架构 — 6 模块清晰分层，trigger → api → domain → infrastructure
5. ⚡ 流式对话 & 工具编排 — SSE 实时推送，工具并发/串行可配，上下文自动压缩

Section 标题：Aether 核心亮点

### 4.3 架构 Section (ArchitectureSection.vue)

4 层卡片式模块图，箭头标注依赖方向：

- **aether-trigger** (Trigger 层) — REST 控制器，Chat API / SSE 流式端点
- **aether-api** (API 层) — 服务接口 & DTO 定义，跨模块契约
- **aether-domain** (Domain Core 层) — 核心引擎，橙色高亮，内部 2×3 子组件网格：
  - ReActAgent（主循环·重试·熔断）
  - GraphExecutor（顺序·并行·循环）
  - ContextManager（压缩·截断·预算）
  - ToolExecutor（并发·串行·适配）
  - ArmoryService（YAML→Bean 装配）
  - MemoryStore（持久记忆读写）
- **aether-infrastructure** + **aether-types** 并排（Infra 层 + Types 层）

Section 标题：系统架构

### 4.4 产品预览 Section (PreviewSection.vue)

三列卡片布局，每列展示一个核心功能截图占位：

| 列 | 标题 | 描述 |
|---|---|---|
| 📊 仪表盘 | Token 用量 / Agent 状态 / 会话趋势 |
| 💬 多 Agent 对话 | 流式输出 / 工具调用可视化 / 会话管理 |
| ⚙️ Agent 管理 | YAML 配置 / 工具绑定 / 权限控制 |

截图区域使用占位背景色 + 文字标注，`border-radius: 8px`，后续替换为真实截图。

Section 标题：产品预览

## 5. 路线图页面 (RoadmapView.vue)

### 5.1 页面结构

- 复用 `LandingNavbar`（页面顶部固定导航）
- 内容区：`max-w-4xl mx-auto px-6 pt-28 pb-20`
- 垂直时间线布局：`border-left: 2px solid` + 圆点标记

### 5.2 时间线内容

| 版本 | 状态 | 内容 |
|---|---|---|
| v1.0 — 核心引擎 | ✅ 已完成 | ReActAgent 主循环 · ChatModel 延迟注入 · MCP/Skills 工具集成 · DDD 6 模块分层 · SSE 流式对话 |
| v1.1 — 多 Agent 编排 | ✅ 已完成 | YAML 配置驱动 AgentGraph · SEQUENTIAL/PARALLEL/LOOP 工作流 · 上下文压缩与熔断 · Vue 3 前端 |
| v1.2 — 工具生态 & 安全 | 🔵 进行中 | Agent 级工具作用域 · 工具调用权限控制 · Skills 本地执行沙箱 · 审计日志 · 多 Provider 路由 |
| v1.3 — 记忆 & 知识图谱 | ⚪ 计划中 | 向量化长期记忆 · 知识图谱自动构建 · 跨会话上下文继承 · RAG 检索增强 · 用户级记忆隔离 |
| v2.0 — 团队协作 | ⚪ 计划中 | 多用户工作空间 · Agent 共享与权限 · 团队知识库 · Webhook 触发器 · 定时任务调度 · 企业 SSO |

## 6. 文档页面 (DocsView.vue)

### 6.1 页面结构

- 新标签页打开：`window.open('/docs', '_blank')`
- 整体布局：左侧固定目录（200px）+ 右侧正文区（flex-1）
- 左侧目录高亮当前选中项

### 6.2 文档目录树

```
📚 Aether 文档
├─ 🚀 快速开始
│  ├─ 环境要求 (Java 17, Maven 3.x, Node 18)
│  ├─ 克隆与构建
│  ├─ 最小配置模板 (only-one-agent.yml)
│  └─ 验证 Agent 运行
├─ 📖 核心概念
│  ├─ Agent 生命周期 (装配 → 执行 → 销毁)
│  ├─ ReActAgent 引擎详解
│  ├─ YAML 配置完整参考
│  ├─ AgentGraph & 工作流类型
│  ├─ 工具系统 (MCP / Skills / 作用域)
│  ├─ 上下文管理与压缩策略
│  └─ 记忆系统 (MemoryStore)
├─ 🛠️ 开发指南
│  ├─ 6 模块 DDD 架构
│  ├─ 自定义 Agent 开发
│  ├─ 添加新工具 (MCP / Skills)
│  ├─ 前端开发 (Vue 3 + Vite)
│  ├─ Docker 部署
│  └─ 故障排查
└─ 📋 API 参考
   ├─ Chat API (POST /api/v1/chat)
   ├─ SSE 流式协议
   ├─ Agent 管理 API
   └─ 模型管理 API
```

### 6.3 右侧默认内容

默认展示「快速开始」章节正文，包含：
- 环境要求列表（JDK 17+ / Maven 3.8+ / Node 18+）
- `git clone` + `mvn clean install` 构建命令（代码块样式）
- 最小 YAML 配置模板（代码块样式）
- `curl` 验证请求示例（代码块样式）

目录项点击后右侧切换到对应章节正文（后续迭代实现，当前仅静态展示快速开始）。

## 7. 路由配置

```ts
// routes.ts 新增:
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

路由使用 `createWebHashHistory`，URL 形式为 `/#/roadmap` 和 `/#/docs`。

## 8. 文件变更清单

| 操作 | 文件 | 说明 |
|---|---|---|
| 修改 | `src/components/landing/LandingNavbar.vue` | 5 导航项 + GitHub + 滚动背景 + 移动端折叠 + 高亮 |
| 修改 | `src/views/LandingView.vue` | 替换 3 个 section 引用，删除 Testimonials/Pricing 引用 |
| 修改 | `src/router/routes.ts` | 新增 /roadmap 和 /docs 路由 |
| 新建 | `src/components/landing/HighlightsSection.vue` | 亮点 section — 5 张特性卡片 |
| 新建 | `src/components/landing/ArchitectureSection.vue` | 架构 section — 4 层模块卡片 |
| 新建 | `src/components/landing/PreviewSection.vue` | 产品预览 section — 三列截图占位 |
| 新建 | `src/views/RoadmapView.vue` | 路线图页面 — 垂直时间线 |
| 新建 | `src/views/DocsView.vue` | 文档首页 — 左目录 + 右正文 |

## 9. 不修改的文件

- `LandingHero.vue` / `LandingAppBar.vue` / `LandingCTA.vue` — 保留不动
- `LandingView.vue` 背景视频、SVG 滤镜、辅助线 — 保留不动
- `AetherLogo.vue` / `LandingButton.vue` — 复用不动
- `App.vue` / `main.ts` / `index.html` / `style.css` — 不动
- `LandingConsole.vue` / `LandingOrchestration.vue` / `LandingIntegrations.vue` / `LandingTestimonials.vue` / `LandingPricing.vue` — 不再引用但**不删除文件**，保持代码库整洁性可选后续清理
- 所有 `app/` 路由下的组件和视图 — 不动

## 10. 全局样式规范

- **字体**：所有新增组件根元素或 `body` 等效层设置 `font-family: 'Microsoft YaHei', '微软雅黑', sans-serif`
- **颜色**：沿用现有多色系（`text-white`、`text-white/60`、`text-white/80`、`bg-white/5`、`bg-white/10`、`bg-black/70` 等）
- **圆角**：卡片 `rounded-xl` 或 `rounded-2xl`，导航项 `rounded-lg`
- **间距**：section 间 `py-20 md:py-32`，容器 `max-w-6xl mx-auto px-6`
- **动效**：新 section 使用 `@vueuse/motion` 的 `v-motion` 指令添加入场动画（与现有 section 一致）
