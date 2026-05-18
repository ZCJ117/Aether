<script setup>
defineProps({
  agents: { type: Array, default: () => [] },
  selectedId: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})

const emit = defineEmits(['select'])

function onChange(e) {
  emit('select', e.target.value)
}
</script>

<template>
  <div class="agent-select-wrap">
    <select
      class="agent-select"
      :value="selectedId"
      @change="onChange"
      aria-label="选择智能体"
    >
      <option v-if="loading" value="">加载中…</option>
      <option v-else-if="error" value="">加载失败</option>
      <option v-else-if="agents.length === 0" value="">加载智能体…</option>
      <option v-else value="">请选择智能体</option>
      <option
        v-for="agent in agents"
        :key="agent.agentId"
        :value="agent.agentId"
      >
        {{ agent.agentName || agent.agentId
        }}<template v-if="agent.agentDesc"> — {{ agent.agentDesc }}</template>
      </option>
    </select>
  </div>
</template>

<style scoped>
.agent-select-wrap {
  position: relative;
  width: 100%;
}

.agent-select-wrap::after {
  content: '';
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  width: 0;
  height: 0;
  border-left: 4px solid transparent;
  border-right: 4px solid transparent;
  border-top: 5px solid var(--text-secondary);
  pointer-events: none;
}

.agent-select {
  width: 100%;
  height: 36px;
  padding: 0 32px 0 13px;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-sm);
  font-family: var(--font);
  font-size: 13px;
  color: var(--text);
  background: var(--surface);
  cursor: pointer;
  outline: none;
  appearance: none;
  -webkit-appearance: none;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.agent-select:focus {
  border-color: var(--text);
  box-shadow: 0 0 0 3px rgba(29, 29, 31, 0.06);
}
</style>
