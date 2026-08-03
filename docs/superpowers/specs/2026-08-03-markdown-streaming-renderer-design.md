# Markdown 流式渲染集成设计

> 日期：2026-08-03 | 状态：已批准 | 框架：markstream-vue

## 1. 问题陈述

智能体输出的原始 Markdown 文本直接在前端显示，导致：
- 标题符号 `#`、加粗符号 `*`、换行符 `\n` 等 Markdown 语法标记可见
- 文本内容挤成一团，缺乏格式化排版

**根因：** 当前使用 `markdown-it` 同步全量渲染器，不适用于流式场景。每个 SSE chunk 到来时触发 `v-html` 全量 DOM 重建，且无法正确处理未闭合的 Markdown 语法（半截代码块、未完成的表格等）。

## 2. 技术方案

采用 **markstream-vue**（npm: `markstream-vue`，MIT 协议，作者 Simon-He95），专为 AI 流式场景设计的 Vue 3 Markdown 渲染库。

### 2.1 核心机制

- **解析与渲染分离：** `parseMarkdownToStructure()` 增量解析流式文本为 `ParsedNode[]` 节点树，`<MarkdownRender>` 组件增量渲染节点树
- **打字机效果：** 增量批处理模式（`max-live-nodes: 0`），小批次渐进渲染
- **零闪烁：** 不进行全量 DOM 重建，仅更新变化部分

### 2.2 改造前 vs 改造后

```
改造前:
  SSE chunk → store.appendMessageDelta(text) → markdown-it.render() → v-html → DOM全量替换

改造后:
  SSE chunk → store.appendMessageDelta(text) → parseMarkdownToStructure() → nodes[]
           → <MarkdownRender :nodes :batchRendering /> → 增量DOM更新
```

## 3. 变更范围

### 3.1 手术式修改（4 个文件）

| 文件 | 操作 | 内容 |
|---|---|---|
| `package.json` | 修改 | 替换 `markdown-it`/`highlight.js` 为 `markstream-vue` + peer deps |
| `utils/markdown.ts` | 重写 | 导出 `parseStreamingMarkdown()` + 解析器工厂 |
| `components/chat/MessageBubble.vue` | 修改 | agent 消息区域 `v-html` → `<MarkdownRender>` |
| `D:\Config\Markdown/` | 新建 | Shiki/Mermaid/KaTeX 可选配置文件 |

### 3.2 不修改的文件

- `stores/chat.ts` — 继续使用 `appendMessageDelta` 累积文本
- `sse-client.ts` — 零改动
- `MessageList.vue` — 零改动
- `AgentAccordionItem.vue` — 零改动

## 4. 组件设计

### 4.1 MessageBubble.vue 改造

**核心逻辑：**

```ts
import MarkdownRender, { getMarkdown, parseMarkdownToStructure } from 'markstream-vue'
import 'markstream-vue/index.css'
import type { ParsedNode } from 'markstream-vue'

const md = getMarkdown()
const parsedNodes = ref<ParsedNode[]>([])

watch(
  () => props.message.text,
  (text) => {
    try {
      parsedNodes.value = parseMarkdownToStructure(text || '', md)
    } catch (e) {
      console.warn('[Markdown] 流式解析异常，回退到纯文本', e)
      parsedNodes.value = [{
        type: 'paragraph',
        children: [{ type: 'text', value: text || '' }]
      }]
    }
  }
)
```

**模板变更：**

```html
<!-- 旧: v-html -->
<div class="prose prose-invert prose-sm max-w-none text-[#DEDBC8]" v-html="renderedText" />

<!-- 新: MarkdownRender 组件 -->
<MarkdownRender
  :nodes="parsedNodes"
  :max-live-nodes="0"
  :batch-rendering="{ renderBatchSize: 8, renderBatchDelay: 16 }"
  :is-dark="true"
/>
```

**不变部分：**
- 用户消息：保持 `whitespace-pre-wrap` 纯文本
- 系统消息：保持不变
- `ToolCallDisplay`、`MessageReactions`、流式光标、时间戳等 UI 元素保持不变

### 4.2 markdown.ts 重写

```ts
// markdown.ts — 流式 Markdown 工具集
import { getMarkdown, parseMarkdownToStructure } from 'markstream-vue'
import type { ParsedNode } from 'markstream-vue'

let _md: ReturnType<typeof getMarkdown> | null = null

export function getMarkdownParser() {
  if (!_md) {
    _md = getMarkdown()
  }
  return _md
}

export function parseStreamingMarkdown(text: string): ParsedNode[] {
  return parseMarkdownToStructure(text, getMarkdownParser())
}

// 保留 escapeHtml（其他组件可能使用）
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
```

### 4.3 扩展配置（D:\Config\Markdown/）

```
D:\Config\Markdown/
├── shiki.config.ts      # Shiki 主题（默认 dark-plus）和语言包配置
├── mermaid.config.ts    # Mermaid 主题（默认 dark）和渲染选项
└── katex.config.ts      # KaTeX Web Worker 可选项
```

所有配置文件均为可选，`markstream-vue` 内置默认值。Monaco 编辑器通过 `stream-monaco` peer dep 按需加载。

## 5. 数据流

```
用户输入消息
  ↓
ChatInput.vue → store.sendMessage()
  ↓
api/chat.ts → SSE POST /api/v1/chat_stream
  ↓
sse-client.ts → 逐行解析 SSE data
  ↓ (textDelta event)
store.handleTextDelta() → store.appendMessageDelta(placeholderId, delta)
  ↓ (message.text 变更)
MessageBubble.vue watch(message.text) → parseMarkdownToStructure()
  ↓ (parsedNodes 更新)
<MarkdownRender :nodes="parsedNodes" /> → 增量 DOM 更新
```

## 6. 边缘情况

| 场景 | 处理 |
|---|---|
| 空消息 | `parseMarkdownToStructure('')` → 空数组，渲染空 div |
| 纯文本（无语法） | 自动包裹为 `<p>` 标签 |
| 未闭合代码块 | 流式解析器保留状态，不崩溃，闭合后自动补全 |
| 中断的流 | `finalizeMessage` 停止流式标记，渲染已接收内容 |
| 历史消息加载 | 完整文本一次性解析，正常渲染 |
| 切换会话 | watch 回调收到空文本，返回空 ParsedNode[] |
| 解析异常 | try-catch 兜底，构造纯文本节点确保内容可见 |

## 7. 依赖变更

```diff
package.json:
- "highlight.js": "^11.11.1"
- "markdown-it": "^14.1.0"
- "@types/markdown-it": "^14.1.2"
+ "markstream-vue": "^1.x"
+ "shiki": "^1.x"
+ "mermaid": "^11.x"
+ "katex": "^0.16.x"
```

## 8. 样式

- `markstream-vue` 样式通过 `.markstream-vue` 容器类作用域隔离
- 移除 agent 消息区域原有的 `prose prose-invert prose-sm` Tailwind Typography 类
- 使用 `:is-dark="true"` prop 启用暗色主题，与 Aether 现有暗色主题保持一致

## 9. 验证清单

- [ ] 流式文本正确渲染：标题/列表/加粗/代码块逐步格式化
- [ ] 打字机效果流畅无闪烁
- [ ] 代码高亮颜色准确
- [ ] Mermaid 图表随内容补全渐进显示
- [ ] 切换会话各消息独立渲染
- [ ] 历史消息完整 Markdown 正确渲染
- [ ] 中断流式后显示已接收内容
- [ ] 解析异常兜底纯文本显示
