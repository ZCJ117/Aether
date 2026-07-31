<script setup lang="ts">
import { ref, computed } from 'vue'
import { useWorkflowStore } from '@/stores/workflow'
import WorkflowNode from './WorkflowNode.vue'

const store = useWorkflowStore()

const svgRef = ref<SVGSVGElement | null>(null)

// ========== Drag state ==========
const DRAG_THRESHOLD = 3
const draggingNodeId = ref<string | null>(null)
const dragStartMouse = ref({ x: 0, y: 0 })
const dragStartNodePos = ref({ x: 0, y: 0 })
const hasDragged = ref(false)

// Node dimensions (must match WorkflowNode)
const NODE_WIDTH = 160
const NODE_HEIGHT = 60

// Grid config
const GRID_SIZE = 24
const DOT_RADIUS = 1.2

// ========== Edge helpers ==========
function getSourcePoint(nodeId: string) {
  const node = store.nodes.find((n) => n.id === nodeId)
  if (!node) return { x: 0, y: 0 }
  return {
    x: node.position.x + NODE_WIDTH / 2,
    y: node.position.y + NODE_HEIGHT,
  }
}

function getTargetPoint(nodeId: string) {
  const node = store.nodes.find((n) => n.id === nodeId)
  if (!node) return { x: 0, y: 0 }
  return {
    x: node.position.x + NODE_WIDTH / 2,
    y: node.position.y,
  }
}

// ========== Mouse handlers ==========
function handleNodeMouseDown(e: MouseEvent, nodeId: string) {
  e.stopPropagation()
  e.preventDefault()

  draggingNodeId.value = nodeId
  dragStartMouse.value = { x: e.clientX, y: e.clientY }
  hasDragged.value = false

  const node = store.nodes.find((n) => n.id === nodeId)
  if (node) {
    dragStartNodePos.value = { ...node.position }
  }
}

function handleSvgMouseMove(e: MouseEvent) {
  if (!draggingNodeId.value || !svgRef.value) return

  const dx = e.clientX - dragStartMouse.value.x
  const dy = e.clientY - dragStartMouse.value.y

  if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
    hasDragged.value = true
  }

  if (hasDragged.value) {
    const svgRect = svgRef.value.getBoundingClientRect()
    const node = store.nodes.find((n) => n.id === draggingNodeId.value)
    if (node) {
      const newX = e.clientX - svgRect.left - (dragStartMouse.value.x - dragStartNodePos.value.x - svgRect.left)
      const newY = e.clientY - svgRect.top - (dragStartMouse.value.y - dragStartNodePos.value.y - svgRect.top)
      node.position.x = newX
      node.position.y = newY
    }
  }
}

function handleSvgMouseUp(_e: MouseEvent) {
  if (draggingNodeId.value && !hasDragged.value) {
    // Click, not drag — select the node
    store.selectNode(draggingNodeId.value)
  }
  draggingNodeId.value = null
  hasDragged.value = false
}

function handleSvgMouseDown(e: MouseEvent) {
  const target = e.target as SVGElement
  // Deselect when clicking empty canvas or grid background
  if (target === svgRef.value || target.closest('.grid-background')) {
    store.selectNode(null)
  }
}
</script>

<template>
  <div class="w-full h-[calc(100vh-200px)] bg-surface-base overflow-hidden relative">
    <svg
      ref="svgRef"
      class="w-full h-full block"
      @mousedown="handleSvgMouseDown"
      @mousemove="handleSvgMouseMove"
      @mouseup="handleSvgMouseUp"
      @mouseleave="handleSvgMouseUp"
    >
      <!-- Grid dot pattern -->
      <defs>
        <pattern
          id="dot-grid"
          :width="GRID_SIZE"
          :height="GRID_SIZE"
          patternUnits="userSpaceOnUse"
        >
          <circle
            :cx="GRID_SIZE / 2"
            :cy="GRID_SIZE / 2"
            :r="DOT_RADIUS"
            fill="#DEDBC8"
            opacity="0.06"
          />
        </pattern>

        <!-- Arrowhead marker -->
        <marker
          id="arrowhead"
          markerWidth="10"
          markerHeight="7"
          refX="9"
          refY="3.5"
          orient="auto"
        >
          <polygon
            points="0 0, 10 3.5, 0 7"
            fill="#DEDBC8"
            opacity="0.3"
          />
        </marker>
      </defs>

      <!-- Grid background rect -->
      <rect class="grid-background" width="100%" height="100%" fill="url(#dot-grid)" />

      <!-- Edges -->
      <g>
        <line
          v-for="edge in store.edges"
          :key="edge.id"
          :x1="getSourcePoint(edge.source).x"
          :y1="getSourcePoint(edge.source).y"
          :x2="getTargetPoint(edge.target).x"
          :y2="getTargetPoint(edge.target).y"
          stroke="#DEDBC8"
          stroke-opacity="0.2"
          stroke-width="2"
          marker-end="url(#arrowhead)"
          class="pointer-events-none"
        />
      </g>

      <!-- Nodes -->
      <g
        v-for="node in store.nodes"
        :key="node.id"
        :transform="`translate(${node.position.x}, ${node.position.y})`"
        @mousedown="(e) => handleNodeMouseDown(e, node.id)"
      >
        <WorkflowNode
          :node="node"
          :selected="store.selectedNode === node.id"
        />
      </g>
    </svg>

    <!-- Empty state hint -->
    <div
      v-if="store.nodes.length === 0"
      class="absolute inset-0 flex items-center justify-center pointer-events-none"
    >
      <div class="text-center">
        <p class="text-secondary text-sm mb-2">画布为空</p>
        <p class="text-secondary/50 text-xs">使用工具栏添加节点开始构建工作流</p>
      </div>
    </div>
  </div>
</template>
