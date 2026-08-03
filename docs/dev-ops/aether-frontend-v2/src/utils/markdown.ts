// markdown.ts — 流式 Markdown 解析工具集
// 基于 markstream-vue 的增量解析，专为 SSE 流式对话场景设计

import { getMarkdown, parseMarkdownToStructure } from 'markstream-vue'
import type { ParsedNode, MarkdownIt } from 'markstream-vue'

/** 全局单例 Markdown 解析器实例 */
let _md: MarkdownIt | null = null

/**
 * 获取全局单例 Markdown 解析器。
 * 首次调用时初始化，后续调用复用同一实例以保证解析状态连续性。
 */
export function getMarkdownParser(): MarkdownIt {
  if (!_md) {
    _md = getMarkdown()
  }
  return _md
}

/**
 * 流式增量解析 Markdown 文本为节点树。
 * 专用于 SSE 流式场景：每次 textDelta 到达时调用，传入累积的完整文本，
 * 返回的 ParsedNode[] 直接喂给 &lt;MarkdownRender :nodes /&gt; 组件。
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
      const anyNode = node as Record<string, unknown> & ParsedNode
      const childrenArr = anyNode.children as Array<{ content?: string }> | undefined

      if (node.type === 'paragraph') {
        const children = childrenArr?.map((c) => c.content || '').join('') || ''
        return `<p>${children}</p>`
      }
      if (node.type === 'heading') {
        const level = (node as { level?: number }).level || 1
        const children = childrenArr?.map((c) => c.content || '').join('') || ''
        return `<h${level}>${children}</h${level}>`
      }
      if (node.type === 'code_block') {
        const lang = (node as { language?: string }).language || ''
        const code = (node as { code?: string }).code || ''
        return `<pre><code class="language-${lang}">${escapeHtml(code)}</code></pre>`
      }
      if (node.type === 'list') {
        const items = ((node as { items?: Array<{ children?: Array<{ content?: string }> }> }).items || [])
          .map((item) => {
            const text = item.children?.map((c) => c.content || '').join('') || ''
            return `<li>${text}</li>`
          })
          .join('')
        const tag = (node as { ordered?: boolean }).ordered ? 'ol' : 'ul'
        return `<${tag}>${items}</${tag}>`
      }
      if (node.type === 'blockquote') {
        const children = childrenArr?.map((c) => c.content || '').join('') || ''
        return `<blockquote>${children}</blockquote>`
      }
      // 回退：尝试获取 children 作为 paragraph 渲染
      const children = childrenArr?.map((c) => c.content || '').join('') || ''
      return `<p>${children}</p>`
    })
    .join('\n')
}
