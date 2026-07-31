<script setup lang="ts">
import { ref } from 'vue'
import {
  Undo2,
  Redo2,
  CheckCheck,
  FileCode2,
  Play,
  Plus,
} from 'lucide-vue-next'
import { useWorkflowStore } from '@/stores/workflow'

const props = defineProps<{
  canUndo: boolean
  canRedo: boolean
  isDirty: boolean
}>()

const emit = defineEmits<{
  addNode: [type: string]
  undo: []
  redo: []
  validate: []
  export: []
}>()

type NodeType = 'start' | 'agent' | 'condition' | 'merge' | 'end'

interface NodeTypeOption {
  type: NodeType
  label: string
  color: string
}

const nodeTypes: NodeTypeOption[] = [
  { type: 'start', label: '开始', color: '#4ADE80' },
  { type: 'agent', label: 'Agent', color: '#60A5FA' },
  { type: 'condition', label: '条件', color: '#FBBF24' },
  { type: 'merge', label: '合并', color: '#A78BFA' },
  { type: 'end', label: '结束', color: '#F87171' },
]

function handleAddNode(type: NodeType) {
  const store = useWorkflowStore()
  store.addNode(type, { x: 200 + Math.random() * 200, y: 150 + Math.random() * 200 })
  emit('addNode', type)
}

function handleUndo() {
  const store = useWorkflowStore()
  store.undo()
  emit('undo')
}

function handleRedo() {
  const store = useWorkflowStore()
  store.redo()
  emit('redo')
}

function handleValidate() {
  const store = useWorkflowStore()
  store.validate()
  emit('validate')
}

function handleExport() {
  emit('export')
}
</script>

<template>
  <div class="flex items-center gap-2 px-4 py-2 bg-surface-card border-b border-white/5">
    <!-- Node type buttons -->
    <div class="flex items-center gap-1">
      <button
        v-for="nt in nodeTypes"
        :key="nt.type"
        @click="handleAddNode(nt.type)"
        :style="{ borderColor: nt.color + '40' }"
        class="flex items-center gap-1.5 px-2.5 py-1.5 rounded-md border text-xs font-medium
               hover:bg-white/5 transition-colors text-secondary hover:text-primary"
        :title="`添加 ${nt.label} 节点`"
      >
        <span
          class="w-2 h-2 rounded-full"
          :style="{ backgroundColor: nt.color }"
        />
        <span class="hidden sm:inline">{{ nt.label }}</span>
        <Plus class="w-3 h-3 hidden sm:block" />
      </button>
    </div>

    <!-- Divider -->
    <div class="w-px h-6 bg-white/10 mx-1" />

    <!-- Undo / Redo -->
    <div class="flex items-center gap-0.5">
      <button
        @click="handleUndo"
        :disabled="!canUndo"
        class="p-1.5 rounded-md text-secondary hover:text-primary hover:bg-white/5
               transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
        title="撤销 (Ctrl+Z)"
      >
        <Undo2 class="w-4 h-4" />
      </button>
      <button
        @click="handleRedo"
        :disabled="!canRedo"
        class="p-1.5 rounded-md text-secondary hover:text-primary hover:bg-white/5
               transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
        title="重做 (Ctrl+Y)"
      >
        <Redo2 class="w-4 h-4" />
      </button>
    </div>

    <!-- Divider -->
    <div class="w-px h-6 bg-white/10 mx-1" />

    <!-- Validate -->
    <button
      @click="handleValidate"
      class="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium
             text-accent-green/80 hover:text-accent-green hover:bg-accent-green/10
             border border-accent-green/20 hover:border-accent-green/30 transition-colors"
      title="验证工作流"
    >
      <CheckCheck class="w-3.5 h-3.5" />
      <span class="hidden sm:inline">验证</span>
    </button>

    <!-- Export -->
    <button
      @click="handleExport"
      class="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium
             text-accent-blue/80 hover:text-accent-blue hover:bg-accent-blue/10
             border border-accent-blue/20 hover:border-accent-blue/30 transition-colors"
      title="导出 YAML"
    >
      <FileCode2 class="w-3.5 h-3.5" />
      <span class="hidden sm:inline">导出</span>
    </button>

    <!-- Dirty indicator -->
    <div v-if="isDirty" class="flex items-center gap-1 ml-auto text-accent-yellow/60 text-2xs">
      <span class="w-1.5 h-1.5 rounded-full bg-accent-yellow" />
      <span class="hidden sm:inline">未保存</span>
    </div>
  </div>
</template>
