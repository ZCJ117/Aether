<script setup lang="ts">
import { reactive, onMounted } from 'vue'
import type { AgentFullConfig, AgentFormData } from '@/types/agent'

const props = withDefaults(defineProps<{
  agent?: AgentFullConfig
  mode: 'create' | 'edit'
}>(), {
  agent: undefined,
})

const emit = defineEmits<{
  submit: [data: AgentFormData]
  cancel: []
}>()

const form = reactive<AgentFormData>({
  agentName: '',
  agentDesc: '',
  agentType: 're_act',
  instruction: '',
  outputKey: '',
  modelRef: '',
  toolNames: [],
  checkpointEnabled: false,
  checkpointInterval: 5,
  maxTurns: 10,
})

const agentId = props.mode === 'edit' ? props.agent?.agentId || '' : ''

onMounted(() => {
  if (props.agent) {
    Object.assign(form, {
      agentName: props.agent.agentName,
      agentDesc: props.agent.agentDesc,
      agentType: props.agent.agentType,
      instruction: props.agent.instruction,
      outputKey: props.agent.outputKey,
      modelRef: props.agent.modelRef,
      toolNames: props.agent.toolNames || [],
      checkpointEnabled: props.agent.checkpointEnabled,
      checkpointInterval: props.agent.checkpointInterval,
      maxTurns: props.agent.maxTurns,
    })
  }
})

function handleSubmit() {
  emit('submit', { ...form })
}
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-6">
    <h3 class="text-sm font-medium text-text-primary mb-5">
      {{ mode === 'create' ? '创建智能体' : '编辑智能体' }}
    </h3>

    <div class="space-y-4">
      <div v-if="mode === 'edit'">
        <label class="block text-xs text-text-muted mb-1">智能体 ID</label>
        <input
          :value="agentId"
          disabled
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-muted cursor-not-allowed"
        />
      </div>

      <div>
        <label class="block text-xs text-text-muted mb-1">名称</label>
        <input
          v-model="form.agentName"
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-blue-500/50 focus:outline-none"
          placeholder="输入智能体名称"
        />
      </div>

      <div>
        <label class="block text-xs text-text-muted mb-1">描述</label>
        <textarea
          v-model="form.agentDesc"
          rows="2"
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-blue-500/50 focus:outline-none resize-none"
          placeholder="输入智能体描述"
        />
      </div>

      <div>
        <label class="block text-xs text-text-muted mb-1">类型</label>
        <select
          v-model="form.agentType"
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary focus:border-blue-500/50 focus:outline-none"
        >
          <option value="re_act">ReAct</option>
          <option value="plan_act">PlanAct</option>
        </select>
      </div>

      <div>
        <label class="block text-xs text-text-muted mb-1">系统指令</label>
        <textarea
          v-model="form.instruction"
          rows="3"
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-blue-500/50 focus:outline-none resize-none font-mono"
          placeholder="输入系统指令 / prompt"
        />
      </div>

      <div>
        <label class="block text-xs text-text-muted mb-1">输出键名</label>
        <input
          v-model="form.outputKey"
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-blue-500/50 focus:outline-none"
          placeholder="例如: final_output"
        />
      </div>

      <div>
        <label class="block text-xs text-text-muted mb-1">最大回合数</label>
        <input
          v-model.number="form.maxTurns"
          type="number"
          min="1"
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary focus:border-blue-500/50 focus:outline-none"
        />
      </div>
    </div>

    <div class="flex justify-end gap-3 mt-6 pt-4 border-t border-white/5">
      <button
        class="px-4 py-2 text-sm text-text-muted hover:text-text-primary transition-colors"
        @click="emit('cancel')"
      >
        取消
      </button>
      <button
        class="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-500 transition-colors"
        @click="handleSubmit"
      >
        {{ mode === 'create' ? '创建' : '保存' }}
      </button>
    </div>
  </div>
</template>
