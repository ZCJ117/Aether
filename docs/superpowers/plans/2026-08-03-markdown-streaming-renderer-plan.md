# Markdown 流式渲染集成 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Aether 前端的 Markdown 渲染从同步 `markdown-it` 替换为流式 `markstream-vue`，实现 AI 对话场景的打字机效果流式渲染。

**Architecture:** SSE 流式文本 → store 累积 → `parseMarkdownToStructure()` 增量解析 → `<MarkdownRender>` 组件增量 DOM 更新。仅修改 `package.json`、`markdown.ts`、`MessageBubble.vue`，不改动 store 和 SSE 层。

**Tech Stack:** Vue 3.5 + TypeScript + Vite 5 + markstream-vue (1.x) + shiki + mermaid + katex

---

## 文件结构

| 文件 | 操作 | 职责 |
|---|---|---|
| `package.json` | 修改 | 依赖替换 |
| `src/utils/markdown.ts` | 重写 | 导出流式解析函数 + 解析器单例 |
| `src/components/chat/MessageBubble.vue` | 修改 | v-html → MarkdownRender 组件 |
| `D:\Config\Markdown\shiki.config.ts` | 创建 | Shiki 主题和语言配置 |
| `D:\Config\Markdown\mermaid.config.ts` | 创建 | Mermaid 主题配置 |
| `D:\Config\Markdown\katex.config.ts` | 创建 | KaTeX 渲染配置 |

---

### Task 1: 更新项目依赖

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/package.json`

- [ ] **Step 1: 修改 package.json**

将以下依赖替换：

```diff
  "dependencies": {
    // ... 其他保持不变
-   "highlight.js": "^11.11.1",
-   "markdown-it": "^14.1.0",
+   "markstream-vue": "^1.0.0",
+   "shiki": "^1.24.0",
+   "mermaid": "^11.4.0",
+   "katex": "^0.16.11",
    // ... 其他保持不变
  },
  "devDependencies": {
-   "@types/markdown-it": "^14.1.2",
    // ... 其他保持不变
  }
```

- [ ] **Step 2: 安装依赖**

```bash
cd docs/dev-ops/aether-frontend-v2 && pnpm install
```

Expected: 安装成功，无错误。`markstream-vue`、`shiki`、`mermaid`、`katex` 出现在 `node_modules` 中。

- [ ] **Step 3: 提交**

```bash
git add docs/dev-ops/aether-frontend-v2/package.json docs/dev-ops/aether-frontend-v2/pnpm-lock.yaml
git commit -m "依赖替换: markdown-it → markstream-vue + shiki/mermaid/katex"
```

---

### Task 2: 创建 Markdown 扩展配置文件

**Files:**
- Create: `D:\Config\Markdown\shiki.config.ts`
- Create: `D:\Config\Markdown\mermaid.config.ts`
- Create: `D:\Config\Markdown\katex.config.ts`

- [ ] **Step 1: 创建目录**

```bash
mkdir -p "D:/Config/Markdown"
```

- [ ] **Step 2: 创建 shiki.config.ts**

```ts
// D:\Config\Markdown\shiki.config.ts
// Shiki 代码高亮配置 — Aether 暗色主题

import type { BundledLanguage, BundledTheme } from 'shiki'

/** Shiki 高亮主题 */
export const SHIKI_THEME: BundledTheme = 'dark-plus'

/** 预加载的语言列表（按需扩展） */
export const SHIKI_LANGS: BundledLanguage[] = [
  'java',
  'python',
  'javascript',
  'typescript',
  'bash',
  'json',
  'yaml',
  'xml',
  'sql',
  'markdown',
  'css',
  'html',
  'vue',
  'shell',
  'dockerfile',
  'properties',
]

/** 是否启用 Monaco 编辑器（流式代码块的富编辑体验） */
export const ENABLE_MONACO = false
```

- [ ] **Step 3: 创建 mermaid.config.ts**

```ts
// D:\Config\Markdown\mermaid.config.ts
// Mermaid 图表配置 — Aether 暗色主题

import type { MermaidConfig } from 'mermaid'

/** Mermaid 全局配置 */
export const MERMAID_CONFIG: MermaidConfig = {
  theme: 'dark',
  themeVariables: {
    primaryColor: '#4ade80',
    primaryTextColor: '#DEDBC8',
    lineColor: '#6b7280',
    secondaryColor: '#374151',
    tertiaryColor: '#1f2937',
  },
  securityLevel: 'sandbox',
  startOnLoad: false,
}
```

- [ ] **Step 4: 创建 katex.config.ts**

```ts
// D:\Config\Markdown\katex.config.ts
// KaTeX 数学渲染配置

/** KaTeX 渲染选项 */
export const KATEX_OPTIONS = {
  /** 遇到解析错误时抛出异常（false = 渲染原始文本） */
  throwOnError: false,
  /** 是否启用 Web Worker 卸载渲染（CDN 加载 KaTeX 时可用） */
  useWorker: false,
}
```

- [ ] **Step 5: 提交**

```bash
git add "D:/Config/Markdown/"
git commit -m "新增Markdown扩展配置文件: Shiki/Mermaid/KaTeX"
```

---

### Task 3: 重写 markdown.ts 工具模块

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/utils/markdown.ts`（全量替换）

- [ ] **Step 1: 全量重写 markdown.ts**

```ts
// markdown.ts — 流式 Markdown 解析工具集
// 基于 markstream-vue 的增量解析，专为 SSE 流式对话场景设计

import { getMarkdown, parseMarkdownToStructure } from 'markstream-vue'
import type { ParsedNode } from 'markstream-vue'

/** 全局单例 Markdown 解析器实例 */
let _md: ReturnType<typeof getMarkdown> | null = null

/**
 * 获取全局单例 Markdown 解析器。
 * 首次调用时初始化，后续调用复用同一实例以保证解析状态连续性。
 */
export function getMarkdownParser(): ReturnType<typeof getMarkdown> {
  if (!_md) {
    _md = getMarkdown()
  }
  return _md
}

/**
 * 流式增量解析 Markdown 文本为节点树。
 * 专用于 SSE 流式场景：每次 textDelta 到达时调用，传入累积的完整文本，
 * 返回的 ParsedNode[] 直接喂给 <MarkdownRender :nodes /> 组件。
 *
 * @param text - 累积的 Markdown 原始文本（非增量 delta）
 * @returns 解析后的节点树，可直接用于 MarkdownRender 组件
 */
export function parseStreamingMarkdown(text: string): ParsedNode[] {
  if (!text) return []
  return parseMarkdownToStructure(text, getMarkdownParser())
}

/**
 * HTML 转义（保留用于非 Markdown 场景的文本安全输出）。
 * 用户消息等不需要 Markdown 渲染的场景使用此函数。
 */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 同步全量渲染（保留用于历史消息等已有完整文本的场景）。
 * 返回 HTML 字符串，兼容旧的 v-html 用法。
 * 注意：新代码应优先使用 parseStreamingMarkdown + MarkdownRender 组件。
 */
export function renderMarkdown(text: string): string {
  if (!text) return ''
  const nodes = parseMarkdownToStructure(text, getMarkdownParser())
  // 将节点树序列化为 HTML 字符串（回退路径）
  return serializeNodesToHtml(nodes)
}

/** 简化的节点树→HTML 序列化（回退用） */
function serializeNodesToHtml(nodes: ParsedNode[]): string {
  return nodes
    .map((node) => {
      if (node.type === 'paragraph') {
        const children = node.children?.map((c: { value?: string }) => c.value || '').join('') || ''
        return `<p>${children}</p>`
      }
      if (node.type === 'heading') {
        const level = (node as { depth?: number }).depth || 1
        const children = node.children?.map((c: { value?: string }) => c.value || '').join('') || ''
        return `<h${level}>${children}</h${level}>`
      }
      if (node.type === 'code') {
        const lang = (node as { lang?: string }).lang || ''
        const value = (node as { value?: string }).value || ''
        return `<pre><code class="language-${lang}">${escapeHtml(value)}</code></pre>`
      }
      if (node.type === 'list') {
        const items = (node.children || [])
          .map((item: { children?: Array<{ value?: string }> }) => {
            const text = item.children?.map((c) => c.value || '').join('') || ''
            return `<li>${text}</li>`
          })
          .join('')
        const tag = (node as { ordered?: boolean }).ordered ? 'ol' : 'ul'
        return `<${tag}>${items}</${tag}>`
      }
      if (node.type === 'blockquote') {
        const children = node.children?.map((c: { value?: string }) => c.value || '').join('') || ''
        return `<blockquote>${children}</blockquote>`
      }
      const children = node.children?.map((c: { value?: string }) => c.value || '').join('') || ''
      return `<p>${children}</p>`
    })
    .join('\n')
}
```

- [ ] **Step 2: 验证 TypeScript 编译**

```bash
cd docs/dev-ops/aether-frontend-v2 && npx vue-tsc --noEmit src/utils/markdown.ts
```

Expected: 无类型错误（如有 `ParsedNode` 类型导入问题，根据 markstream-vue 实际导出调整）。

- [ ] **Step 3: 提交**

```bash
git add docs/dev-ops/aether-frontend-v2/src/utils/markdown.ts
git commit -m "重写markdown.ts: markdown-it → markstream-vue流式解析"
```

---

### Task 4: 改造 MessageBubble.vue — agent 消息 Markdown 渲染

**Files:**
- Modify: `docs/dev-ops/aether-frontend-v2/src/components/chat/MessageBubble.vue`

- [ ] **Step 1: 修改 script 部分 — 导入和计算逻辑**

替换 script setup 中的 Markdown 渲染逻辑（第 5 行和第 16–22 行区域）：

```diff
  <script setup lang="ts">
  import { ref, computed } from 'vue'
  import { Copy, RotateCcw } from 'lucide-vue-next'
  import type { ChatMessage, ToolCallState } from '@/types/chat'
- import { renderMarkdown } from '@/utils/markdown'
+ import { parseStreamingMarkdown } from '@/utils/markdown'
+ import MarkdownRender from 'markstream-vue'
+ import 'markstream-vue/index.css'
+ import type { ParsedNode } from 'markstream-vue'
  import ToolCallDisplay from './ToolCallDisplay.vue'
  import MessageReactions from './MessageReactions.vue'

  const props = defineProps<{
    message: ChatMessage
  }>()

  const showTimestamp = ref(false)
  const expandedMeta = computed<Record<string, unknown>>(() => props.message.meta || {})

- const renderedText = computed(() => {
-   try {
-     return renderMarkdown(props.message.text || '')
-   } catch {
-     return props.message.text || ''
-   }
- })

+ // 流式 Markdown 解析：watch message.text 变化时增量解析
+ const parsedNodes = ref<ParsedNode[]>([])
+
+ watch(
+   () => props.message.text,
+   (text) => {
+     try {
+       parsedNodes.value = parseStreamingMarkdown(text || '')
+     } catch (e) {
+       console.warn('[Markdown] 流式解析异常，回退到纯文本', e)
+       // 解析失败时手动构造纯文本节点，确保内容始终可见
+       parsedNodes.value = [
+         {
+           type: 'paragraph',
+           children: [{ type: 'text', value: text || '' }],
+         } as unknown as ParsedNode,
+       ]
+     }
+   },
+   { immediate: true }
+ )
```

需要在 import 中增加 `watch`（从 vue 导入已有 `ref, computed`，补充 `watch`）：

```diff
- import { ref, computed } from 'vue'
+ import { ref, computed, watch } from 'vue'
```

- [ ] **Step 2: 修改 template 部分 — agent 消息的 Markdown 渲染区域**

将第 115–119 行的 `v-html` 替换为 `MarkdownRender` 组件：

```diff
        <!-- Markdown content -->
-       <div
-         class="prose prose-invert prose-sm max-w-none text-[#DEDBC8]"
-         v-html="renderedText"
-       />
+       <MarkdownRender
+         v-if="parsedNodes.length > 0"
+         :nodes="parsedNodes"
+         :max-live-nodes="0"
+         :batch-rendering="{ renderBatchSize: 8, renderBatchDelay: 16 }"
+         :is-dark="true"
+       />
+       <div
+         v-else-if="message.streaming"
+         class="text-[#DEDBC8]/40 text-sm italic"
+       >
+         思考中...
+       </div>
```

- [ ] **Step 3: 验证 TypeScript 编译**

```bash
cd docs/dev-ops/aether-frontend-v2 && npx vue-tsc --noEmit
```

Expected: 无新增类型错误。如有类型不匹配，根据 markstream-vue 实际导出类型调整。

- [ ] **Step 4: 验证 Vite 构建**

```bash
cd docs/dev-ops/aether-frontend-v2 && npx vite build
```

Expected: 构建成功，无错误。

- [ ] **Step 5: 提交**

```bash
git add docs/dev-ops/aether-frontend-v2/src/components/chat/MessageBubble.vue
git commit -m "MessageBubble集成markstream-vue流式Markdown渲染"
```

---

### Task 5: 端到端验证

- [ ] **Step 1: 启动开发服务器**

```bash
cd docs/dev-ops/aether-frontend-v2 && npx vite --port 5173
```

- [ ] **Step 2: 验证流式渲染**

在浏览器中打开应用，发送一条会触发 Markdown 输出的消息（如"用Markdown格式介绍Java"），确认：

1. **打字机效果** — 文本逐步出现，非一次性全量显示
2. **标题渲染** — `#` 符号不可见，标题以正确字号层级显示
3. **加粗/斜体** — `**text**` 显示为加粗文本，`*text*` 显示为斜体
4. **代码块** — 代码块有语法高亮，背景色区别于正文
5. **列表** — 有序/无序列表正确缩进和符号显示
6. **无闪烁** — 流式过程中无 DOM 重排或闪烁
7. **暗色主题** — 渲染内容与 Aether 暗色主题一致

- [ ] **Step 3: 验证边缘情况**

- 发送包含 Mermaid 图表的请求（如"画一个流程图"）→ 确认图表渐进渲染
- 发送包含数学公式的请求（如"写一个矩阵"）→ 确认 KaTeX 渲染
- 取消正在进行的流式对话 → 确认已接收内容正常显示
- 切换会话 → 确认各会话消息独立渲染

---

## 自审查

1. **Spec 覆盖检查：**
   - Task 1 覆盖依赖变更 ✓
   - Task 2 覆盖扩展配置（D:\Config\Markdown） ✓
   - Task 3 覆盖 markdown.ts 重写 ✓
   - Task 4 覆盖 MessageBubble.vue 改造（含错误处理/回退） ✓
   - Task 5 覆盖验证清单 ✓

2. **占位符检查：** 无 "TBD"、"TODO"、空函数体。所有代码完整。

3. **类型一致性：** `parseStreamingMarkdown` 在 Task 3 定义，Task 4 导入使用，签名一致。`ParsedNode` 从 `markstream-vue` 导入，Task 3 和 Task 4 使用相同类型。
