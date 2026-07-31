import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { WorkflowSummary, DAGValidationError } from '@/types/workflow'

/** 生成简单的 UUID v4 */
function generateId(): string {
  return crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
}

/** 节点类型 */
type NodeType = 'start' | 'agent' | 'condition' | 'merge' | 'end' | 'loop'

/** DAG 节点 */
interface DAGNode {
  id: string
  type: NodeType
  position: { x: number; y: number }
  data: Record<string, unknown>
}

/** DAG 边 */
interface DAGEdge {
  id: string
  source: string
  target: string
  type: string
}

/** 快照类型 */
interface Snapshot {
  nodes: DAGNode[]
  edges: DAGEdge[]
}

export const useWorkflowStore = defineStore('workflow', () => {
  // ========== State ==========
  const nodes = ref<DAGNode[]>([])
  const edges = ref<DAGEdge[]>([])
  const selectedNode = ref<string | null>(null)
  const workflowId = ref<string | null>(null)
  const workflowName = ref('未命名工作流')
  const workflowDescription = ref('')
  const isDirty = ref(false)
  const undoStack = ref<Snapshot[]>([])
  const redoStack = ref<Snapshot[]>([])
  const validationErrors = ref<{ nodeId?: string; message: string }[]>([])
  const workflows = ref<WorkflowSummary[]>([])
  const isLoading = ref(false)

  // ========== Getters ==========
  const canUndo = computed(() => undoStack.value.length > 0)
  const canRedo = computed(() => redoStack.value.length > 0)
  const nodeCount = computed(() => nodes.value.length)
  const isValid = computed(() => validationErrors.value.length === 0)

  // ========== Actions ==========

  function addNode(type: NodeType, position: { x: number; y: number }): string {
    const id = generateId()
    const node: DAGNode = {
      id,
      type,
      position,
      data: {
        type,
        label: type === 'start' ? '开始' : type === 'end' ? '结束' : '',
      },
    }
    pushSnapshot()
    nodes.value.push(node)
    isDirty.value = true
    return id
  }

  function removeNode(nodeId: string): void {
    pushSnapshot()
    nodes.value = nodes.value.filter((n) => n.id !== nodeId)
    edges.value = edges.value.filter((e) => e.source !== nodeId && e.target !== nodeId)
    if (selectedNode.value === nodeId) {
      selectedNode.value = null
    }
    isDirty.value = true
  }

  function updateNode(nodeId: string, data: Record<string, unknown>): void {
    const node = nodes.value.find((n) => n.id === nodeId)
    if (!node) return
    pushSnapshot()
    node.data = { ...node.data, ...data }
    isDirty.value = true
  }

  function selectNode(nodeId: string | null): void {
    selectedNode.value = nodeId
  }

  function addEdge(source: string, target: string, type = 'default'): string {
    const id = generateId()
    pushSnapshot()
    edges.value.push({ id, source, target, type })
    isDirty.value = true
    return id
  }

  function removeEdge(edgeId: string): void {
    pushSnapshot()
    edges.value = edges.value.filter((e) => e.id !== edgeId)
    isDirty.value = true
  }

  function pushSnapshot(): void {
    undoStack.value.push({
      nodes: JSON.parse(JSON.stringify(nodes.value)),
      edges: JSON.parse(JSON.stringify(edges.value)),
    })
    redoStack.value = []
  }

  function undo(): void {
    if (!canUndo.value) return
    const current: Snapshot = {
      nodes: JSON.parse(JSON.stringify(nodes.value)),
      edges: JSON.parse(JSON.stringify(edges.value)),
    }
    redoStack.value.push(current)
    const snapshot = undoStack.value.pop()!
    nodes.value = snapshot.nodes
    edges.value = snapshot.edges
    isDirty.value = true
  }

  function redo(): void {
    if (!canRedo.value) return
    const current: Snapshot = {
      nodes: JSON.parse(JSON.stringify(nodes.value)),
      edges: JSON.parse(JSON.stringify(edges.value)),
    }
    undoStack.value.push(current)
    const snapshot = redoStack.value.pop()!
    nodes.value = snapshot.nodes
    edges.value = snapshot.edges
    isDirty.value = true
  }

  function validate(): { nodeId?: string; message: string }[] {
    const errors: { nodeId?: string; message: string }[] = []

    // 检查是否有 start 和 end 节点
    const startNodes = nodes.value.filter((n) => n.type === 'start')
    const endNodes = nodes.value.filter((n) => n.type === 'end')

    if (startNodes.length === 0) {
      errors.push({ message: '工作流缺少开始节点' })
    }
    if (endNodes.length === 0) {
      errors.push({ message: '工作流缺少结束节点' })
    }

    // 检测环路：使用拓扑排序
    const adjacency = new Map<string, string[]>()
    const inDegree = new Map<string, number>()

    for (const node of nodes.value) {
      adjacency.set(node.id, [])
      inDegree.set(node.id, 0)
    }

    for (const edge of edges.value) {
      if (!adjacency.has(edge.target)) {
        errors.push({ nodeId: edge.target, message: `边指向不存在的节点: ${edge.target}` })
        continue
      }
      adjacency.get(edge.source)?.push(edge.target)
      inDegree.set(edge.target, (inDegree.get(edge.target) ?? 0) + 1)
    }

    // Kahn 拓扑排序检测环
    const queue: string[] = []
    for (const [id, degree] of inDegree) {
      if (degree === 0) queue.push(id)
    }

    let visited = 0
    while (queue.length > 0) {
      const current = queue.shift()!
      visited++
      for (const neighbor of adjacency.get(current) ?? []) {
        const newDegree = (inDegree.get(neighbor) ?? 1) - 1
        inDegree.set(neighbor, newDegree)
        if (newDegree === 0) queue.push(neighbor)
      }
    }

    if (visited < nodes.value.length) {
      errors.push({ message: '工作流包含环路' })
    }

    // 检测孤立节点（非 start/end 且无连接）
    for (const node of nodes.value) {
      if (node.type === 'start' || node.type === 'end') continue
      const hasConnection =
        edges.value.some((e) => e.source === node.id || e.target === node.id)
      if (!hasConnection) {
        errors.push({ nodeId: node.id, message: `节点未连接: ${node.data.label || node.type}` })
      }
    }

    validationErrors.value = errors
    return errors
  }

  function newWorkflow(): void {
    nodes.value = []
    edges.value = []
    selectedNode.value = null
    workflowId.value = null
    workflowName.value = '未命名工作流'
    workflowDescription.value = ''
    isDirty.value = false
    undoStack.value = []
    redoStack.value = []
    validationErrors.value = []
  }

  function exportYAML(): string {
    const lines: string[] = []
    lines.push(`id: ${workflowId.value ?? ''}`)
    lines.push(`name: ${workflowName.value}`)
    lines.push(`description: ${workflowDescription.value}`)
    lines.push('nodes:')
    for (const node of nodes.value) {
      lines.push(`  - id: ${node.id}`)
      lines.push(`    type: ${node.type}`)
      lines.push(`    position: { x: ${node.position.x}, y: ${node.position.y} }`)
      if (node.data && Object.keys(node.data).length > 0) {
        lines.push(`    data:`)
        for (const [key, value] of Object.entries(node.data)) {
          lines.push(`      ${key}: ${JSON.stringify(value)}`)
        }
      }
    }
    lines.push('edges:')
    for (const edge of edges.value) {
      lines.push(`  - id: ${edge.id}`)
      lines.push(`    source: ${edge.source}`)
      lines.push(`    target: ${edge.target}`)
      lines.push(`    type: ${edge.type}`)
    }
    return lines.join('\n')
  }

  function loadMockWorkflows(): void {
    workflows.value = [
      {
        id: 'wf-001',
        name: '客服对话工作流',
        description: '处理客户咨询并进行智能分流',
        nodeCount: 5,
        edgeCount: 4,
        updatedAt: '2026-07-30T10:00:00Z',
      },
      {
        id: 'wf-002',
        name: '数据分析工作流',
        description: '从数据库提取数据并生成分析报告',
        nodeCount: 7,
        edgeCount: 8,
        updatedAt: '2026-07-29T15:30:00Z',
      },
      {
        id: 'wf-003',
        name: '文档处理流水线',
        description: '接收文档并进行多步骤处理',
        nodeCount: 6,
        edgeCount: 6,
        updatedAt: '2026-07-28T09:00:00Z',
      },
    ]
  }

  function deleteWorkflow(id: string): void {
    workflows.value = workflows.value.filter((w) => w.id !== id)
  }

  return {
    // State
    nodes,
    edges,
    selectedNode,
    workflowId,
    workflowName,
    workflowDescription,
    isDirty,
    undoStack,
    redoStack,
    validationErrors,
    workflows,
    isLoading,
    // Getters
    canUndo,
    canRedo,
    nodeCount,
    isValid,
    // Actions
    addNode,
    removeNode,
    updateNode,
    selectNode,
    addEdge,
    removeEdge,
    pushSnapshot,
    undo,
    redo,
    validate,
    newWorkflow,
    exportYAML,
    loadMockWorkflows,
    deleteWorkflow,
  }
})
