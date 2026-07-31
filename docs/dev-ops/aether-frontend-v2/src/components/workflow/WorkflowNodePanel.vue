<script setup lang="ts">
import { computed, watch } from 'vue'
import { X, Hash } from 'lucide-vue-next'
import { useWorkflowStore } from '@/stores/workflow'

const props = defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const store = useWorkflowStore()

const selectedNode = computed(() =>
  store.nodes.find((n) => n.id === store.selectedNode) ?? null
)

const nodeLabel = computed({
  get: () => (selectedNode.value?.data?.label as string) ?? '',
  set: (val: string) => {
    if (selectedNode.value) {
      store.updateNode(selectedNode.value.id, { label: val })
    }
  },
})

const agentId = computed({
  get: () => (selectedNode.value?.data?.agentId as string) ?? '',
  set: (val: string) => {
    if (selectedNode.value) {
      store.updateNode(selectedNode.value.id, { agentId: val })
    }
  },
})

const instruction = computed({
  get: () => (selectedNode.value?.data?.instruction as string) ?? '',
  set: (val: string) => {
    if (selectedNode.value) {
      store.updateNode(selectedNode.value.id, { instruction: val })
    }
  },
})

const outputKey = computed({
  get: () => (selectedNode.value?.data?.outputKey as string) ?? '',
  set: (val: string) => {
    if (selectedNode.value) {
      store.updateNode(selectedNode.value.id, { outputKey: val })
    }
  },
})

const typeLabelMap: Record<string, string> = {
  start: '开始',
  agent: 'Agent',
  condition: '条件',
  merge: '合并',
  end: '结束',
  loop: '循环',
}

const isAgent = computed(() => selectedNode.value?.type === 'agent')
</script>

<template>
  <Transition name="panel-slide">
    <div
      v-if="visible && selectedNode"
      class="w-80 bg-surface-card border-l border-white/5 flex flex-col h-full overflow-y-auto"
    >
      <!-- Header -->
      <div class="flex items-center justify-between px-4 py-3 border-b border-white/5">
        <div class="flex items-center gap-2">
          <Hash class="w-4 h-4 text-secondary" />
          <span class="text-sm font-medium text-primary">节点属性</span>
        </div>
        <button
          @click="emit('close')"
          class="p-1 rounded-md text-secondary hover:text-primary hover:bg-white/5 transition-colors"
        >
          <X class="w-4 h-4" />
        </button>
      </div>

      <!-- Body -->
      <div class="p-4 space-y-4">
        <!-- Type badge -->
        <div class="flex items-center gap-2">
          <span class="text-2xs text-secondary uppercase tracking-wider">类型</span>
          <span class="px-2 py-0.5 rounded text-2xs font-medium bg-white/5 text-secondary">
            {{ typeLabelMap[selectedNode.type] ?? selectedNode.type }}
          </span>
        </div>

        <!-- Label -->
        <div>
          <label class="block text-2xs text-secondary mb-1.5 uppercase tracking-wider">
            标签名称
          </label>
          <input
            v-model="nodeLabel"
            type="text"
            placeholder="输入节点名称"
            class="w-full px-3 py-2 bg-surface-base border border-white/10 rounded-md
                   text-sm text-primary placeholder:text-secondary/30
                   focus:outline-none focus:border-primary/30 transition-colors"
          />
        </div>

        <!-- Agent ID (agent type only) -->
        <div v-if="isAgent">
          <label class="block text-2xs text-secondary mb-1.5 uppercase tracking-wider">
            Agent ID
          </label>
          <input
            v-model="agentId"
            type="text"
            placeholder="输入 Agent ID"
            class="w-full px-3 py-2 bg-surface-base border border-white/10 rounded-md
                   text-sm text-primary placeholder:text-secondary/30 font-mono
                   focus:outline-none focus:border-primary/30 transition-colors"
          />
        </div>

        <!-- Instruction -->
        <div>
          <label class="block text-2xs text-secondary mb-1.5 uppercase tracking-wider">
            指令
          </label>
          <textarea
            v-model="instruction"
            rows="4"
            placeholder="输入智能体指令..."
            class="w-full px-3 py-2 bg-surface-base border border-white/10 rounded-md
                   text-sm text-primary placeholder:text-secondary/30 resize-none
                   focus:outline-none focus:border-primary/30 transition-colors"
          />
        </div>

        <!-- Output Key -->
        <div>
          <label class="block text-2xs text-secondary mb-1.5 uppercase tracking-wider">
            输出键名
          </label>
          <input
            v-model="outputKey"
            type="text"
            placeholder="例如: result, response"
            class="w-full px-3 py-2 bg-surface-base border border-white/10 rounded-md
                   text-sm text-primary placeholder:text-secondary/30 font-mono
                   focus:outline-none focus:border-primary/30 transition-colors"
          />
        </div>
      </div>

      <!-- Footer -->
      <div class="mt-auto p-4 border-t border-white/5">
        <button
          @click="store.removeNode(selectedNode.id); emit('close')"
          class="w-full px-3 py-2 rounded-md text-xs font-medium
                 text-accent-red/80 hover:text-accent-red
                 border border-accent-red/20 hover:border-accent-red/30
                 hover:bg-accent-red/5 transition-colors"
        >
          删除节点
        </button>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.panel-slide-enter-active,
.panel-slide-leave-active {
  transition: transform 0.2s ease, opacity 0.2s ease;
}

.panel-slide-enter-from,
.panel-slide-leave-to {
  transform: translateX(16px);
  opacity: 0;
}
</style>
