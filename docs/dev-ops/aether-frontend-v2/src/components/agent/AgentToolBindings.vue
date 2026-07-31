<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { ToolBinding } from '@/types/agent'

const props = defineProps<{
  agentId: string
}>()

const emit = defineEmits<{
  update: [selected: string[]]
}>()

const availableTools = ref<ToolBinding[]>([
  { toolName: 'web_search', toolType: 'builtin' },
  { toolName: 'file_read', toolType: 'builtin' },
  { toolName: 'file_write', toolType: 'builtin' },
  { toolName: 'code_exec', toolType: 'builtin' },
  { toolName: 'weather_query', toolType: 'mcp', source: 'weather-mcp' },
  { toolName: 'db_query', toolType: 'mcp', source: 'database-mcp' },
])

const selected = ref<string[]>([])

onMounted(() => {
  // In real app, fetch bound tools from API
  // For now start with web_search already bound
  selected.value = ['web_search']
})

function toggle(toolName: string) {
  const idx = selected.value.indexOf(toolName)
  if (idx === -1) {
    selected.value.push(toolName)
  } else {
    selected.value.splice(idx, 1)
  }
  emit('update', [...selected.value])
}

const typeLabel: Record<string, string> = { mcp: 'MCP', skill: '技能', builtin: '内置' }
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-5">
    <h3 class="text-sm font-medium text-text-primary mb-4">
      工具绑定 — {{ agentId }}
    </h3>

    <div class="space-y-1 max-h-64 overflow-y-auto">
      <label
        v-for="tool in availableTools"
        :key="tool.toolName"
        class="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-white/[0.03] cursor-pointer transition-colors"
      >
        <input
          type="checkbox"
          :checked="selected.includes(tool.toolName)"
          class="w-4 h-4 rounded border-white/20 bg-white/5 text-blue-500 focus:ring-0 focus:ring-offset-0"
          @change="toggle(tool.toolName)"
        />
        <span class="flex-1 text-sm text-text-primary">{{ tool.toolName }}</span>
        <span class="text-2xs px-1.5 py-0.5 rounded bg-white/5 text-text-muted">
          {{ typeLabel[tool.toolType] }}
        </span>
      </label>
    </div>
  </div>
</template>
