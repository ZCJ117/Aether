<script setup lang="ts">
import { reactive, onMounted } from 'vue'
import type { ModelConfig } from '@/types/model'

const props = withDefaults(defineProps<{
  model?: ModelConfig
  mode: 'create' | 'edit'
}>(), {
  model: undefined,
})

const emit = defineEmits<{
  submit: [data: Partial<ModelConfig>]
  cancel: []
}>()

const providers = [
  { id: 'openai', name: 'OpenAI' },
  { id: 'deepseek', name: 'DeepSeek' },
  { id: 'anthropic', name: 'Anthropic' },
  { id: 'qwen', name: '通义千问' },
  { id: 'custom', name: '自定义' },
]

const form = reactive({
  providerId: '',
  modelId: '',
  apiKey: '',
  apiBase: '',
  maxTokens: 4096,
  timeout: 30000,
})

onMounted(() => {
  if (props.model) {
    form.providerId = props.model.providerId
    form.modelId = props.model.modelId
    form.apiKey = props.model.apiKey || ''
    form.apiBase = props.model.apiBase || ''
    form.maxTokens = props.model.maxTokens || 4096
    form.timeout = props.model.timeout || 30000
  }
})

function handleSubmit() {
  emit('submit', { ...form })
}
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-6">
    <h3 class="text-sm font-medium text-text-primary mb-5">
      {{ mode === 'create' ? '添加模型' : '编辑模型' }}
    </h3>

    <div class="space-y-4">
      <div>
        <label class="block text-xs text-text-muted mb-1">提供者</label>
        <select
          v-model="form.providerId"
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary focus:border-blue-500/50 focus:outline-none"
        >
          <option value="" disabled>选择提供者</option>
          <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </div>

      <div>
        <label class="block text-xs text-text-muted mb-1">模型 ID</label>
        <input
          v-model="form.modelId"
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-blue-500/50 focus:outline-none"
          placeholder="例如: gpt-4, deepseek-v3"
        />
      </div>

      <div>
        <label class="block text-xs text-text-muted mb-1">API Key</label>
        <input
          v-model="form.apiKey"
          type="password"
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-blue-500/50 focus:outline-none"
          placeholder="输入 API Key"
        />
      </div>

      <div>
        <label class="block text-xs text-text-muted mb-1">API 基础地址</label>
        <input
          v-model="form.apiBase"
          class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-blue-500/50 focus:outline-none"
          placeholder="例如: https://api.openai.com/v1"
        />
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="block text-xs text-text-muted mb-1">最大 Token</label>
          <input
            v-model.number="form.maxTokens"
            type="number"
            min="1"
            class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary focus:border-blue-500/50 focus:outline-none"
          />
        </div>
        <div>
          <label class="block text-xs text-text-muted mb-1">超时 (ms)</label>
          <input
            v-model.number="form.timeout"
            type="number"
            min="1000"
            step="1000"
            class="w-full bg-white/5 border border-white/5 rounded-lg px-3 py-2 text-sm text-text-primary focus:border-blue-500/50 focus:outline-none"
          />
        </div>
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
