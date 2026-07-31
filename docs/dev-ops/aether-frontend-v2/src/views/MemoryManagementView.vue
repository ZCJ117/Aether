<script setup lang="ts">
import { onMounted } from 'vue'
import { useMemoryStore } from '@/stores/memory'
import FutureVersionBanner from '@/components/common/FutureVersionBanner.vue'

const memoryStore = useMemoryStore()

onMounted(() => {
  memoryStore.loadMemories()
})
</script>

<template>
  <div class="memory-management">
    <div class="page-header">
      <h1>记忆管理</h1>
    </div>

    <FutureVersionBanner />

    <div v-if="memoryStore.isLoading" class="loading">加载中...</div>

    <div v-else class="memory-columns">
      <!-- Working Memory -->
      <div class="memory-column">
        <div class="column-header working">
          <h3>工作记忆</h3>
          <span class="count">{{ memoryStore.workingMemories.length }}</span>
        </div>
        <div class="memory-items">
          <div
            v-for="mem in memoryStore.workingMemories"
            :key="mem.id"
            class="memory-item"
          >
            <h4>{{ mem.title }}</h4>
            <p>{{ mem.content }}</p>
            <span class="memory-type">{{ mem.type }}</span>
          </div>
          <div v-if="memoryStore.workingMemories.length === 0" class="empty-column">
            暂无工作记忆
          </div>
        </div>
      </div>

      <!-- Short-term Memory -->
      <div class="memory-column">
        <div class="column-header short-term">
          <h3>短期记忆</h3>
          <span class="count">{{ memoryStore.shortTermMemories.length }}</span>
        </div>
        <div class="memory-items">
          <div
            v-for="mem in memoryStore.shortTermMemories"
            :key="mem.id"
            class="memory-item"
          >
            <h4>{{ mem.title }}</h4>
            <p>{{ mem.content }}</p>
            <span class="memory-type">{{ mem.type }}</span>
          </div>
          <div v-if="memoryStore.shortTermMemories.length === 0" class="empty-column">
            暂无短期记忆
          </div>
        </div>
      </div>

      <!-- Long-term Memory -->
      <div class="memory-column">
        <div class="column-header long-term">
          <h3>长期记忆</h3>
          <span class="count">{{ memoryStore.longTermMemories.length }}</span>
        </div>
        <div class="memory-items">
          <div
            v-for="mem in memoryStore.longTermMemories"
            :key="mem.id"
            class="memory-item"
          >
            <h4>{{ mem.title }}</h4>
            <p>{{ mem.content }}</p>
            <span class="memory-type">{{ mem.type }}</span>
          </div>
          <div v-if="memoryStore.longTermMemories.length === 0" class="empty-column">
            暂无长期记忆
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.memory-management {
  padding: 1.5rem;
}

.page-header {
  margin-bottom: 1.5rem;
}

.page-header h1 {
  font-size: 1.5rem;
  margin: 0;
  color: var(--text-primary, #eee);
}

.loading {
  text-align: center;
  padding: 3rem;
  color: var(--text-secondary, #888);
}

.memory-columns {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
}

.memory-column {
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 10px;
  overflow: hidden;
}

.column-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.875rem 1rem;
  border-bottom: 1px solid var(--border-color, #2a2a4a);
}

.column-header h3 {
  margin: 0;
  font-size: 0.875rem;
  color: var(--text-primary, #eee);
}

.column-header .count {
  font-size: 0.75rem;
  padding: 0.125rem 0.5rem;
  border-radius: 999px;
  font-weight: 600;
}

.column-header.working {
  border-bottom-color: #22c55e;
}

.column-header.working .count {
  background: rgba(34, 197, 94, 0.15);
  color: #22c55e;
}

.column-header.short-term {
  border-bottom-color: #f59e0b;
}

.column-header.short-term .count {
  background: rgba(245, 158, 11, 0.15);
  color: #f59e0b;
}

.column-header.long-term {
  border-bottom-color: #6366f1;
}

.column-header.long-term .count {
  background: rgba(99, 102, 241, 0.15);
  color: #6366f1;
}

.memory-items {
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  min-height: 200px;
}

.memory-item {
  padding: 0.75rem;
  background: var(--bg-primary, #0f0f1a);
  border-radius: 8px;
  border: 1px solid var(--border-color, #2a2a4a);
}

.memory-item h4 {
  margin: 0 0 0.25rem;
  font-size: 0.8125rem;
  color: var(--text-primary, #eee);
}

.memory-item p {
  margin: 0 0 0.375rem;
  font-size: 0.75rem;
  color: var(--text-secondary, #aaa);
  line-height: 1.4;
}

.memory-type {
  font-size: 0.625rem;
  padding: 0.0625rem 0.375rem;
  border-radius: 4px;
  background: rgba(99, 102, 241, 0.1);
  color: var(--accent-color, #6366f1);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.empty-column {
  text-align: center;
  padding: 2rem 1rem;
  color: var(--text-secondary, #666);
  font-size: 0.8125rem;
}
</style>
