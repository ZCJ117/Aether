<script setup lang="ts">
import { onMounted } from 'vue'
import { useModelStore } from '@/stores/model'

const modelStore = useModelStore()

onMounted(() => {
  modelStore.loadModels()
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">模型</h1>

    <div v-if="modelStore.isLoading" class="state-msg">加载中...</div>

    <template v-else>
      <div v-if="modelStore.models.length === 0" class="state-msg">暂无模型</div>
      <div v-else class="card-grid">
        <div v-for="m in modelStore.models" :key="m.id" class="model-card">
          <div class="mc-provider">{{ m.providerId }}</div>
          <div class="mc-name">{{ m.modelId }}</div>
          <div class="mc-meta">{{ m.maxTokens ? m.maxTokens + ' max tokens' : '' }}</div>
          <div class="mc-footer">
            <span :class="['conn-dot', m.status === 'ACTIVE' ? 'on' : 'off']">
              {{ m.status === 'ACTIVE' ? '已连接' : m.status === 'ERROR' ? '错误' : '未连接' }}
            </span>
            <span class="test-link">测试连接 →</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page { padding: 20px; }
.page-title { font-size: 28px; font-weight: 700; color: #F5F5F7; margin: 0 0 18px; }

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.model-card {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 14px;
  padding: 16px;
}

.mc-provider {
  font-size: 10px;
  color: #636366;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
}

.mc-name {
  font-size: 16px;
  font-weight: 600;
  color: #F5F5F7;
}

.mc-meta {
  font-size: 12px;
  color: #98989D;
  margin-top: 4px;
}

.mc-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}

.conn-dot {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
}

.conn-dot::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.conn-dot.on { color: #30D158; }
.conn-dot.on::before { background: #30D158; }
.conn-dot.off { color: #FF453A; }
.conn-dot.off::before { background: #FF453A; }

.test-link { font-size: 11px; color: #5AC8FA; cursor: pointer; }

.state-msg {
  text-align: center;
  padding: 48px 0;
  font-size: 14px;
  color: #98989D;
}
</style>
