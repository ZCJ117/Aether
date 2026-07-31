<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  agentId: string
}>()

const emit = defineEmits<{
  update: [mode: string]
}>()

const permissions = [
  { value: 'DEFAULT', label: '默认', desc: '所有工具调用都需用户确认' },
  { value: 'PLAN', label: '计划', desc: '仅确认执行计划，工具调用自动执行' },
  { value: 'ACCEPT_EDITS', label: '接受编辑', desc: '自动接受代码编辑，其他操作需确认' },
  { value: 'BYPASS', label: '跳过', desc: '跳过所有确认，自动执行所有工具调用' },
]

const selected = ref('DEFAULT')

function select(mode: string) {
  selected.value = mode
  emit('update', mode)
}
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-5">
    <h3 class="text-sm font-medium text-text-primary mb-4">
      权限配置 — {{ agentId }}
    </h3>

    <div class="space-y-3">
      <label
        v-for="perm in permissions"
        :key="perm.value"
        class="flex items-start gap-3 px-3 py-3 rounded-lg border cursor-pointer transition-colors"
        :class="selected === perm.value ? 'border-blue-500/30 bg-blue-500/5' : 'border-white/5 hover:bg-white/[0.03]'"
      >
        <input
          type="radio"
          :value="perm.value"
          :checked="selected === perm.value"
          class="mt-0.5 w-4 h-4 text-blue-500 bg-white/5 border-white/20 focus:ring-0 focus:ring-offset-0"
          @change="select(perm.value)"
        />
        <div class="flex-1 min-w-0">
          <span class="block text-sm text-text-primary">{{ perm.label }}</span>
          <span class="block text-xs text-text-muted mt-0.5">{{ perm.desc }}</span>
        </div>
      </label>
    </div>
  </div>
</template>
