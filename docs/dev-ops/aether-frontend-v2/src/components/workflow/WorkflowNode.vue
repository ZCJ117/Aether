<script setup lang="ts">
import { computed } from 'vue'

interface DAGNode {
  id: string
  type: string
  position: { x: number; y: number }
  data: Record<string, unknown>
}

const props = defineProps<{
  node: DAGNode
  selected: boolean
}>()

const NODE_WIDTH = 160
const NODE_HEIGHT = 60

const colorMap: Record<string, string> = {
  start: '#4ADE80',
  agent: '#60A5FA',
  condition: '#FBBF24',
  merge: '#A78BFA',
  end: '#F87171',
  loop: '#60A5FA',
}

const labelMap: Record<string, string> = {
  start: '开始',
  agent: 'Agent',
  condition: '条件',
  merge: '合并',
  end: '结束',
  loop: '循环',
}

const nodeColor = computed(() => colorMap[props.node.type] ?? '#6B7280')

const displayLabel = computed(() => {
  const label = props.node.data?.label
  return label ? String(label) : (labelMap[props.node.type] ?? props.node.type)
})

defineExpose({ NODE_WIDTH, NODE_HEIGHT })
</script>

<template>
  <g class="workflow-node cursor-pointer">
    <!-- Node rectangle -->
    <rect
      :width="NODE_WIDTH"
      :height="NODE_HEIGHT"
      rx="8"
      :fill="nodeColor"
      fill-opacity="0.12"
      :stroke="nodeColor"
      :stroke-width="selected ? 2.5 : 1.5"
      :stroke-dasharray="selected ? '6 3' : 'none'"
      class="transition-all duration-150"
    />
    <!-- Main label -->
    <text
      :x="NODE_WIDTH / 2"
      :y="NODE_HEIGHT / 2"
      text-anchor="middle"
      dominant-baseline="middle"
      fill="#DEDBC8"
      font-size="13"
      font-weight="500"
      font-family="Inter, sans-serif"
    >
      {{ displayLabel }}
    </text>
    <!-- Type badge -->
    <text
      :x="NODE_WIDTH / 2"
      :y="NODE_HEIGHT - 10"
      text-anchor="middle"
      :fill="nodeColor"
      font-size="10"
      font-family="Inter, sans-serif"
      opacity="0.8"
    >
      {{ node.type }}
    </text>
    <!-- Connection ports -->
    <circle
      :cx="NODE_WIDTH / 2"
      cy="0"
      r="4"
      :fill="nodeColor"
      stroke="#0f0f0f"
      stroke-width="1.5"
      class="opacity-0 hover:opacity-100 transition-opacity"
    />
    <circle
      :cx="NODE_WIDTH / 2"
      :cy="NODE_HEIGHT"
      r="4"
      :fill="nodeColor"
      stroke="#0f0f0f"
      stroke-width="1.5"
      class="opacity-0 hover:opacity-100 transition-opacity"
    />
  </g>
</template>
