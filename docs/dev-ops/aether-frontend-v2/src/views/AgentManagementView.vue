<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'

const router = useRouter()
const agentStore = useAgentStore()

onMounted(() => {
  agentStore.loadManagementAgents()
})

function handleEdit(id: string) {
  router.push(`/app/agents/${id}`)
}

function handleDelete(id: string) {
  if (confirm('确定要删除该智能体吗？')) {
    agentStore.removeManagementAgent(id)
  }
}
</script>

<template>
  <div class="agent-management">
    <div class="page-header">
      <h1>智能体管理</h1>
      <button class="btn-create">+ 创建智能体</button>
    </div>

    <div v-if="agentStore.managementLoading" class="loading">加载中...</div>

    <div v-else-if="agentStore.managementAgents.length === 0" class="empty">
      <p>暂无智能体，点击"创建智能体"开始</p>
    </div>

    <div v-else class="agent-table">
      <div class="table-header">
        <span class="col-name">名称</span>
        <span class="col-type">类型</span>
        <span class="col-tools">工具数</span>
        <span class="col-status">状态</span>
        <span class="col-actions">操作</span>
      </div>
      <div
        v-for="agent in agentStore.managementAgents"
        :key="agent.agentId"
        class="table-row"
      >
        <span class="col-name">{{ agent.agentName || agent.agentId }}</span>
        <span class="col-type">{{ agent.agentType || '通用' }}</span>
        <span class="col-tools">{{ (agent.tools || []).length }}</span>
        <span class="col-status">
          <span :class="['status-badge', agent.status]">
            {{ agent.status === 'active' ? '运行中' : agent.status === 'inactive' ? '已停用' : agent.status }}
          </span>
        </span>
        <span class="col-actions">
          <button class="btn-edit" @click="handleEdit(agent.agentId)">编辑</button>
          <button class="btn-delete" @click="handleDelete(agent.agentId)">删除</button>
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.agent-management {
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

.agent-table {
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 10px;
  overflow: hidden;
}

.table-header {
  display: grid;
  grid-template-columns: 2fr 1fr 0.75fr 1fr 1.25fr;
  padding: 0.75rem 1rem;
  background: rgba(99, 102, 241, 0.08);
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text-secondary, #aaa);
  border-bottom: 1px solid var(--border-color, #2a2a4a);
}

.table-row {
  display: grid;
  grid-template-columns: 2fr 1fr 0.75fr 1fr 1.25fr;
  padding: 0.75rem 1rem;
  align-items: center;
  font-size: 0.875rem;
  color: var(--text-primary, #eee);
  border-bottom: 1px solid var(--border-color, #2a2a4a);
  transition: background 0.15s;
}

.table-row:last-child {
  border-bottom: none;
}

.table-row:hover {
  background: rgba(99, 102, 241, 0.05);
}

.status-badge {
  display: inline-block;
  padding: 0.125rem 0.5rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 500;
}

.status-badge.active {
  background: rgba(34, 197, 94, 0.15);
  color: #22c55e;
}

.status-badge.inactive {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
}

.col-actions {
  display: flex;
  gap: 0.5rem;
}

.btn-edit, .btn-delete {
  padding: 0.25rem 0.625rem;
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 6px;
  font-size: 0.75rem;
  cursor: pointer;
  transition: opacity 0.2s;
  background: transparent;
}

.btn-edit {
  color: var(--accent-color, #6366f1);
  border-color: rgba(99, 102, 241, 0.3);
}

.btn-delete {
  color: #ef4444;
  border-color: rgba(239, 68, 68, 0.3);
}

.btn-edit:hover, .btn-delete:hover {
  opacity: 0.8;
}
</style>
