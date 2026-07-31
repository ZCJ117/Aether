<script setup lang="ts">
import { onMounted } from 'vue'
import { useModelStore } from '@/stores/model'

const modelStore = useModelStore()

onMounted(() => {
  modelStore.loadModels()
})
</script>

<template>
  <div class="model-management">
    <div class="page-header">
      <h1>模型管理</h1>
      <button class="btn-create">+ 添加模型</button>
    </div>

    <div v-if="modelStore.isLoading" class="loading">加载中...</div>

    <div v-else-if="modelStore.models.length === 0" class="empty">
      <p>暂无模型配置，点击"添加模型"开始</p>
    </div>

    <div v-else class="model-grid">
      <div
        v-for="model in modelStore.models"
        :key="model.id"
        class="model-card"
      >
        <div class="model-header">
          <span class="model-provider">{{ model.providerId }}</span>
          <span :class="['model-status', model.status]">
            {{ model.status === 'ACTIVE' ? '活跃' : model.status }}
          </span>
        </div>
        <div class="model-body">
          <h3>{{ model.modelId }}</h3>
          <span class="model-id">{{ model.id }}</span>
        </div>
        <div class="model-footer">
          <button class="btn-test">测试连接</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.model-management {
  padding: 1.5rem;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

.page-header h1 {
  font-size: 1.5rem;
  margin: 0;
  color: var(--text-primary, #eee);
}

.btn-create {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 8px;
  background: var(--accent-color, #6366f1);
  color: #fff;
  font-size: 0.875rem;
  cursor: pointer;
  transition: opacity 0.2s;
}

.btn-create:hover {
  opacity: 0.9;
}

.loading, .empty {
  text-align: center;
  padding: 3rem;
  color: var(--text-secondary, #888);
}

.model-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
}

.model-card {
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 10px;
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  transition: border-color 0.2s;
}

.model-card:hover {
  border-color: rgba(99, 102, 241, 0.4);
}

.model-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.model-provider {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--accent-color, #6366f1);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.model-status {
  font-size: 0.6875rem;
  padding: 0.125rem 0.5rem;
  border-radius: 999px;
}

.model-status.ACTIVE {
  background: rgba(34, 197, 94, 0.15);
  color: #22c55e;
}

.model-status.INACTIVE {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
}

.model-body h3 {
  margin: 0;
  font-size: 1rem;
  color: var(--text-primary, #eee);
}

.model-id {
  font-size: 0.75rem;
  color: var(--text-secondary, #666);
  font-family: monospace;
}

.model-footer {
  padding-top: 0.5rem;
  border-top: 1px solid var(--border-color, #2a2a4a);
}

.btn-test {
  padding: 0.375rem 0.75rem;
  border: 1px solid rgba(99, 102, 241, 0.3);
  border-radius: 6px;
  background: transparent;
  color: var(--accent-color, #6366f1);
  font-size: 0.75rem;
  cursor: pointer;
  transition: opacity 0.2s;
}

.btn-test:hover {
  opacity: 0.8;
}
</style>
