<script setup lang="ts">
import { ref, computed } from 'vue'
import { ChevronRight, Folder, FileText } from 'lucide-vue-next'
import type { MemoryTreeNode } from '@/types/memory'

const props = defineProps<{
  nodes: MemoryTreeNode[]
}>()

const emit = defineEmits<{
  select: [nodeId: string]
}>()

const expanded = ref<Set<string>>(new Set())

function toggleExpand(id: string) {
  if (expanded.value.has(id)) {
    expanded.value.delete(id)
  } else {
    expanded.value.add(id)
  }
}

function isExpanded(id: string): boolean {
  return expanded.value.has(id)
}

function handleSelect(nodeId: string) {
  emit('select', nodeId)
}
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-5">
    <h3 class="text-sm font-medium text-text-primary mb-4">记忆树</h3>

    <div v-if="nodes.length === 0" class="text-center py-8 text-text-muted text-sm">
      暂无记忆节点
    </div>

    <div v-else class="space-y-0">
      <template v-for="node in nodes" :key="node.id">
        <TreeNodeItem
          :node="node"
          :depth="0"
          :expanded-set="expanded"
          @toggle="toggleExpand"
          @select="handleSelect"
        />
      </template>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'

const TreeNodeItem = defineComponent({
  name: 'TreeNodeItem',
  props: {
    node: { type: Object as PropType<MemoryTreeNode>, required: true },
    depth: { type: Number, required: true },
    expandedSet: { type: Object as PropType<Set<string>>, required: true },
  },
  emits: ['toggle', 'select'],
  setup(props, { emit }) {
    const hasChildren = computed(() => props.node.children && props.node.children.length > 0)
    const expanded = computed(() => props.expandedSet.has(props.node.id))

    function onClick() {
      if (hasChildren.value) {
        emit('toggle', props.node.id)
      } else {
        emit('select', props.node.id)
      }
    }

    return { hasChildren, expanded, onClick }
  },
  template: `
    <div>
      <div
        :style="{ paddingLeft: depth * 20 + 12 + 'px' }"
        class="flex items-center gap-2 py-2 pr-3 rounded-md hover:bg-white/[0.03] cursor-pointer transition-colors text-sm"
        @click="onClick"
      >
        <ChevronRight
          v-if="hasChildren"
          class="w-3.5 h-3.5 text-text-muted transition-transform flex-shrink-0"
          :class="expanded ? 'rotate-90' : ''"
        />
        <span v-else class="w-3.5 flex-shrink-0" />
        <Folder v-if="hasChildren" class="w-4 h-4 text-amber-400 flex-shrink-0" />
        <FileText v-else class="w-4 h-4 text-blue-400 flex-shrink-0" />
        <span class="text-text-primary truncate">{{ node.label }}</span>
      </div>
      <div v-if="hasChildren && expanded">
        <TreeNodeItem
          v-for="child in node.children"
          :key="child.id"
          :node="child"
          :depth="depth + 1"
          :expanded-set="expandedSet"
          @toggle="emit('toggle', $event)"
          @select="emit('select', $event)"
        />
      </div>
    </div>
  `,
})
</script>
